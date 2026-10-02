package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.litematica.render.LitematicaRenderer;
import ml.pypals.simulatica.workshop.WorkshopManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LitematicaRenderer.class, remap = false)
public abstract class WorkshopSchematicRenderingMixin {
    @Shadow private boolean renderPiecewiseSchematic;
    @Shadow private boolean renderPiecewiseBlocks;
    @Shadow private boolean renderPiecewiseEntities;
    @Shadow private boolean renderPiecewiseTileEntities;
    @Shadow private boolean renderEntityDebugHitboxes;

    @Inject(method = {"updateConfigState", "piecewisePrepare"}, at = @At("HEAD"), cancellable = true)
    private void workshop$materialized(CallbackInfo ci) {
        if (!WorkshopManager.isLocalWorkshop()) return;
        renderPiecewiseSchematic = renderPiecewiseBlocks = renderPiecewiseEntities =
                renderPiecewiseTileEntities = renderEntityDebugHitboxes = false;
        ci.cancel();
    }

    @Inject(method = "renderEntityDebugHitboxes", at = @At("HEAD"), cancellable = true)
    private void workshop$hideOriginalHitboxes(CallbackInfo ci) {
        if (WorkshopManager.isLocalWorkshop()) ci.cancel();
    }
}
