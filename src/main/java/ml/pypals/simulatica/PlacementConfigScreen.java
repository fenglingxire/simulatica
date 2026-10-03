package ml.pypals.simulatica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.client.gui.screens.Screen;

/** Settings for one loaded placement, with navigation back to the placement list. */
public final class PlacementConfigScreen extends SimulaticaMenuScreen {
    public PlacementConfigScreen(SchematicPlacement placement, Screen parent) {
        super(placement, parent);
    }
}
