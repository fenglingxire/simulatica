package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.data.DataManager;
import ml.pypals.simulatica.SimulaticaClient;
import ml.pypals.simulatica.simulation.SimulationRaycast;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 拦截客户端释放蓄力投射物（弓/弩/三叉戟），把释放重放到模拟世界，使投射物在模拟中生成并飞行
 */
/**
 * Releases charged projectiles into the simulation level.
 *
 * <p>{@code LivingEntity.releaseUsingItem()} replays the release against {@code this.level()},
 * which for a real client player is the real world -- the arrow would fly there. This mixin
 * intercepts the client-side release entry point and, when the player is aiming into a running
 * simulation while holding a bow/crossbow/trident, replays it against that {@code SimulationLevel}
 * instead. Because a {@code SimulationLevel} is a {@code ServerLevel}, the item takes its server
 * branch and spawns the projectile inside the simulation, where it ticks, flies and hits
 * simulated mobs and blocks. The vanilla release is then cancelled so the real server does not
 * also spawn a duplicate.</p>
 */
@Mixin(MultiPlayerGameMode.class)
public class ProjectileReleaseMixin {

    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
    private void simulatica$releaseIntoSimulation(Player player, CallbackInfo ci) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) return;
        if (!(player instanceof LocalPlayer localPlayer)) {
            return;
        }
        if (DataManager.getToolMode() != SimulaticaClient.SIMULATE) {
            return;
        }

        ItemStack stack = localPlayer.getItemInHand(localPlayer.getUsedItemHand());
        if (!(stack.getItem() instanceof BowItem)
                && !(stack.getItem() instanceof CrossbowItem)
                && !(stack.getItem() instanceof TridentItem)) {
            return;
        }

        SimulationLevel sim = SimulationRaycast.resolveLevel(Minecraft.getInstance());
        if (sim == null) {
            return;
        }

        stack.releaseUsing(sim, localPlayer, localPlayer.getUseItemRemainingTicks());
        localPlayer.stopUsingItem();
        ci.cancel();
    }
}
