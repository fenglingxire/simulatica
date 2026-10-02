package ml.pypals.simulatica;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.tool.ToolMode;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.ProjectionBridge;
import ml.pypals.simulatica.simulation.server.SimulationRegion;
import ml.pypals.simulatica.simulation.server.SimulationCommands;
import ml.pypals.simulatica.simulation.server.SimulationServer;
import ml.pypals.simulatica.workshop.WorkshopCommands;
import ml.pypals.simulatica.workshop.WorkshopManager;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - ClientCommandManager → ClientCommands（fabric command-api v3 改名）
 * - 新增 /simulatica absorb [on|off] 与 /simulatica purge 子指令；根指令裸输改为打开控制面板
 * - DISCONNECT 时停止全部模拟、清理留档并关闭模拟服务器；sendFeedback 放宽为包私有供菜单复用
 */
public class SimulaticaClient implements ClientModInitializer {
    public static ToolMode SIMULATE;
    private static final SuggestionProvider<FabricClientCommandSource> PLACEMENT_SUGGESTION = (context, builder) -> {
        String prefix = builder.getRemainingLowerCase();
        Set<String> seen = new LinkedHashSet<>();

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            String name = schematicName(placement);
            if (name != null && name.toLowerCase().startsWith(prefix)) {
                seen.add(name);
            }
        }
        seen.forEach(builder::suggest);
        return builder.buildFuture();
    };

    @Nullable
    private static String schematicName(SchematicPlacement placement) {
        Path file = placement.getSchematicFile();
        if (file == null || file.getFileName() == null) return null;

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
    private static List<SchematicPlacement> placementsNamed(String name) {
        List<SchematicPlacement> found = new ArrayList<>();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (placement.getSchematic() == null) continue;

            String fileName = schematicName(placement);
            if ((fileName != null && fileName.equalsIgnoreCase(name))
                    || placement.getName().equalsIgnoreCase(name)) {
                found.add(placement);
            }
        }
        return found;
    }
    @Override
    public void onInitializeClient() {
        ml.pypals.simulatica.config.SimulaticaConfigs.initialize();
        ml.pypals.simulatica.gui.SimulaticaConfigScreen.register();
        registerTickEvent();
        registerCommands();
        registerShutdown();
        Simulatica.LOGGER.info("Client initialised.");
    }

    private void registerShutdown() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (WorkshopSession.handleDisconnect(handler)) return;
            client.execute(() -> {
                SimulationManager.getInstance().stopAllForWorldChange();
                SimulationManager.getInstance().clearLeftovers();
                ml.pypals.simulatica.carpet.BotManager.clearAll();
                SimulationServer.shutdown();
            });
        });
    }
    private void registerTickEvent() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                WorkshopManager.tick();
            } catch (Throwable exception) {
                reportTickException(exception);
            }
            if (WorkshopManager.isActive()) return;
            // A bare /simulatica asks for the menu; open it once the chat screen has closed.
            if (menuRequested && client.gui.screen() == null) {
                menuRequested = false;
                client.gui.setScreen(new SimulaticaMenuScreen());
            }
            if (client.level == null || client.isPaused()) {
                SimulationServer server = SimulationServer.getRunning();
                if (server != null) server.suspendClocks();
                return;
            }
            try {
                SimulationManager.getInstance().tick();
            } catch (Throwable t) {
                reportTickException(t);
            }
        });
    }

    /** Same exception class+message is only reported once every 30s, so a per-tick fault cannot spam the log. */
    private long lastErrorReportNanos = 0L;
    private String lastErrorSignature = null;

    private void reportTickException(Throwable t) {
        String signature = t.getClass().getName() + ": " + t.getMessage();
        long now = System.nanoTime();
        if (signature.equals(lastErrorSignature) && now - lastErrorReportNanos < 30_000_000_000L) {
            return;
        }
        lastErrorReportNanos = now;
        lastErrorSignature = signature;
        Simulatica.LOGGER.error("[Simulatica] Simulation tick failed: {}", signature, t);
    }

    private static volatile boolean menuRequested;

    /** Schedules the control panel for the next tick, after the chat screen closes. */
    static void openMenu() {
        menuRequested = true;
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("simulatica")
                        .executes(ctx -> {
                            if (WorkshopManager.isActive()) WorkshopManager.requestReturn();
                            else openMenu();
                            return 1;
                        })

                        .then(WorkshopCommands.node())

                        .then(ClientCommands.literal("start")
                                .requires(source -> !WorkshopManager.isActive())
                                .executes(ctx -> {
                                    ctx.getSource().getPlayer();
                                    startAll();
                                    sendFeedback("Started all schematic simulations.");
                                    return 1;
                                })
                                .then(ClientCommands.argument("placement_name", StringArgumentType.greedyString())
                                        .suggests(PLACEMENT_SUGGESTION)
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "placement_name");
                                            startByName(name);
                                            return 1;
                                        }))
                        )
                        .then(ClientCommands.literal("stop")
                                .requires(source -> !WorkshopManager.isActive())
                                .executes(ctx -> {
                                    SimulationManager.getInstance().stopAll();
                                    sendFeedback("Stopped all simulations.");
                                    return 1;
                                }).then(ClientCommands.argument("placement_name", StringArgumentType.greedyString())
                                        .suggests(PLACEMENT_SUGGESTION)
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "placement_name");
                                            stopByName(name);
                                            return 1;
                                        }))
                        )
                        .then(ClientCommands.literal("status")
                                .executes(ctx -> {
                                    reportStatus();
                                    return 1;
                                })
                        )
                        .then(ClientCommands.literal("tps")
                                .requires(source -> !WorkshopManager.isActive())
                                .then(ClientCommands.argument("placement_name", StringArgumentType.string())
                                        .suggests((ctx, builder) -> {
                                            String prefix = builder.getRemainingLowerCase();
                                            for (SchematicPlacement placement : collectLoadedPlacements()) {
                                                String name = placement.getName();
                                                String quoted = StringArgumentType.escapeIfRequired(name);
                                                if (name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)
                                                        || quoted.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                                                    builder.suggest(quoted);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> tpsByName(StringArgumentType.getString(ctx, "placement_name"), null))
                                        .then(ClientCommands.argument("tps", IntegerArgumentType.integer(1, 1000))
                                                .executes(ctx -> tpsByName(StringArgumentType.getString(ctx, "placement_name"),
                                                        IntegerArgumentType.getInteger(ctx, "tps"))))))
                        .then(ClientCommands.literal("absorb")
                                .requires(source -> !WorkshopManager.isActive())
                                .executes(ctx -> {
                                    reportAbsorption(SimulationManager.getInstance().setItemAbsorption(null));
                                    return 1;
                                })
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> {
                                            reportAbsorption(SimulationManager.getInstance().setItemAbsorption(true));
                                            return 1;
                                        }))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> {
                                            reportAbsorption(SimulationManager.getInstance().setItemAbsorption(false));
                                            return 1;
                                        }))
                        )
                        .then(ClientCommands.literal("purge")
                                .requires(source -> !WorkshopManager.isActive())
                                .executes(ctx -> {
                                    int removed = SimulationManager.getInstance().purgeEscapedEntities();
                                    sendFeedback(removed == 0
                                            ? "No escaped entities found."
                                            : "Purged " + removed + " escaped entit" + (removed == 1 ? "y" : "ies") + ".");
                                    return 1;
                                })
                        )
                        .then(ClientCommands.literal("execute")
                                .then(ClientCommands.argument("command", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> SimulationCommands.suggest(builder))
                                        .executes(ctx -> {
                                            SimulationCommands.execute(StringArgumentType.getString(ctx, "command"));
                                            return 1;
                                        }))
                        )
                        .then(ClientCommands.literal("server")
                                .requires(source -> !WorkshopManager.isActive())
                                .then(ClientCommands.literal("start")
                                        .executes(ctx -> {
                                            try {
                                                SimulationServer.getOrCreate();
                                                sendFeedback("Simulation server running.");
                                            } catch (Exception e) {
                                                sendFeedback("Failed to start the simulation server: " + e);
                                                Simulatica.LOGGER.error("[Simulatica] Simulation server start failed", e);
                                            }
                                            return 1;
                                        }))
                                .then(ClientCommands.literal("stop")
                                        .executes(ctx -> {
                                            SimulationManager.getInstance().stopAll();
                                            SimulationManager.getInstance().clearLeftovers();
                                            SimulationServer.shutdown();
                                            sendFeedback("Simulation server stopped.");
                                            return 1;
                                        }))
                        )
                )
        );
    }

    private static void reportAbsorption(boolean enabled) {
        sendFeedback("Item absorption " + (enabled
                ? "enabled — simulated drops touching you vanish (nothing enters your inventory)."
                : "disabled."));
    }

    private static void reportStatus() {
        Collection<ProjectionBridge> bridges = SimulationManager.getInstance().getAllSimulations();
        List<String> waiting = SimulationManager.getInstance().describePending();

        if (bridges.isEmpty() && waiting.isEmpty()) {
            sendFeedback("No simulations running.");
            return;
        }

        sendFeedback("Active simulations: " + bridges.size());
        waiting.forEach(line -> sendFeedback("  waiting: " + line));
        SimulationManager.getInstance().describeRates().forEach(SimulaticaClient::sendFeedback);
        for (ProjectionBridge bridge : bridges) {
            SimulationRegion region = bridge.region();
            sendFeedback("  " + bridge.label()
                    + " @ " + region.worldMin().toShortString() + ".." + region.worldMax().toShortString()
                    + " -- " + bridge.entities().size() + " entity(s)");
        }
    }

    private static int tpsByName(String name, @Nullable Integer tps) {
        List<SchematicPlacement> found = collectLoadedPlacements().stream()
                .filter(placement -> placement.getName().equals(name)).toList();
        if (found.size() != 1) {
            sendFeedback(found.isEmpty() ? "没有名为 '" + name + "' 的放置。" : "放置名称重复，请先改名：" + name);
            return 0;
        }
        try {
            SimulationManager manager = SimulationManager.getInstance();
            if (tps != null) manager.setTps(found.getFirst(), tps);
            sendFeedback(manager.describeTps(found.getFirst()));
            return 1;
        } catch (Exception e) {
            sendFeedback("TPS 设置失败：" + e.getMessage());
            return 0;
        }
    }

    private static void startAll() {
        List<SchematicPlacement> found = collectLoadedPlacements();
        if (found.isEmpty()) {
            sendFeedback("No loaded schematic placements found.");
            return;
        }

        for (SchematicPlacement placement : found) {
            SimulationManager.getInstance().startSimulation(placement);
        }
        sendFeedback("Started simulations for " + found.size() + " placement(s).");
    }
    private static void startByName(String name) {
        List<SchematicPlacement> found = placementsNamed(name);
        if (found.isEmpty()) {
            sendFeedback("No loaded schematic named '" + name + "'.");
            return;
        }

        found.forEach(SimulationManager.getInstance()::startSimulation);
        sendFeedback(found.size() == 1
                ? "Started simulation for '" + name + "'."
                : "Started simulations for " + found.size() + " placements of '" + name + "'.");
    }
    private static void stopByName(String name) {
        List<SchematicPlacement> found = placementsNamed(name);
        if (found.isEmpty()) {
            sendFeedback("No loaded schematic named '" + name + "'.");
            return;
        }

        found.forEach(SimulationManager.getInstance()::stopSimulation);
        sendFeedback(found.size() == 1
                ? "Stopped simulation for '" + name + "'."
                : "Stopped simulations for " + found.size() + " placements of '" + name + "'.");
    }
    private static List<SchematicPlacement> collectLoadedPlacements() {
        List<SchematicPlacement> result = new ArrayList<>();
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement.getSchematic() != null && !result.contains(placement)) {
                result.add(placement);
            }
        }
        return result;
    }

    static void sendFeedback(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(message));
        } else {
            Simulatica.LOGGER.info(message);
        }
    }

    /** 带样式（颜色）的反馈，原样发送 Component，保留颜色渲染。 */
    static void sendFeedback(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(message);
        } else {
            Simulatica.LOGGER.info(message.getString());
        }
    }
}
