package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopEdit;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class WorkshopPlatformCommandMixin {
    @Inject(method = {"performCommand", "performPrefixedCommand"}, at = @At("HEAD"))
    private void simulatica$begin(CallbackInfo ci) { WorkshopEdit.beginPlayerMutation(); }
    @Inject(method = {"performCommand", "performPrefixedCommand"}, at = @At("RETURN"))
    private void simulatica$end(CallbackInfo ci) { WorkshopEdit.endPlayerMutation(); }
}
