package ml.pypals.simulatica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.client.gui.screens.Screen;

/** Placement-scoped bot management; global simulation settings stay in the main panel. */
public final class PlacementConfigScreen extends SimulaticaMenuScreen {
    public PlacementConfigScreen(SchematicPlacement placement, Screen parent) {
        super(placement, parent);
    }
}
