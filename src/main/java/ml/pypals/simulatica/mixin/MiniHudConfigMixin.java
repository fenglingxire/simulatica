package ml.pypals.simulatica.mixin;

import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import ml.pypals.simulatica.config.SimulaticaConfigs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Pseudo
@Mixin(targets = "fi.dy.masa.minihud.gui.GuiConfigs", remap = false)
public abstract class MiniHudConfigMixin {
    @Inject(method = {"getConfigs", "getAllConfigs"}, at = @At("RETURN"), cancellable = true)
    private void simulatica$addTpsToggle(CallbackInfoReturnable<List<ConfigOptionWrapper>> ci) {
        List<ConfigOptionWrapper> original = ci.getReturnValue();
        if (original.stream().anyMatch(option -> option.getConfig() == SimulaticaConfigs.TPS_HUD)
                || original.stream().noneMatch(option -> option.getConfig() != null
                        && option.getConfig().getName().equals("infoServerTPS"))) return;
        List<ConfigOptionWrapper> configs = new ArrayList<>(original);
        configs.add(1, new ConfigOptionWrapper(SimulaticaConfigs.TPS_HUD));
        configs.add(2, new ConfigOptionWrapper(SimulaticaConfigs.TPS_MULTILINE));
        ci.setReturnValue(configs);
    }
}
