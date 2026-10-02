package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = SchematicPlacementManager.class, remap = false)
public interface WorkshopPlacementManagerInvoker {
    @Invoker("onPrePlacementChange") void simulatica$beforeChange(SchematicPlacement placement);
}
