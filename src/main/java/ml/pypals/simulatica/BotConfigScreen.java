package ml.pypals.simulatica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.carpet.BotManager;
import net.minecraft.client.gui.screens.Screen;

/** Settings for one bot of a placement, with navigation back to that placement's page. */
public final class BotConfigScreen extends SimulaticaMenuScreen {
    public BotConfigScreen(SchematicPlacement placement, BotManager.Bot bot, Screen parent) {
        super(placement, bot, parent);
    }
}
