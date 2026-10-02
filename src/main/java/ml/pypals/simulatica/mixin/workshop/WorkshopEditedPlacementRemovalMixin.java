package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import ml.pypals.simulatica.workshop.EditedPlacementCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SchematicPlacementManager.class, remap = false)
public abstract class WorkshopEditedPlacementRemovalMixin {
    @Inject(method = "removeSchematicPlacement(Lfi/dy/masa/litematica/schematic/placement/SchematicPlacement;Z)Z", at = @At("RETURN"))
    private void simulatica$forget(SchematicPlacement placement, boolean update, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) EditedPlacementCache.invalidate(placement);
    }
}
