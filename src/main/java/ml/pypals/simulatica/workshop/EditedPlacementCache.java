package ml.pypals.simulatica.workshop;

import com.google.gson.JsonObject;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.simulation.SimulationManager;
import java.nio.file.Path;
import java.util.*;

/** Process memory only: Litematica clears its own holder on logout and dimension changes. */
public final class EditedPlacementCache {
    private record Key(String world, UUID placement) {}
    private record Entry(Path file, WorkshopEdit.Result result, boolean running) {}
    private static final Map<Key, Entry> ENTRIES = new HashMap<>();
    private static final Map<LitematicaSchematic, Integer> RELOADS = new WeakHashMap<>();
    private EditedPlacementCache() {}

    public static boolean hasEdit(SchematicPlacement placement) {
        return ENTRIES.containsKey(new Key(WorkshopEdit.currentWorldKey(), placement.getHashId()));
    }

    public static void remember(String world, SchematicPlacement placement, WorkshopEdit.Result result) {
        ENTRIES.put(new Key(world, placement.getHashId()), new Entry(result.schematic().getFile(), result,
                SimulationManager.getInstance().isSimulating(placement)));
    }
    public static Runnable undoFor(String world, SchematicPlacement placement) {
        Key key = new Key(world, placement.getHashId());
        Entry previous = ENTRIES.get(key);
        return () -> { if (previous == null) ENTRIES.remove(key); else ENTRIES.put(key, previous); };
    }

    public static void beforeWorldChange() {
        if (WorkshopSession.isActive() && !WorkshopSession.isRemoteScope()) return;
        String world = WorkshopEdit.currentWorldKey();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            Key key = new Key(world, placement.getHashId());
            Entry entry = ENTRIES.get(key);
            if (entry != null && entry.result().schematic() == placement.getSchematic()) {
                Map<String, JsonObject> regions = new LinkedHashMap<>();
                placement.getAllSubRegionsPlacements().forEach(sub -> regions.put(sub.getName(), sub.toJson().deepCopy()));
                ENTRIES.put(key, new Entry(entry.file(), new WorkshopEdit.Result(placement.getSchematic(), Map.copyOf(regions)),
                        entry.running()));
            }
        }
    }

    public static void restore() {
        if (WorkshopSession.isActive() && !WorkshopSession.isRemoteScope()) return;
        String world = WorkshopEdit.currentWorldKey();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            Entry entry = ENTRIES.get(new Key(world, placement.getHashId()));
            if (entry == null || !Objects.equals(entry.file(), placement.getSchematic().getFile()) || placement.getSchematic() == entry.result().schematic()) continue;
            WorkshopEdit.replace(placement, entry.result());
            if (entry.running()) SimulationManager.getInstance().startSimulation(placement);
        }
    }

    public static void invalidate(SchematicPlacement placement) {
        ENTRIES.remove(new Key(WorkshopEdit.currentWorldKey(), placement.getHashId()));
    }

    public static void simulationStateChanged(SchematicPlacement placement, boolean running) {
        Key key = new Key(WorkshopEdit.currentWorldKey(), placement.getHashId());
        Entry entry = ENTRIES.get(key);
        if (entry != null) ENTRIES.put(key, new Entry(entry.file(), entry.result(), running));
    }

    public static void invalidate(LitematicaSchematic schematic) {
        RELOADS.merge(schematic, 1, Integer::sum);
        ENTRIES.entrySet().removeIf(e -> e.getValue().result().schematic() == schematic);
    }
    public static int generation(LitematicaSchematic schematic) { return RELOADS.getOrDefault(schematic, 0); }
}
