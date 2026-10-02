package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import ml.pypals.simulatica.workshop.WorkshopSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SchematicPlacementManager.class)
public class WorkshopPlacementLifecycleMixin {
    @Inject(method = {"onClientChunkLoad", "onClientChunkUnload", "onWorldJoin"}, at = @At("HEAD"), cancellable = true)
    private void workshop$keepOriginalWorld(CallbackInfo ci) {
        if (WorkshopSession.isActive()) ci.cancel();
    }
}
