package ml.pypals.simulatica.config;

import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import java.nio.file.Path;

/** Both config screens use these same options; Simulatica owns their saved values. */
public final class SimulaticaConfigs {
    public static final ConfigBoolean TPS_HUD = new ConfigBoolean("simulaticaTps", true).apply("simulatica.config");
    public static final ConfigBoolean TPS_MULTILINE = new ConfigBoolean("simulaticaTpsMultiline", false).apply("simulatica.config");
    private static final Path FILE = FileUtils.getConfigDirectory().resolve("simulatica.json");

    private SimulaticaConfigs() {}

    public static void initialize() {
        TPS_HUD.setValueChangeCallback(null);
        TPS_MULTILINE.setValueChangeCallback(null);
        var saved = JsonUtils.parseJsonFile(FILE);
        if (saved != null && saved.isJsonObject()) {
            JsonObject root = saved.getAsJsonObject();
            if (root.has("showTpsHud")) TPS_HUD.setValueFromJsonElement(root.get("showTpsHud"));
            if (root.has("multiLineTps")) TPS_MULTILINE.setValueFromJsonElement(root.get("multiLineTps"));
        }
        // MiniHUD saves its own settings on GUI close, so persist our shared options when they change.
        TPS_HUD.setValueChangeCallback(option -> save());
        TPS_MULTILINE.setValueChangeCallback(option -> save());
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.add("showTpsHud", TPS_HUD.getAsJsonElement());
        root.add("multiLineTps", TPS_MULTILINE.getAsJsonElement());
        JsonUtils.writeJsonToFile(root, FILE);
    }
}
