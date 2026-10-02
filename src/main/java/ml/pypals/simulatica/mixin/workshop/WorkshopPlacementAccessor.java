package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.Map;

@Mixin(value = SchematicPlacement.class, remap = false)
public interface WorkshopPlacementAccessor {
    @Mutable @Accessor("schematic") void simulatica$schematic(LitematicaSchematic schematic);
    @Mutable @Accessor("subRegionCount") void simulatica$regionCount(int count);
    @Accessor("relativeSubRegionPlacements") Map<String, SubRegionPlacement> simulatica$regions();
    @Accessor("materialList") void simulatica$materials(MaterialListBase materials);
    @Invoker("onModified") void simulatica$modified(SchematicPlacementManager manager);
}
