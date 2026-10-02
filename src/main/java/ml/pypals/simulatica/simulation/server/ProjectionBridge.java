package ml.pypals.simulatica.simulation.server;

import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.SchematicEntityLookup;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.carpet.BotManager;
import ml.pypals.simulatica.mixin.SchematicEntityLookupInvoker;
import ml.pypals.simulatica.mixin.simulation.EntityOmnidirectionalAirMoverInvoker;
import ml.pypals.simulatica.mixin.simulation.ServerLevelBlockEventsAccessor;
import ml.pypals.simulatica.mixin.simulation.SimPistonMovingBlockEntityAccessor;
import ml.pypals.simulatica.mixin.WorldSchematicAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;


/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 投影重绘：setBlock 后调用 setBlocksDirty + scheduleChunkRenders(cx, cz, true)（26.2 唯一重绘路径）
 * - 转发声音包（ClientboundSoundPacket / ClientboundSoundEntityPacket / levelEvent / 爆炸）
 * - dropItem 出生点钳入区域；clear() 不再丢弃实体
 * - publishEntities 跨区块换桶：实体位置迁移时先注销再注册，修复击退后渲染消失
 */
public final class ProjectionBridge {

    private final SimulationLevel level;
    private SimulationRegion region;
    private String label;

    @Nullable
    private SimulationViewer viewer;

    private final LongSet dirtyRenderChunks = new LongOpenHashSet();
    private final Set<UUID> published = new HashSet<>();

    /** Where each tracked entity was last seen, and whether it was already dying then. */
    private record SeenEntity(Entity entity, BlockPos pos, boolean dying) {
    }

    private final java.util.Map<UUID, SeenEntity> lastSeen = new java.util.HashMap<>();

    /**
     * The chunk each published entity is currently registered under in the projection's
     * lookup. Litematica's own re-bucketing in {@code addFreshEntity} compares the stored
     * entity's position with the incoming one's -- but both are the very same object here,
     * so the comparison is always true and the entity would stay in its original chunk's
     * bucket forever, vanishing from the per-chunk render enumeration the moment knockback
     * carries it across a chunk border. We therefore re-bucket ourselves: remove and re-add
     * whenever the chunk changes.
     */
    private final java.util.Map<UUID, Long> publishedChunks = new java.util.HashMap<>();
    private final Set<BlockPos> dirtyBlockEntities = new LinkedHashSet<>();
    private final Set<BlockPos> animated = new LinkedHashSet<>();
    private final Set<BlockPos> dirtyBlocks = new LinkedHashSet<>();
    private static final int PROJECTION_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    private static final double ENTITY_TRACKING_MARGIN = 16.0;

    ProjectionBridge(SimulationLevel level, SimulationRegion region, String label) {
        this.level = level;
        this.region = region;
        this.label = label;
    }

    public SimulationRegion region() {
        return this.region;
    }

    public SimulationLevel level() {
        return this.level;
    }

    public BlockPos toSim(BlockPos world) {
        return this.region.toSim(world);
    }

    public String label() {
        return this.label;
    }

    public void setLabel(String label) { this.label = label; }

    void setViewer(@Nullable SimulationViewer viewer) {
        if (this.viewer != null) {
            this.viewer.remove();
        }
        this.viewer = viewer;
    }

    /**
     * Carries the running simulation to a new position instead of rebuilding it there.
     *
     * <p>Blocks are moved in whichever direction keeps a source cell from being overwritten before
     * it is read incase that the old and new footprints overlaps.
     * Ticks and block events are collected across the whole region before any of them are rescheduled.</p>
     */
    void translate(SimulationRegion target) {
        BlockPos delta = target.worldMin().subtract(this.region.worldMin());
        if (delta.equals(BlockPos.ZERO)) {
            this.region = target;
            return;
        }

        SimulationRegion source = this.region;
        List<Entity> entities = entities();

        moveBlocks(source, target, delta);
        moveTicks(source, delta);
        moveBlockEvents(source, delta);
        clearVacated(source, target);

        for (Entity entity : entities) {
            if (entity instanceof EnderDragonPart) continue;
            entity.snapTo(entity.getX() + delta.getX(), entity.getY() + delta.getY(), entity.getZ() + delta.getZ(),
                    entity.getYRot(), entity.getXRot());
        }

        this.region = target;
        this.animated.clear();
        this.dirtyBlockEntities.clear();
        this.dirtyRenderChunks.clear();

        refreshBoundary(source, target);
    }

    /**
     * Tells whatever borders the region that its neighbor changed.
     */
    private void refreshBoundary(SimulationRegion source, SimulationRegion target) {
        Set<BlockPos> shell = new LinkedHashSet<>();
        collectFaceLayer(source, 1, shell);
        collectFaceLayer(target, 1, shell);
        shell.removeIf(pos -> source.containsSim(pos) || target.containsSim(pos));

        collectFaceLayer(target, 0, shell);

        for (BlockPos pos : shell) {
            BlockState state = this.level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }

            BlockState updated = Block.updateFromNeighbourShapes(state, this.level, pos);
            if (updated != state) {
                this.level.setBlock(pos, updated, Block.UPDATE_ALL);
            } else {
                this.level.neighborChanged(pos, state.getBlock(), null);
            }
        }
    }

    private static void collectFaceLayer(SimulationRegion region, int outset, Set<BlockPos> into) {
        BlockPos min = region.toSim(region.worldMin());
        BlockPos max = region.toSim(region.worldMax());

        for (Direction face : Direction.values()) {
            int lo = outset;
            int hi = outset - 1;

            int x0 = face == Direction.EAST ? max.getX() + lo : (face == Direction.WEST ? min.getX() - lo : min.getX());
            int x1 = face == Direction.EAST ? max.getX() + lo : (face == Direction.WEST ? min.getX() - lo : max.getX());
            int y0 = face == Direction.UP ? max.getY() + lo : (face == Direction.DOWN ? min.getY() - lo : min.getY());
            int y1 = face == Direction.UP ? max.getY() + lo : (face == Direction.DOWN ? min.getY() - lo : max.getY());
            int z0 = face == Direction.SOUTH ? max.getZ() + lo : (face == Direction.NORTH ? min.getZ() - lo : min.getZ());
            int z1 = face == Direction.SOUTH ? max.getZ() + lo : (face == Direction.NORTH ? min.getZ() - lo : max.getZ());

            if (hi < 0 && (x1 < x0 || y1 < y0 || z1 < z0)) {
                continue;
            }

            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        into.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
    }

    private void moveBlocks(SimulationRegion source, SimulationRegion target, BlockPos delta) {
        BlockPos min = source.worldMin();
        BlockPos max = source.worldMax();
        int spanX = max.getX() - min.getX() + 1;
        int spanY = max.getY() - min.getY() + 1;
        int spanZ = max.getZ() - min.getZ() + 1;

        for (int i = 0; i < spanX; i++) {
            int x = delta.getX() > 0 ? max.getX() - i : min.getX() + i;
            for (int j = 0; j < spanY; j++) {
                int y = delta.getY() > 0 ? max.getY() - j : min.getY() + j;
                for (int k = 0; k < spanZ; k++) {
                    int z = delta.getZ() > 0 ? max.getZ() - k : min.getZ() + k;

                    BlockPos world = new BlockPos(x, y, z);
                    BlockPos from = source.toSim(world);
                    BlockPos to = target.toSim(world.offset(delta));

                    BlockState state = this.level.getBlockState(from);
                    BlockEntity blockEntity = state.hasBlockEntity() ? this.level.getBlockEntity(from) : null;
                    CompoundTag tag = blockEntity != null
                            ? blockEntity.saveWithFullMetadata(this.level.registryAccess()) : null;

                    this.level.setBlock(to, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                    if (tag != null) {
                        BlockEntity moved = BlockEntity.loadStatic(to, state, tag, this.level.registryAccess());
                        if (moved != null) {
                            this.level.setBlockEntity(moved);
                        }
                    }
                }
            }
        }
    }

    private void clearVacated(SimulationRegion source, SimulationRegion target) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos world : BlockPos.betweenClosed(source.worldMin(), source.worldMax())) {
            BlockPos sim = source.toSim(world);
            if (target.containsSim(sim) || this.level.getBlockState(sim).isAir()) {
                continue;
            }
            this.level.setBlock(sim, air, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        }
    }

    private void moveTicks(SimulationRegion source, BlockPos delta) {
        List<ScheduledTick<Block>> blocks = new ArrayList<>();
        List<ScheduledTick<Fluid>> fluids = new ArrayList<>();

        ChunkPos min = source.simChunkMin();
        ChunkPos max = source.simChunkMax();
        for (int cx = min.x(); cx <= max.x(); cx++) {
            for (int cz = min.z(); cz <= max.z(); cz++) {
                LevelChunk chunk = this.level.getChunk(cx, cz);

                @SuppressWarnings("unchecked")
                LevelChunkTicks<Block> blockTicks = (LevelChunkTicks<Block>) chunk.getBlockTicks();
                blockTicks.getAll().filter(tick -> source.containsSim(tick.pos())).forEach(blocks::add);
                blockTicks.removeIf(tick -> source.containsSim(tick.pos()));

                @SuppressWarnings("unchecked")
                LevelChunkTicks<Fluid> fluidTicks = (LevelChunkTicks<Fluid>) chunk.getFluidTicks();
                fluidTicks.getAll().filter(tick -> source.containsSim(tick.pos())).forEach(fluids::add);
                fluidTicks.removeIf(tick -> source.containsSim(tick.pos()));
            }
        }

        for (ScheduledTick<Block> tick : blocks) {
            this.level.getBlockTicks().schedule(new ScheduledTick<>(
                    tick.type(), tick.pos().offset(delta), tick.triggerTick(), tick.priority(), tick.subTickOrder()));
        }
        for (ScheduledTick<Fluid> tick : fluids) {
            this.level.getFluidTicks().schedule(new ScheduledTick<>(
                    tick.type(), tick.pos().offset(delta), tick.triggerTick(), tick.priority(), tick.subTickOrder()));
        }
    }

    private void moveBlockEvents(SimulationRegion source, BlockPos delta) {
        var queue = ((ServerLevelBlockEventsAccessor) this.level).simulatica$blockEvents();
        if (queue.isEmpty()) {
            return;
        }

        List<BlockEventData> moved = new ArrayList<>();
        queue.removeIf(event -> {
            if (!source.containsSim(event.pos())) {
                return false;
            }
            moved.add(new BlockEventData(event.pos().offset(delta), event.block(), event.paramA(), event.paramB()));
            return true;
        });
        queue.addAll(moved);
    }

    /**
     * Writes the whole region back into the projection.
     */
    public void pushToProjection() {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return;
        }

        for (BlockPos world : BlockPos.betweenClosed(this.region.worldMin(), this.region.worldMax())) {
            int cx = world.getX() >> 4;
            int cz = world.getZ() >> 4;
            if (!projection.hasChunk(cx, cz)) {
                continue;
            }

            BlockPos sim = this.region.toSim(world);
            BlockState state = this.level.getBlockState(sim);
            // 26.2 litematica: setBlock ignores flags and never schedules a re-render;
            // setBlocksDirty is the only path that does (immediate=true)
            BlockState previous = projection.getBlockState(world);
            projection.setBlock(world, state, PROJECTION_FLAGS);
            projection.setBlocksDirty(world, previous, state);
            this.dirtyRenderChunks.add(ChunkPos.pack(cx, cz));
            if (state.hasBlockEntity()) {
                this.dirtyBlockEntities.add(sim);
            }
        }
    }

    /**
     * Applies a packet the simulation server produced for this region.
     */
    void onPacket(Packet<?> packet) {
        ClientLevel client = Minecraft.getInstance().level;
        if (client == null) {
            return;
        }

        if (packet instanceof ClientboundLevelParticlesPacket particles) {
            spawnParticles(client, particles);
        } else if (packet instanceof ClientboundLevelEventPacket event) {
            if (!this.level.allowLevelEvent(event.getType())) return;
            client.levelEvent(null, event.getType(), event.getPos(), event.getData());
        } else if (packet instanceof ClientboundSoundPacket sound) {
            if (!this.level.allowSound(sound.getSound().value())) return;
            // Regions are mapped with a zero offset, so sim coordinates are world coordinates.
            client.playLocalSound(sound.getX(), sound.getY(), sound.getZ(), sound.getSound().value(),
                    sound.getSource(), sound.getVolume(), sound.getPitch(), false);
        } else if (packet instanceof ClientboundSoundEntityPacket entitySound) {
            if (!this.level.allowSound(entitySound.getSound().value())) return;
            Entity entity = this.level.getEntity(entitySound.getId());
            if (entity != null) {
                client.playLocalSound(entity.getX(), entity.getY(), entity.getZ(),
                        entitySound.getSound().value(), entitySound.getSource(),
                        entitySound.getVolume(), entitySound.getPitch(), false);
            }
        } else if (packet instanceof ClientboundExplodePacket explode) {
            client.addParticle(explode.explosionParticle(), true, false,
                    explode.center().x, explode.center().y, explode.center().z, 0.0, 0.0, 0.0);
        }
    }

    private static void spawnParticles(ClientLevel client, ClientboundLevelParticlesPacket packet) {
        if (packet.getCount() == 0) {
            // Count zero means "one particle moving at maxSpeed along the offsets", not "none".
            client.addParticle(packet.getParticle(), packet.isOverrideLimiter(), false,
                    packet.getX(), packet.getY(), packet.getZ(),
                    packet.getXDist() * packet.getMaxSpeed(),
                    packet.getYDist() * packet.getMaxSpeed(),
                    packet.getZDist() * packet.getMaxSpeed());
            return;
        }

        RandomSource random = client.getRandom();
        for (int i = 0; i < packet.getCount(); i++) {
            client.addParticle(packet.getParticle(), packet.isOverrideLimiter(), false,
                    packet.getX() + random.nextGaussian() * packet.getXDist(),
                    packet.getY() + random.nextGaussian() * packet.getYDist(),
                    packet.getZ() + random.nextGaussian() * packet.getZDist(),
                    random.nextGaussian() * packet.getMaxSpeed(),
                    random.nextGaussian() * packet.getMaxSpeed(),
                    random.nextGaussian() * packet.getMaxSpeed());
        }
    }

    /**
     * Copies the region's projection contents into the simulation.
     *
     * @param preserved UUIDs that must not be duplicated: live leftovers retaken by this
     *                  bridge and entities the leftover store holds fresher states for.
     *                  Every UUID copied in is added to the set, so it doubles as the
     *                  "already present" list for the leftover restore that follows.
     */
    public int copyIn(Set<UUID> preserved) {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return 0;
        }

        int copied = 0;
        for (BlockPos world : BlockPos.betweenClosed(this.region.worldMin(), this.region.worldMax())) {
            BlockState state = projection.getBlockState(world);
            if (state.isAir()) {
                continue;
            }

            BlockPos sim = this.region.toSim(world);
            this.level.setBlock(sim, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            copyBlockEntity(projection, world, sim, state);
            copied++;
        }

        // 铺方块时 LevelChunk.setBlockState 会对光照属性变化的方块同步 checkBlock（26.2 无需
        // UPDATE_LIGHT flag），露天 skylight/火把 blockLight 的取值已正确，刷怪判定读得到。手动
        // runLightUpdates 反而会在渲染线程撞上 ThreadedLevelLightEngine 的线程守卫。

        copyEntitiesIn(projection, preserved);
        return copied;
    }

    private void copyEntitiesIn(WorldSchematic projection, Set<UUID> preserved) {
        for (Entity source : projection.getEntities((Entity) null, this.region.simBounds(), e -> true)) {
            if (preserved.contains(source.getUUID())) {
                continue;
            }
            try {
                TagValueOutput output =
                        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, this.level.registryAccess());
                if (!source.save(output)) {
                    continue;
                }

                Entity copy = EntityType.loadEntityRecursive(
                        output.buildResult(), this.level,
                        new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.LOAD, false),
                        entity -> entity);
                if (copy != null) {
                    this.level.addFreshEntityWithPassengers(copy);
                    preserved.add(copy.getUUID());
                }
            } catch (Exception e) {
                Simulatica.LOGGER.error("[Simulatica] Failed to copy in entity {}: {}",
                        source.getType(), e.getMessage(), e);
            }
        }
    }

    /**
     * Throws an item into the simulation the way the player would throw it in the world.
     *
     * <p>Reproduces {@code LivingEntity.createItemStackToDrop} rather than calling
     * {@code Player.drop}, which would build the entity in {@code player.level()} -- the client
     * world, where the simulation cannot see it. The spawn point is clamped just inside the
     * region: the player throws from outside, and the boundary walls would otherwise catch the
     * item before it could enter.</p>
     */
    public void dropItem(Player thrower, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        AABB bounds = this.region.simBounds();
        double x = clampSpawn(thrower.getX(), bounds.minX, bounds.maxX);
        double y = clampSpawn(thrower.getEyeY() - 0.3, bounds.minY, bounds.maxY);
        double z = clampSpawn(thrower.getZ(), bounds.minZ, bounds.maxZ);
        ItemEntity item = new ItemEntity(this.level, x, y, z, stack);
        item.setPickUpDelay(40);

        RandomSource random = this.level.getRandom();
        float pitchSin = Mth.sin(thrower.getXRot() * (float) (Math.PI / 180.0));
        float pitchCos = Mth.cos(thrower.getXRot() * (float) (Math.PI / 180.0));
        float yawSin = Mth.sin(thrower.getYRot() * (float) (Math.PI / 180.0));
        float yawCos = Mth.cos(thrower.getYRot() * (float) (Math.PI / 180.0));
        float spread = random.nextFloat() * (float) (Math.PI * 2);
        float scatter = 0.02F * random.nextFloat();

        item.setDeltaMovement(
                -yawSin * pitchCos * 0.3F + Math.cos(spread) * scatter,
                -pitchSin * 0.3F + 0.1F + (random.nextFloat() - random.nextFloat()) * 0.1F,
                yawCos * pitchCos * 0.3F + Math.sin(spread) * scatter);

        this.level.addFreshEntity(item);
    }

    /** Half a block inside the region, or the region's center line when it is too thin. */
    private static double clampSpawn(double value, double min, double max) {
        double lo = min + 0.5;
        double hi = max - 0.5;
        if (lo > hi) {
            return (min + max) / 2.0;
        }
        return Mth.clamp(value, lo, hi);
    }

    private void copyBlockEntity(WorldSchematic projection, BlockPos world, BlockPos sim, BlockState state) {
        if (!state.hasBlockEntity()) {
            return;
        }

        BlockEntity source = projection.getBlockEntity(world);
        if (source == null) {
            return;
        }

        CompoundTag tag = source.saveWithFullMetadata(this.level.registryAccess());
        BlockEntity copy = BlockEntity.loadStatic(sim, state, tag, this.level.registryAccess());
        if (copy != null) {
            this.level.setBlockEntity(copy);
        }
    }

    void onBlockChanged(BlockPos sim) {
        this.dirtyBlocks.add(sim.immutable());
    }

    private void flushBlocks() {
        for (BlockPos sim : this.dirtyBlocks) mirrorBlock(sim);
        this.dirtyBlocks.clear();
    }

    private void mirrorBlock(BlockPos sim) {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return;
        }

        BlockPos world = this.region.toWorld(sim);
        int cx = world.getX() >> 4;
        int cz = world.getZ() >> 4;
        if (!projection.hasChunk(cx, cz)) {
            return;
        }

        BlockState state = this.level.getBlockState(sim);
        // 26.2 litematica: setBlock ignores flags and never schedules a re-render;
        // setBlocksDirty is the only path that does (immediate=true)
        BlockState previous = projection.getBlockState(world);
        projection.setBlock(world, state, PROJECTION_FLAGS);
        projection.setBlocksDirty(world, previous, state);
        this.dirtyRenderChunks.add(ChunkPos.pack(cx, cz));

        if (state.hasBlockEntity()) {
            this.dirtyBlockEntities.add(sim);
        } else if (this.animated.remove(world)) {
            projection.removeBlockEntity(world);
        }
    }

    void onBlockEntityChanged(BlockPos sim) {
        this.dirtyBlockEntities.add(sim);
    }

    private void flushBlockEntities() {
        if (this.dirtyBlockEntities.isEmpty()) {
            return;
        }

        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection != null) {
            for (BlockPos sim : this.dirtyBlockEntities) {
                mirrorBlockEntity(projection, sim);
            }
        }
        this.dirtyBlockEntities.clear();
    }

    private void mirrorBlockEntity(WorldSchematic projection, BlockPos sim) {
        BlockEntity source = this.level.getBlockEntity(sim);
        if (source == null) {
            return;
        }

        BlockPos world = this.region.toWorld(sim);
        int cx = world.getX() >> 4;
        int cz = world.getZ() >> 4;
        if (!projection.hasChunk(cx, cz) || !(projection.getChunk(cx, cz) instanceof ChunkSchematic chunk)) {
            return;
        }

        BlockEntity target = chunk.getBlockEntity(world, LevelChunk.EntityCreationType.CHECK);
        if (target == null) {
            target = chunk.createBlockEntity(world);
            if (target == null) {
                return;
            }
            chunk.setBlockEntity(target);
        }
        if (target == source || target.getType() != source.getType()) {
            return;
        }

        try {
            CompoundTag nbt = source.saveWithFullMetadata(this.level.registryAccess());
            target.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, this.level.registryAccess(), nbt));
            restoreInterpolation(source, target);
            this.animated.add(world);
        } catch (Exception e) {
            Simulatica.LOGGER.error("[Simulatica] Failed to mirror block entity at {}: {}", world, e.getMessage(), e);
        }
    }

    private static void restoreInterpolation(BlockEntity source, BlockEntity target) {
        if (source instanceof PistonMovingBlockEntity && target instanceof PistonMovingBlockEntity) {
            ((SimPistonMovingBlockEntityAccessor) target).sim$setProgressO(
                    ((SimPistonMovingBlockEntityAccessor) source).sim$getProgressO());
            ((SimPistonMovingBlockEntityAccessor) target).sim$setProgress(
                    ((SimPistonMovingBlockEntityAccessor) source).sim$getProgress());
        }
    }

    private void tickProjectionBlockEntities() {
        if (this.animated.isEmpty() || !this.level.tickRateManager().runsNormally()) {
            return;
        }

        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return;
        }

        for (BlockPos world : this.animated) {
            if (!projection.hasChunk(world.getX() >> 4, world.getZ() >> 4)) {
                continue;
            }

            BlockEntity target = projection.getBlockEntity(world);
            if (target == null || target.isRemoved()) {
                continue;
            }

            BlockState state = target.getBlockState();
            // Piston progress comes from the simulation; a second tick would advance it twice.
            if (!(state.getBlock() instanceof EntityBlock entityBlock) || target instanceof PistonMovingBlockEntity) {
                continue;
            }

            @SuppressWarnings("unchecked")
            BlockEntityTicker<BlockEntity> ticker =
                    (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(projection, state, target.getType());
            if (ticker == null) {
                continue;
            }

            try {
                ticker.tick(projection, world, state, target);
            } catch (Exception e) {
                Simulatica.LOGGER.error("[Simulatica] Exception animating projection block entity at {}: {}",
                        world, e.getMessage(), e);
            }
        }
    }

    void syncToProjection() {
        flushBlocks();
        for (BlockPos world : this.animated) {
            BlockPos sim = this.region.toSim(world);
            if (this.level.getBlockEntity(sim) instanceof PistonMovingBlockEntity) {
                this.dirtyBlockEntities.add(sim);
            }
        }
        flushBlockEntities();
        tickProjectionBlockEntities();
        publishEntities();
        flushRenders();
    }

        public List<Entity> entities() {
        return this.level.getEntities(
                (Entity) null, this.region.simBounds().inflate(ENTITY_TRACKING_MARGIN),
                e -> !e.isRemoved() && !SimulationViewer.isViewer(e));
    }

    void publishEntities() {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return;
        }

        Set<UUID> live = null;
        for (Entity entity : entities()) {
            // Level.getEntities appends the ender dragon's parts, which a real client never holds --
            // the dragon's own renderer draws them. Handing one to the projection gets it looked up
            // as an ENDER_DRAGON and cast to one.
            if (entity instanceof EnderDragonPart) {
                continue;
            }

            animate(entity);

            SchematicEntityLookup<Entity> lookup = ((WorldSchematicAccessor) projection).sim$getEntityLookup();
            UUID uuid = entity.getUUID();
            boolean registered = lookup != null && lookup.contains(uuid);

            long chunkKey = ChunkPos.pack(
                    net.minecraft.util.Mth.floor(entity.getX() / 16.0),
                    net.minecraft.util.Mth.floor(entity.getZ() / 16.0));
            Long previousChunk = this.publishedChunks.get(uuid);
            boolean chunkChanged = previousChunk == null || previousChunk.longValue() != chunkKey;

            // Only touch the projection when something actually changed. Litematica's
            // addFreshEntitySafe re-rolls the entity's UUID every single call when the schematic
            // de-duplication option is off (it branches to setUUID(randomUUID()) as soon as the
            // UUID is already known), which silently destroys entity identity: every tick would
            // produce a "new" entity, the tracking set would churn, and UUID-keyed state such as
            // the leftover store would stop matching.
            if (!registered || chunkChanged) {
                if (registered) {
                    unpublish(projection, uuid);
                }
                this.publishedChunks.put(uuid, chunkKey);
                projection.addFreshEntity(entity);
            }

            if (live == null) {
                live = new HashSet<>();
            }
            live.add(uuid);
            this.lastSeen.put(uuid, new SeenEntity(entity, entity.blockPosition(),
                    entity instanceof LivingEntity living && living.isDeadOrDying()));
        }

        for (UUID uuid : this.published) {
            if (live == null || !live.contains(uuid)) {
                // Dying is expected; vanishing while alive is not -- log where the entity was
                // last seen so a boundary escape can be pinpointed from the log.
                SeenEntity seen = this.lastSeen.remove(uuid);
                if (seen == null || (!seen.dying() && !seen.entity().isRemoved())) {
                    Simulatica.LOGGER.warn("[Simulatica] Entity {} left '{}' while alive, last seen at {}",
                            uuid, this.label, seen != null ? seen.pos() : "unknown");
                }
                this.publishedChunks.remove(uuid);
                unpublish(projection, uuid);
            }
        }

        this.published.clear();
        if (live != null) {
            this.published.addAll(live);
        }
    }

    /**
     * Limb movement, which {@code LivingEntity} only computes when {@code level().isClientSide()}.
     *
     * <p>On a real server the client derives it from the positions it interpolates between. These
     * entities live in a {@code ServerLevel}, so nothing ever advances their walk animation and they
     * slide around with their legs still.</p>
     */
    private static void animate(Entity entity) {
        if (entity instanceof LivingEntity living) {
            living.calculateEntityAnimation(
                    ((EntityOmnidirectionalAirMoverInvoker) living).simulatica$omnidirectionalAirMover());
        }
        // 假人（ServerPlayer）的渲染镜像（ClientMannequin）跟随其位置/朝向，让皮肤正确显示
        if (entity instanceof ServerPlayer player) {
            BotManager.syncAvatar(player);
        }
    }

    private static void unpublish(WorldSchematic projection, UUID uuid) {
        SchematicEntityLookup<Entity> lookup = ((WorldSchematicAccessor) projection).sim$getEntityLookup();
        if (lookup != null) {
            ((SchematicEntityLookupInvoker) lookup).sim$remove(uuid, projection);
        }
    }

    private void flushRenders() {
        if (this.dirtyRenderChunks.isEmpty()) {
            return;
        }

        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection != null) {
            // immediate=true: rebuild right away instead of queuing behind the renderer
            this.dirtyRenderChunks.forEach(
                    (long key) -> projection.scheduleChunkRenders(ChunkPos.getX(key), ChunkPos.getZ(key), true));
        }
        this.dirtyRenderChunks.clear();
    }

    void clear() {
        // Entities are deliberately NOT discarded or unpublished here: summoned creatures are
        // part of the simulated end state and stay visible in the projection, the same way the
        // simulated blocks do. SimulationManager takes over their republishing from now on.
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (BlockPos world : BlockPos.betweenClosed(this.region.worldMin(), this.region.worldMax())) {
            BlockPos sim = this.region.toSim(world);
            if (!this.level.getBlockState(sim).isAir()) {
                this.level.setBlock(sim, air, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            }
        }
        refreshBoundary(this.region, this.region);

        this.published.clear();
        this.publishedChunks.clear();
        this.dirtyBlockEntities.clear();
        this.animated.clear();
        Simulatica.LOGGER.info("[Simulatica] Detached '{}' from the simulation", this.label);
    }

    /** Removes one entity from the projection's lookup, wherever it came from. */
    public static void unpublishEntity(WorldSchematic projection, Entity entity) {
        SchematicEntityLookup<Entity> lookup = ((WorldSchematicAccessor) projection).sim$getEntityLookup();
        if (lookup != null) {
            ((SchematicEntityLookupInvoker) lookup).sim$remove(entity.getUUID(), projection);
        }
    }

    @Nullable
    static ProjectionBridge covering(Iterable<ProjectionBridge> bridges, SimulationLevel level, BlockPos sim) {
        for (ProjectionBridge bridge : bridges) {
            if (bridge.level == level && bridge.region.containsSim(sim)) {
                return bridge;
            }
        }
        return null;
    }
}
