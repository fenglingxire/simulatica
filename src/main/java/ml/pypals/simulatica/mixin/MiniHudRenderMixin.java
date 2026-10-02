package ml.pypals.simulatica.mixin;

import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.config.SimulaticaConfigs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "fi.dy.masa.minihud.event.RenderHandler", remap = false)
public abstract class MiniHudRenderMixin {
    @Shadow public abstract void addLine(String text);

    // Add before MiniHUD sorts and copies its rebuilt text, retaining its layout and visibility controls.
    @Inject(method = "updateLines", at = @At(value = "FIELD",
            target = "Lfi/dy/masa/minihud/config/Configs$Generic;SORT_LINES_BY_LENGTH:Lfi/dy/masa/malilib/config/options/ConfigBoolean;"))
    private void simulatica$addTpsLines(CallbackInfo ci) {
        if (SimulaticaConfigs.TPS_HUD.getBooleanValue()) {
            SimulationManager.getInstance().describeTpsHud().forEach(this::addLine);
        }
    }
}
