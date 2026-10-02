package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.litematica.event.RenderHandler;
import ml.pypals.simulatica.workshop.WorkshopManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderHandler.class, remap = false)
public abstract class WorkshopSchematicOverlayMixin {
    @Inject(method = {"onRenderWorldLast", "onExtractGuiOverlayPost"}, at = @At("HEAD"), cancellable = true)
    private void workshop$hideOriginalOverlay(CallbackInfo ci) {
        if (WorkshopManager.isLocalWorkshop()) ci.cancel();
    }
}
