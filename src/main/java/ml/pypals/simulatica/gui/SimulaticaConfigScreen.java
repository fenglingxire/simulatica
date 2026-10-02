package ml.pypals.simulatica.gui;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.SimulaticaMenuScreen;

import java.util.List;

/** MaLiLib switcher entry forwarding to the original vanilla-screen control panel. */
public final class SimulaticaConfigScreen extends GuiConfigsBase {
    public SimulaticaConfigScreen() {
        super(10, 60, Simulatica.MOD_ID, null, "Simulatica");
    }

    public static void register() {
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(Simulatica.MOD_ID, "Simulatica", SimulaticaConfigScreen::new));
    }

    @Override public void initGui() {
        super.initGui();
        this.mc.schedule(() -> {
            if (this.mc.gui.screen() == this) this.mc.gui.setScreen(new SimulaticaMenuScreen());
        });
    }

    @Override public List<ConfigOptionWrapper> getConfigs() { return List.of(); }
}
