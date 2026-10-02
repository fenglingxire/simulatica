package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.data.DataManager;
import ml.pypals.simulatica.SimulaticaClient;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.ProjectionBridge;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(LocalPlayer.class)
public abstract class LocalPlayerDropMixin {

    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void simulatica$dropIntoSimulation(boolean dropAll, CallbackInfoReturnable<Boolean> cir) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) return;
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (DataManager.getToolMode() != SimulaticaClient.SIMULATE) return;

        ProjectionBridge bridge = simulatica$bridgeAround(self);
        if (bridge == null) return;

        ItemStack selected = self.getInventory().getSelectedItem();
        if (selected.isEmpty()) {
            cir.setReturnValue(false);
            return;
        }

        bridge.dropItem(self, dropAll ? selected.copy() : selected.copyWithCount(1));
        cir.setReturnValue(true);
    }

    @Unique
    @Nullable
    private static ProjectionBridge simulatica$bridgeAround(LocalPlayer player) {
        ProjectionBridge bridge = simulatica$bridgeAt(player.blockPosition());
        return bridge != null ? bridge : simulatica$bridgeAt(BlockPos.containing(player.getEyePosition()));
    }

    @Unique
    @Nullable
    private static ProjectionBridge simulatica$bridgeAt(BlockPos pos) {
        SimulationManager.Target target = SimulationManager.getInstance().findTarget(pos);
        return target != null ? target.bridge() : null;
    }
}
