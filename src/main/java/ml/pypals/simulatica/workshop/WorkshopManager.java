package ml.pypals.simulatica.workshop;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.LeftoverStore;
import ml.pypals.simulatica.simulation.server.SimulationCommandBlocks;
import ml.pypals.simulatica.simulation.server.SimulationMenus;
import ml.pypals.simulatica.simulation.server.SimulationServer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Coordinates the UI transaction; the local server and remote connection own their own state. */
public final class WorkshopManager {
    private static WorkshopEdit edit;
    private static SchematicPlacement pending;
    private static boolean returnRequested;
    private static boolean busy;
    private static boolean wasSimulating;
    private static boolean applyingReturn;
    private static String leftoverWorld;
    private static List<Path> entityFiles = List.of();
    private static Component error = Component.empty();
    private static List<String> conflicts = List.of();

    private WorkshopManager() {}

    public static boolean isActive() { return edit != null || WorkshopSession.isActive(); }
    public static boolean isLocalWorkshop() { return isActive(); }
    public static boolean isBusy() { return busy; }
    public static Component error() { return error; }
    public static List<String> conflicts() { return conflicts; }

    public static void enterSelected() {
        select(DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement());
    }

    public static void enter(String name) {
        var found = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                .filter(placement -> placement.getName().equals(name)).toList();
        if (found.size() != 1) {
            feedback(found.isEmpty() ? "simulatica.workshop.not_found" : "simulatica.workshop.ambiguous", name);
            return;
        }
        select(found.getFirst());
    }

    public static void enter(SchematicPlacement placement) {
        select(placement);
    }

    private static void select(SchematicPlacement placement) {
        if (isActive() || pending != null) { feedback("simulatica.workshop.already_open"); return; }
        if (placement == null || placement.getSchematic() == null) {
            feedback("simulatica.workshop.select_first");
            return;
        }
        pending = placement;
    }

    public static void requestReturn() {
        if (!isActive()) { feedback("simulatica.workshop.not_open"); return; }
        returnRequested = true;
    }

    public static void localConnectionFailed(Component reason) {
        error = Component.literal("本地工作间连接失败：").append(reason);
        Simulatica.LOGGER.error("[Simulatica] Local workshop connection failed: {}", reason.getString());
        returnRequested = false;
        Minecraft.getInstance().gui.setScreen(new WorkshopReturnScreen());
    }

    public static void tick() {
        WorkshopSession.tick();
        Minecraft mc = Minecraft.getInstance();
        if (pending != null && mc.gui.screen() == null) {
            SchematicPlacement placement = pending;
            pending = null;
            try {
                if (mc.player == null || mc.getConnection() == null || mc.hasSingleplayerServer()) {
                    throw new IllegalStateException("请先连接多人服务器。");
                }
                SimulationMenus.close();
                SimulationCommandBlocks.clear();
                wasSimulating = SimulationManager.getInstance().isSimulating(placement);
                leftoverWorld = LeftoverStore.currentWorldKey();
                List<Path> files = new ArrayList<>();
                for (String region : placement.getSchematic().getAreaSizes().keySet()) {
                    Path file = LeftoverStore.fileFor(LeftoverStore.key(placement, region));
                    if (file != null) files.add(file);
                }
                entityFiles = List.copyOf(files);
                edit = WorkshopEdit.create(placement);
                SimulationServer simulation = SimulationServer.getRunning();
                if (simulation != null) simulation.suspendClocks();
                WorkshopSession.open(edit.dimension(), edit.min(), edit.max(), edit.spawnPosition(), edit::populate);
                if (!WorkshopSession.isActive()) reset();
            } catch (Exception exception) {
                Simulatica.LOGGER.error("[Simulatica] Could not enter workshop", exception);
                reset();
                feedback("simulatica.workshop.failed", exception.getMessage());
            }
        }
        if (returnRequested && mc.player != null && !busy) {
            returnRequested = false;
            mc.gui.setScreen(new WorkshopReturnScreen());
        }
        if (isActive()) {
            SimulationServer server = SimulationServer.getRunning();
            if (server != null) server.suspendClocks();
        }
    }

    public static void finish(boolean apply) {
        WorkshopSession session = WorkshopSession.current();
        WorkshopEdit transaction = edit;
        if (session == null || transaction == null || busy) return;
        if (apply && session.server().isShutdown()) {
            showFailure(new IllegalStateException("本地工作间服务器已停止，编辑存档保留于 " + session.server().directory() + "；可以放弃并返回服务器。"));
            return;
        }
        busy = true;
        error = Component.empty();
        conflicts = List.of();
        if (!apply) {
            try { session.prepareReturn(); leave(session, false); }
            catch (Exception exception) {
                if (WorkshopSession.current() == session && !session.server().isShutdown()) showFailure(exception);
                else showHandoffFailure(exception, false);
            }
            return;
        }
        session.server().execute(() -> {
            boolean frozen = session.server().tickRateManager().isFrozen();
            session.server().tickRateManager().setFrozen(true);
            try {
                WorkshopEdit.Result candidate = transaction.capture(session.level());
                Minecraft.getInstance().execute(() -> {
                    Map<Path, byte[]> previousFiles = Map.of();
                    boolean committed = false;
                    try {
                        session.prepareReturn();
                        previousFiles = deleteSupersededEntityFiles();
                        transaction.commit(candidate);
                        committed = true;
                        leave(session, true);
                    } catch (Exception exception) {
                        if (WorkshopSession.current() == session && !session.server().isShutdown()) {
                            if (committed) {
                                try { transaction.rollback(); }
                                catch (Exception rollback) { exception.addSuppressed(rollback); }
                            }
                            restoreEntityFiles(previousFiles, exception);
                            session.server().execute(() -> session.server().tickRateManager().setFrozen(frozen));
                            showFailure(exception);
                        } else {
                            showHandoffFailure(exception, committed);
                        }
                    }
                });
            } catch (Exception exception) {
                session.server().tickRateManager().setFrozen(frozen);
                Minecraft.getInstance().execute(() -> showFailure(exception));
            }
        });
    }

    public static void enableRegion(String name) {
        WorkshopSession session = WorkshopSession.current();
        WorkshopEdit transaction = edit;
        if (session == null || transaction == null || busy) return;
        busy = true;
        session.server().execute(() -> {
            try {
                transaction.enableRegion(name, session.level());
                Minecraft.getInstance().execute(() -> {
                    busy = false;
                    conflicts = List.of();
                    error = Component.empty();
                    Minecraft.getInstance().gui.setScreen(null);
                    feedback("simulatica.workshop.region_enabled", name);
                });
            } catch (Exception exception) {
                Minecraft.getInstance().execute(() -> showFailure(exception));
            }
        });
    }

    private static void showFailure(Exception exception) {
        busy = false;
        error = Component.translatable("simulatica.workshop.failed", exception.getMessage());
        conflicts = exception instanceof WorkshopEdit.DisabledRegionConflict conflict ? conflict.regions() : List.of();
        Simulatica.LOGGER.warn("[Simulatica] Workshop transaction retained after failure", exception);
        Minecraft.getInstance().gui.setScreen(new WorkshopReturnScreen());
    }

    private static void showHandoffFailure(Exception exception, boolean applied) {
        reset();
        Simulatica.LOGGER.error("[Simulatica] Workshop return failed after local shutdown", exception);
        feedback(applied ? "simulatica.workshop.return_failed_applied" : "simulatica.workshop.return_failed", exception.getMessage());
    }

    private static Map<Path, byte[]> deleteSupersededEntityFiles() throws java.io.IOException {
        Map<Path, byte[]> previous = new LinkedHashMap<>();
        for (Path file : entityFiles) if (Files.exists(file)) previous.put(file, Files.readAllBytes(file));
        try {
            for (Path file : previous.keySet()) Files.delete(file);
        } catch (java.io.IOException failure) {
            restoreEntityFiles(previous, failure);
            throw failure;
        }
        return previous;
    }

    private static void restoreEntityFiles(Map<Path, byte[]> files, Exception failure) {
        files.forEach((file, bytes) -> {
            try { if (!Files.exists(file)) Files.write(file, bytes); }
            catch (java.io.IOException restoration) { failure.addSuppressed(restoration); }
        });
    }

    private static void leave(WorkshopSession session, boolean applied) {
        WorkshopEdit transaction = edit;
        applyingReturn = applied;
        session.returnToRemote();
        EditedPlacementCache.restore();
        if (applied) {
            if (transaction.worldMatchesNow()) {
                SchematicPlacement selected = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                        .filter(placement -> placement.getHashId().equals(transaction.placement().getHashId()))
                        .findFirst().orElse(null);
                SimulationManager manager = SimulationManager.getInstance();
                if (selected != null && wasSimulating && !manager.isSimulating(selected)) manager.startSimulation(selected);
            }
        }
        reset();
        SimulationServer server = SimulationServer.getRunning();
        if (server != null) server.suspendClocks();
        feedback(applied ? "simulatica.workshop.applied" : "simulatica.workshop.discarded");
    }

    /** Called before the remote world's lifecycle hooks can replace the Litematica namespace. */
    public static void beforeRemoteRestore(boolean sameWorld, boolean connected) {
        if (edit == null) return;
        SimulationManager manager = SimulationManager.getInstance();
        if (applyingReturn) {
            manager.discardSimulationState(edit.placement());
        }
        if (!sameWorld || !connected) {
            LeftoverStore.withWorldKey(leftoverWorld, () -> {
                manager.stopAllForWorldChange();
                manager.clearLeftovers();
                ml.pypals.simulatica.carpet.BotManager.clearAll();
                SimulationServer.shutdown();
            });
        }
    }

    private static void reset() {
        if (edit != null) edit.close();
        edit = null;
        pending = null;
        busy = false;
        applyingReturn = false;
        leftoverWorld = null;
        returnRequested = false;
        conflicts = List.of();
        error = Component.empty();
        entityFiles = List.of();
    }

    private static void feedback(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        Component message = Component.translatable(key, args);
        if (mc.player != null) mc.player.sendSystemMessage(message);
        else Simulatica.LOGGER.info(message.getString());
    }
}
