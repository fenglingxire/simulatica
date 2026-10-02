package ml.pypals.simulatica.gui;

import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.SimulaticaMenuScreen;

/** The switcher, command, and Litematica menu all open the same control panel. */
public final class SimulaticaConfigScreen {
    private SimulaticaConfigScreen() {}

    public static void register() {
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(Simulatica.MOD_ID, "Simulatica", SimulaticaMenuScreen::new));
    }
}
