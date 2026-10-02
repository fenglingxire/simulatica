package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import ml.pypals.simulatica.workshop.EditedPlacementCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class WorkshopEditedSchematicReloadMixin {
    @Inject(method = "readFromFile()Z", at = @At("RETURN"))
    private void simulatica$forget(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) EditedPlacementCache.invalidate((LitematicaSchematic) (Object) this);
    }
}
