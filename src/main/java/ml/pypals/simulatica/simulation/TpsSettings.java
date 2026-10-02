package ml.pypals.simulatica.simulation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ml.pypals.simulatica.Simulatica;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.*;

/** World identity and exact placement name, without filename sanitizing collisions. */
public final class TpsSettings {
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("simulatica/tps.json");
    private static JsonObject values;

    private TpsSettings() {}

    private static String world() {
        Minecraft client = Minecraft.getInstance();
        if (client.getSingleplayerServer() != null)
            return "local:" + client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (client.getCurrentServer() != null) return "server:" + client.getCurrentServer().ip;
        throw new IllegalStateException("No world connected");
    }

    public static void reload() {
        values = new JsonObject();
        if (!Files.exists(FILE)) return;
        try {
            values = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
        } catch (Exception e) {
            Simulatica.LOGGER.warn("[Simulatica] Could not read TPS settings; using 20 TPS", e);
        }
    }

    public static int get(String name) {
        if (values == null) reload();
        try {
            JsonObject placements = values.getAsJsonObject(world());
            if (placements == null || !placements.has(name)) return 20;
            double raw = placements.get(name).getAsDouble();
            if (!Double.isFinite(raw) || raw != Math.floor(raw) || raw < 1 || raw > 1000)
                throw new IllegalArgumentException("Invalid TPS: " + raw);
            return (int) raw;
        } catch (RuntimeException e) {
            Simulatica.LOGGER.warn("[Simulatica] Invalid TPS setting for '{}'; using 20 TPS", name, e);
            return 20;
        }
    }

    public static void set(String name, int tps) throws IOException {
        if (tps < 1 || tps > 1000) throw new IllegalArgumentException("TPS must be 1–1000");
        if (values == null) reload();
        JsonObject updated = values.deepCopy();
        String key = world();
        JsonObject placements = updated.has(key) && updated.get(key).isJsonObject()
                ? updated.getAsJsonObject(key) : new JsonObject();
        placements.addProperty(name, tps);
        updated.add(key, placements);
        Files.createDirectories(FILE.getParent());
        Path temporary = Files.createTempFile(FILE.getParent(), "tps-", ".tmp");
        try {
            Files.writeString(temporary, new Gson().toJson(updated));
            try {
                Files.move(temporary, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            values = updated;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
