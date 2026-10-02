package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.event.InputHandler;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.malilib.util.EntityUtils;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.BotInventoryScreen;
import ml.pypals.simulatica.SimulaticaClient;
import ml.pypals.simulatica.simulation.SimulationRaycast;
import ml.pypals.simulatica.simulation.SimulatedUseOnContext;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.ProjectionBridge;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import ml.pypals.simulatica.simulation.server.SimulationMenus;
import ml.pypals.simulatica.simulation.server.SimulationViewer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 新增实体射线追踪：攻击与右键支持命中模拟生物（26.2 interact 三参，含命中点）
 * - 攻击由固定值 hurtServer 改为 mc.player.attack（原版攻击管线），并补调附魔 post-attack 效果；手持物快照恢复
 * - 物品使用改走 SimulatedUseOnContext，避免把方块/刷怪蛋实体放进真实世界
 * - 持剑左键不再破坏原理图方块（只挥手），避免挥空误删投影内容
 * - 未命中原理图方块时回退到区域底部虚拟地面，使模拟区域最底层可放置方块
 * - 投射物类物品（弓/弩/三叉戟/雪球/鸡蛋/末影珍珠/药水/经验瓶/风弹/钓竿）右键改在模拟世界使用：蓄力类先拉弓、瞬发类直接生成投射物
 * - 放置落点按 26.2 语义解析（命中处可替换 → 放在命中处，否则相邻格），底部虚拟地面命中指向区域最低层本身；修复方块落到区域底面之下后不回写、不渲染、不可交互的问题
 */
@Mixin(value = InputHandler.class, remap = false)
public class InputHandlerMixin {

    @Inject(method = "handleMouseScroll", at = @At("HEAD"), cancellable = true)
    private void workshop$nativeScroll(CallbackInfoReturnable<Boolean> cir) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) cir.setReturnValue(false);
    }

    @Unique
    private static final double SIMULATICA_REACH = 10.0;

    @Unique
    private record SimulaticaEntityHit(Entity entity, Vec3 location) {
    }

    @Inject(method = "handleAttackKey", at = @At("HEAD"), cancellable = true)
    private void simulatica$handleAttackKey(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) { cir.setReturnValue(false); return; }
        if (mc.player == null || DataManager.getToolMode() != SimulaticaClient.SIMULATE) return;

        BlockHitResult hit = simulatica$traceSchematicHit(mc);
        double blockDistance = hit != null
                ? hit.getLocation().distanceTo(mc.player.getEyePosition())
                : Double.MAX_VALUE;

        SimulaticaEntityHit entityHit = simulatica$traceSimulationEntity(mc, blockDistance);
        if (entityHit != null) {
            simulatica$attack(mc, entityHit.entity());
            cir.setReturnValue(true);
            return;
        }

        if (hit == null) {
            cir.setReturnValue(false);
            return;
        }

        BlockPos pos = hit.getBlockPos();
        SimulationManager.Target simTarget = SimulationManager.getInstance().findTarget(pos);
        ProjectionBridge bridge = simTarget != null ? simTarget.bridge() : null;

        if (bridge != null) {
            // A sword is a weapon here and nothing else. Vanilla swords do break some blocks fast,
            // and a swing that misses a mob used to fall through to the branch below and delete a
            // piece of the projection. Swords now only swing.
            if (mc.player.getItemInHand(InteractionHand.MAIN_HAND).is(ItemTags.SWORDS)) {
                mc.player.swing(mc.player.getUsedItemHand());
                cir.setReturnValue(true);
                return;
            }

            SimulationLevel level = bridge.level();
            BlockPos sim = bridge.toSim(pos);
            BlockState previous = level.getBlockState(sim);
            level.setBlock(sim, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL, 512);
            // Vanilla only makes a break sound via levelEvent(2001), which the client resolves
            // against the real block at that position -- air, where the projection is. Say it here.
            if (!previous.isAir()) {
                SoundType soundType = previous.getSoundType();
                level.playSound(null, sim, soundType.getBreakSound(), SoundSource.BLOCKS,
                        (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
            }
            mc.player.swing(mc.player.getUsedItemHand());
        } else {
            simulatica$reportNotSimulated(mc, simTarget);
        }

        cir.setReturnValue(true);
    }

    @Inject(method = "handleUseKey", at = @At("HEAD"), cancellable = true)
    private void simulatica$handleUseKey(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) { cir.setReturnValue(false); return; }
        if (mc.player == null || DataManager.getToolMode() != SimulaticaClient.SIMULATE) return;

        // Projectile weapons go first: aiming a bow at a simulated mob must draw the bow, not
        // open the mob's interaction menu. Their use()/releaseUsing() is routed into the
        // simulation level, so the projectile spawns and flies there instead of the real world.
        ItemStack held = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (simulatica$isProjectileItem(held.getItem()) && simulatica$useProjectile(mc, held)) {
            cir.setReturnValue(true);
            return;
        }

        // 空手右键：准星指向假人（模拟世界中的 ServerPlayer，非 viewer）时直接打开其背包。
        if (held.isEmpty()) {
            SimulaticaEntityHit botHit = simulatica$traceSimulationEntity(mc, SimulationRaycast.REACH);
            if (botHit != null && botHit.entity() instanceof ServerPlayer bot
                    && !SimulationViewer.isViewer(bot)) {
                Minecraft.getInstance().gui.setScreen(new BotInventoryScreen(bot));
                cir.setReturnValue(true);
                return;
            }
        }

        BlockHitResult hitResult = simulatica$traceSchematicHit(mc);
        double blockDistance = hitResult != null
                ? hitResult.getLocation().distanceTo(mc.player.getEyePosition())
                : Double.MAX_VALUE;

        // Entity interactions come first: igniting a creeper, healing an iron golem, breeding...
        SimulaticaEntityHit entityHit = simulatica$traceSimulationEntity(mc, blockDistance);
        if (entityHit != null) {
            simulatica$interactWithEntity(mc, entityHit);
            cir.setReturnValue(true);
            return;
        }

        if (hitResult == null) {
            // Nothing in the schematic to aim at. The region floor is collision-only, so it has no
            // block for the trace to hit either -- synthesise the hit so the bottom layer can be
            // built on like any other surface.
            hitResult = SimulationRaycast.traceFloor(mc);
            if (hitResult == null) {
                return;
            }
        }

        BlockPos pos = hitResult.getBlockPos();
        // Match the clicked block: it is always inside the placement box (a schematic block, or
        // the floor hit synthesised by traceFloor on the region's bottom layer).
        SimulationManager.Target target = SimulationManager.getInstance().findTarget(pos);
        ProjectionBridge bridge = target != null ? target.bridge() : null;

        if (bridge == null) {
            simulatica$reportNotSimulated(mc, target);
            cir.setReturnValue(true);
            return;
        }

        SimulationLevel level = bridge.level();
        // 26.2 placement semantics (BlockPlaceContext, bytecode-verified): when the clicked state
        // can be replaced, the block goes INTO that position; otherwise one step along the clicked
        // face. The floor hit points at the region's bottom layer, which is air in the simulation
        // -- under the old "clicked block plus face" assumption the block landed one layer BELOW
        // the region, where no bridge picks the change up: never written to the projection, never
        // rendered, impossible to break or interact with.
        BlockState clicked = level.getBlockState(bridge.toSim(pos));
        BlockPos landing = clicked.canBeReplaced() ? pos : pos.relative(hitResult.getDirection());

        // The landing position must actually be inside the simulated region. The placement box
        // litematica matches against can reach slightly beyond it; writing there would put the
        // block outside the projection, where the attack ray trace (which only follows schematic
        // blocks) can never reach it again.
        if (!bridge.region().containsSim(bridge.toSim(landing))) {
            simulatica$reportOutsideBounds(mc);
            cir.setReturnValue(true);
            return;
        }

        ItemStack stack = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack snapshot = stack.copy();

        SimulationMenus.beginInteraction();
        try {
            BlockState state = level.getBlockState(pos);

            if (!mc.player.isShiftKeyDown()) {
                InteractionResult result = state.useItemOn(stack, level, mc.player, InteractionHand.MAIN_HAND, hitResult);
                if (result.consumesAction()) {
                    simulatica$finish(mc, cir);
                    return;
                }
                if (result instanceof InteractionResult.TryEmptyHandInteraction
                        && state.useWithoutItem(level, mc.player, hitResult).consumesAction()) {
                    simulatica$finish(mc, cir);
                    return;
                }
            }

            if (!stack.isEmpty()) {
                UseOnContext context = new SimulatedUseOnContext(level, mc.player, InteractionHand.MAIN_HAND, stack, hitResult);
                if (stack.useOn(context).consumesAction()) {
                    simulatica$finish(mc, cir);
                    return;
                }

                if (stack.use(level, mc.player, InteractionHand.MAIN_HAND).consumesAction()) {
                    simulatica$markUseKeyHeld(mc);
                    simulatica$finish(mc, cir);
                    return;
                }
            }

            simulatica$finish(mc, cir);
        } finally {
            SimulationMenus.endInteraction();
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, snapshot);
        }
    }

    @Unique
    private static void simulatica$finish(Minecraft mc, CallbackInfoReturnable<Boolean> cir) {
        if (mc.player != null) mc.player.swing(mc.player.getUsedItemHand());
        cir.setReturnValue(true);
    }

    /** Bow / crossbow / trident draw first and are released; throwables spawn on use(). */
    @Unique
    private static boolean simulatica$isProjectileItem(Item item) {
        return simulatica$isChargedItem(item)
                || item instanceof SnowballItem
                || item instanceof EggItem
                || item instanceof EnderpearlItem
                || item instanceof ThrowablePotionItem
                || item instanceof ExperienceBottleItem
                || item instanceof WindChargeItem
                || item instanceof FishingRodItem;
    }

    @Unique
    private static boolean simulatica$isChargedItem(Item item) {
        return item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem;
    }

    /**
     * Uses a projectile weapon inside the simulation.
     *
     * <p>{@code SimulationLevel} is a {@code ServerLevel}, so its {@code isClientSide()} is false
     * and an item's {@code use()} takes its server branch -- the projectile is spawned into the
     * simulation level and ticked there, hitting simulated mobs and blocks like a real world
     * projectile. Charged items (bow/crossbow/trident) only start drawing here; their release is
     * replayed against the simulation by {@code ProjectileReleaseMixin}.</p>
     *
     * <p>The held stack is snapshotted and restored: a simulated throw never consumes real items.</p>
     */
    @Unique
    private static boolean simulatica$useProjectile(Minecraft mc, ItemStack stack) {
        SimulationLevel sim = SimulationRaycast.resolveLevel(mc);
        if (sim == null) {
            return false;
        }

        if (simulatica$isChargedItem(stack.getItem())) {
            mc.player.startUsingItem(InteractionHand.MAIN_HAND);
            // Litematica consuming the press cancels the vanilla mouse handler before it marks the
            // use key as held. Without this, handleKeybinds sees the key as released and calls
            // releaseUsingItem one tick later, so the draw/eat animation never advances past its
            // first frame. Mark the key held ourselves; the real release event still clears it.
            simulatica$markUseKeyHeld(mc);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return true;
        }

        ItemStack snapshot = stack.copy();
        try {
            InteractionResult result = stack.use(sim, mc.player, InteractionHand.MAIN_HAND);
            if (result.consumesAction()) {
                mc.player.swing(InteractionHand.MAIN_HAND);
                return true;
            }
            return false;
        } finally {
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, snapshot);
        }
    }

    @Unique
    @Nullable
    private static BlockHitResult simulatica$traceSchematicHit(Minecraft mc) {
        Entity entity = EntityUtils.getCameraEntity();
        if (mc.level == null || entity == null) {
            return null;
        }
        RayTraceUtils.RayTraceWrapper wrapper =
                RayTraceUtils.getGenericTrace(mc.level, entity, SIMULATICA_REACH, true, false, false);

        if (wrapper == null || wrapper.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {
            return null;
        }
        return wrapper.getBlockHitResult();
    }

    @Unique
    @Nullable
    private static SimulaticaEntityHit simulatica$traceSimulationEntity(Minecraft mc, double maxDistance) {
        SimulationRaycast.Hit hit = SimulationRaycast.traceEntity(mc, maxDistance);
        return hit != null ? new SimulaticaEntityHit(hit.entity(), hit.location()) : null;
    }

    /**
     * Routes a right-click into the simulation the way vanilla routes it through
     * {@code Player.interactOn}. The 26.2 interact signature takes the hit location.
     * The held stack is snapshotted and restored, matching the block-use path: simulated
     * interactions never consume real items. Menu-capable mobs (villagers) go through the
     * same SimulationMenus wrapping as block GUIs, and any failure is logged rather than
     * leaked into the client tick.
     */
    @Unique
    private static void simulatica$interactWithEntity(Minecraft mc, SimulaticaEntityHit hit) {
        if (mc.player == null) return;
        if (!(hit.entity().level() instanceof SimulationLevel)) return;

        ItemStack snapshot = mc.player.getItemInHand(InteractionHand.MAIN_HAND).copy();
        SimulationMenus.beginInteraction();
        try {
            hit.entity().interact(mc.player, InteractionHand.MAIN_HAND, hit.location());
            mc.player.swing(InteractionHand.MAIN_HAND);
        } catch (Exception e) {
            Simulatica.LOGGER.error("[Simulatica] Entity interaction failed for {}: {}",
                    hit.entity().getType(), e.getMessage(), e);
        } finally {
            SimulationMenus.endInteraction();
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, snapshot);
        }
    }

    /**
     * Routes a left-click through the vanilla attack pipeline ({@code Player.attack}), so
     * cooldown, critical hits, enchanted damage and knockback all behave as they would
     * against a real mob. 26.2 guards every {@code ServerLevel} cast inside it with an
     * instanceof, so calling it from the client is safe; the damage itself lands because
     * {@code hurtOrSimulate} routes to {@code hurtServer} for entities in a ServerLevel.
     *
     * <p>The held stack is snapshotted and restored: {@code itemAttackInteraction} may wear
     * the weapon, and simulated combat must never consume real items. Enchantment
     * post-attack effects (fire aspect & friends) are skipped by the vanilla method unless
     * the attacker's own level is a ServerLevel, so they are re-applied against the
     * simulation afterwards.</p>
     */
    @Unique
    private static void simulatica$attack(Minecraft mc, Entity target) {
        if (mc.player == null) return;
        if (!(target.level() instanceof SimulationLevel level)) return;

        ItemStack snapshot = mc.player.getItemInHand(InteractionHand.MAIN_HAND).copy();

        // Charge is deliberately left alone: Player.onAttack resets it, which is what gives the
        // swing its vanilla cooldown. PlayerAttackMixin makes the damage independent of it.
        boolean crit = simulatica$canCriticalAttack(mc, target);
        float charge = mc.player.getAttackStrengthScale(0.5F);

        try {
            mc.player.attack(target);
            mc.player.swing(InteractionHand.MAIN_HAND);

            ItemStack weapon = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!weapon.isEmpty()) {
                DamageSource source = weapon.getDamageSource(mc.player);
                EnchantmentHelper.doPostAttackEffectsWithItemSource(level, target, source, weapon);
                applyKnockbackEnchantment(mc, level, target, weapon, source);
                applySweepAttack(mc, level, target, weapon, source, crit, charge);
            }
        } catch (Exception e) {
            Simulatica.LOGGER.error("[Simulatica] Attack on simulated entity failed for {}: {}",
                    target.getType(), e.getMessage(), e);
        } finally {
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, snapshot);
        }
    }

    /** Mirrors {@code Player.canCriticalAttack}, which is private. */
    @Unique
    private static boolean simulatica$canCriticalAttack(Minecraft mc, Entity target) {
        return mc.player.fallDistance > 0.0F
                && !mc.player.onGround()
                && !mc.player.onClimbable()
                && !mc.player.isInWater()
                && !mc.player.isMobilityRestricted()
                && !mc.player.isPassenger()
                && target instanceof LivingEntity
                && !mc.player.isSprinting();
    }

    /**
     * Sweeping, done here rather than by vanilla.
     *
     * <p>{@code Player.doSweepAttack} bails out unless the attacker's own level is a ServerLevel
     * -- a real client player's never is -- and even then it collects targets from that level,
     * which for the client player is the real world. No simulated mob could ever be swept. The
     * same rule is replayed below against the simulation level; 26.2 drives the ratio from the
     * {@code SWEEPING_DAMAGE_RATIO} attribute instead of the old Sweeping enchantment.</p>
     */
    @Unique
    private static void applySweepAttack(Minecraft mc, SimulationLevel level, Entity target, ItemStack weapon,
                                         DamageSource source, boolean crit, float charge) {
        float ratio = (float) mc.player.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO);
        if (ratio <= 0.0F || crit || charge <= 0.9F || !mc.player.onGround() || mc.player.isSprinting()) {
            return;
        }
        if (!weapon.is(ItemTags.SWORDS)) {
            return;
        }

        float base = (float) mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float yaw = mc.player.getYRot() * ((float) Math.PI / 180.0F);
        double pushX = Mth.sin(yaw);
        double pushZ = -Mth.cos(yaw);
        boolean swept = false;

        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class,
                target.getBoundingBox().inflate(1.0, 0.25, 1.0))) {
            if (other == mc.player || other == target || !other.isAlive() || mc.player.isAlliedTo(other)) {
                continue;
            }
            if (other instanceof ArmorStand stand && stand.isMarker()) {
                continue;
            }
            if (target.distanceToSqr(other) >= 9.0) {
                continue;
            }

            float damage = EnchantmentHelper.modifyDamage(level, weapon, other, source, base) * ratio;
            if (other.hurtServer(level, source, damage)) {
                other.knockback(0.4, pushX, pushZ, source, damage);
                swept = true;
            }
        }

        if (swept) {
            ClientLevel client = Minecraft.getInstance().level;
            if (client != null) {
                client.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        SoundEvents.PLAYER_ATTACK_SWEEP, mc.player.getSoundSource(), 1.0F, 1.0F, false);
                client.addParticle(ParticleTypes.SWEEP_ATTACK,
                        target.getX(), target.getY(0.5), target.getZ(), 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * The difference the Knockback enchantment would have made.
     *
     * <p>{@code LivingEntity.getKnockback} only consults {@code EnchantmentHelper} when the
     * attacker's own level is a ServerLevel, which a real player's is not -- so the enchantment
     * is lost on attacks issued from the client. It halves the value it returns, so the
     * difference is applied halved too, as a one-off push away from the player.</p>
     */
    @Unique
    private static void applyKnockbackEnchantment(Minecraft mc, SimulationLevel level, Entity target,
                                                  ItemStack weapon, DamageSource source) {
        float base = (float) mc.player.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
        float extra = EnchantmentHelper.modifyKnockback(level, weapon, target, source, base) - base;
        if (extra <= 0.0F) {
            return;
        }

        Vec3 away = new Vec3(target.getX() - mc.player.getX(), 0.0, target.getZ() - mc.player.getZ());
        if (away.lengthSqr() < 1.0E-4) {
            return;
        }
        Vec3 push = away.normalize().scale(extra / 2.0);
        target.push(push.x, 0.0, push.z);
    }

    @Unique
    private static void simulatica$reportNotSimulated(Minecraft mc, SimulationManager.@Nullable Target target) {
        if (mc.player == null) return;

        Component message;
        if (target == null) {
            message = Component.translatable("simulatica.message.no_simulation");
        } else if (target.placement().getSchematic() == null) {
            message = Component.translatable("simulatica.message.no_schematic", target.placement().getName());
        } else {
            message = Component.translatable("simulatica.message.placement_not_simulated", target.placement().getName());
        }
        mc.player.sendOverlayMessage(message);
    }

    /**
     * Marks the use key as held when a use starts, so {@code handleKeybinds} does not release the
     * item one tick later. Only a hold-type use (one that left the player using an item) is marked;
     * instant throws must not stick the key down.
     */
    @Unique
    private static void simulatica$markUseKeyHeld(Minecraft mc) {
        if (mc.player != null && !mc.player.getUseItem().isEmpty()) {
            mc.options.keyUse.setDown(true);
        }
    }

    @Unique
    private static void simulatica$reportOutsideBounds(Minecraft mc) {
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.translatable("simulatica.message.outside_bounds"));
        }
    }
}
