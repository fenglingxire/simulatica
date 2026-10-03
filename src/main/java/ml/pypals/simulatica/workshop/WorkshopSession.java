package ml.pypals.simulatica.workshop;

import com.mojang.serialization.JsonOps;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.mixin.workshop.WorkshopMinecraftAccessor;
import ml.pypals.simulatica.mixin.workshop.WorkshopDataManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Input;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/** One foreground workshop and one background remote session. */
public final class WorkshopSession {
    public interface ConnectionOwner { Connection workshop$connection(); }
    private static volatile WorkshopSession current;
    private static final ThreadLocal<RemoteSession> scope = new ThreadLocal<>();
    private static final ThreadLocal<java.util.Deque<Connection>> metadataScope = ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private static final java.util.Set<Connection> retiredConnections = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    private static final java.util.Map<PacketListener, java.lang.ref.WeakReference<Connection>> listenerOwners =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final RemoteSession remote;
    private WorkshopServer server;
    private Connection localConnection;
    private boolean returning;
    private DisconnectionDetails localFailure;
    private final boolean sourceCanSave = WorkshopDataManagerAccessor.simulatica$canSave();

    private WorkshopSession(Minecraft mc) { remote = new RemoteSession(mc); }
    public static WorkshopSession current() { return current; }
    public static boolean isActive() { return current != null; }
    public static RemoteSession remote() { return current == null ? null : current.remote; }
    public static boolean isRemoteScope() {
        if (current == null) return false;
        var stack = metadataScope.get();
        if (!stack.isEmpty()) return current != null && current.remote.owns(stack.peek());
        return scope.get() != null;
    }
    public static boolean isWorldScope() { return scope.get() != null; }
    static void enterRemoteScope(RemoteSession owner) { scope.set(owner); }
    static void leaveRemoteScope() { scope.remove(); }
    public static void enterMetadataScope(Connection owner) { metadataScope.get().push(owner); }
    public static void leaveMetadataScope() {
        var stack = metadataScope.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) metadataScope.remove();
    }
    public static Connection sourceConnection() {
        return !metadataScope.get().isEmpty() ? metadataScope.get().peek() : scope.get() == null ? null : scope.get().connection();
    }

    public static void runFor(Connection connection, Runnable action) {
        WorkshopSession session = current;
        if (retired(connection) && !(session != null && session.localConnection == connection && !session.returning)) return;
        if (session != null && session.remote.owns(connection)) {
            if (session.remote.active(connection)) session.remote.run(action);
        }
        else action.run();
    }

    public static void runFor(PacketListener listener, Runnable action) {
        WorkshopSession session = current;
        var reference = listenerOwners.get(listener);
        var owner = reference == null ? null : reference.get();
        if (owner != null && retired(owner) && !(session != null && session.localConnection == owner && !session.returning)) return;
        if (session != null && session.remote.owns(listener)) {
            if (session.remote.accepts(listener)) session.remote.run(action);
        }
        else action.run();
    }

    public static void open(ResourceKey<Level> dimension, BlockPos min, BlockPos max, Consumer<ServerLevel> initializer) {
        open(dimension, min, max, new BlockPos(Math.floorDiv(min.getX() + max.getX(), 2), min.getY() + 1, min.getZ() + 8), initializer);
    }

    public static void open(ResourceKey<Level> dimension, BlockPos min, BlockPos max, BlockPos spawn, Consumer<ServerLevel> initializer) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Open the workshop on the client thread");
        if (current != null) throw new IllegalStateException("A workshop is already open");
        if (mc.getConnection() == null || mc.hasSingleplayerServer()) throw new IllegalStateException("A multiplayer world is required");
        if (!dimension.equals(mc.level.dimension())) throw new IllegalArgumentException("Workshop must follow the current source dimension");
        if (!java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END).contains(dimension))
            throw new UnsupportedOperationException("工作间暂不支持自定义维度：" + dimension);
        var expectedTypeKey = dimension.equals(Level.NETHER) ? net.minecraft.world.level.dimension.BuiltinDimensionTypes.NETHER
                : dimension.equals(Level.END) ? net.minecraft.world.level.dimension.BuiltinDimensionTypes.END
                : net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD;
        if (!mc.level.dimensionTypeRegistration().is(expectedTypeKey))
            throw new UnsupportedOperationException("工作间暂不支持自定义维度类型：" + mc.level.dimensionTypeRegistration().unwrapKey());
        var sourceType = net.minecraft.world.level.dimension.DimensionType.NETWORK_CODEC.encodeStart(
                mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), mc.level.dimensionType()).getOrThrow();
        WorkshopSession session = new WorkshopSession(mc);
        current = session;
        try {
            fi.dy.masa.litematica.data.DataManager.save();
            WorkshopMetadata.capture();
            mc.player.closeContainer();
            mc.gameMode.stopDestroyBlock();
            if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
            mc.player.setSprinting(false);
            session.remote.connection().send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(
                    mc.player, net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            session.remote.connection().send(new ServerboundPlayerInputPacket(Input.EMPTY));
            var inputState = (ml.pypals.simulatica.mixin.workshop.WorkshopPlayerInputAccessor) mc.player;
            inputState.workshop$lastInput(Input.EMPTY);
            inputState.workshop$lastSprint(false);
            // Validate the source environment before detaching the foreground or running the initializer.
            session.server = WorkshopServer.start(dimension, spawn, sourceType, initializer);
            // Clear the native camera/render engines together with the world before asynchronous login.
            // HUD integrations use the camera to decide whether a world is available.
            mc.gameRenderer.resetData();
            mc.setLevel(null);
            mc.player = null;
            mc.gameMode = null;
            mc.gui.setScreen(new net.minecraft.client.gui.screens.GenericMessageScreen(Component.literal("正在加载工作间…")));
            WorkshopMinecraftAccessor accessor = (WorkshopMinecraftAccessor) mc;
            accessor.workshop$server(session.server);
            accessor.workshop$local(true);
            long deadline = System.nanoTime() + 120_000_000_000L;
            mc.managedBlock(() -> {
                mc.packetProcessor().processQueuedPackets();
                session.remote.tick();
                return session.server.isReady() || session.server.isShutdown() || System.nanoTime() >= deadline;
            });
            if (!session.server.isReady()) throw new IllegalStateException("Workshop server did not start");
            var address = session.server.getConnection().startMemoryChannel();
            session.localConnection = Connection.connectToLocalServer(address);
            retiredConnections.add(session.localConnection);
            session.localConnection.initiateServerboundPlayConnection(address.toString(), 0,
                    new ClientHandshakePacketListenerImpl(session.localConnection, mc, null, null, true, null,
                            message -> {}, new LevelLoadTracker(), null));
            session.localConnection.send(new ServerboundHelloPacket(mc.getUser().getName(), mc.getUser().getProfileId()));
            accessor.workshop$pending(session.localConnection);
        } catch (Throwable error) {
            Simulatica.LOGGER.error("Could not start the workshop", error);
            session.returnToRemote();
            notifyLocal("工作间启动失败：" + error.getMessage());
        }
    }

    public WorkshopServer server() { return server; }
    public ServerLevel level() { return server == null ? null : server.getLevel(server.sourceDimension()); }

    public static void tick() {
        WorkshopSession session = current;
        if (session != null && !session.returning) session.remote.tick();
    }

    public static boolean handleDisconnect(ClientPacketListener handler) {
        if (retiredConnections.contains(handler.getConnection())) return true;
        WorkshopSession session = current;
        if (session == null) return false;
        return session.remote.owns(handler) || handler.getConnection() == session.localConnection;
    }
    public static boolean retired(Connection connection) { return retiredConnections.contains(connection); }
    public static void retireConnection(Connection connection) { retiredConnections.add(connection); }
    public static void rememberListener(Connection connection, PacketListener listener) {
        listenerOwners.put(listener, new java.lang.ref.WeakReference<>(connection));
    }
    public boolean isReturning() { return returning; }
    public DisconnectionDetails localFailure() { return localFailure; }

    /** The local connection is retired for late callbacks, but an active failure must reach the UI. */
    public static void handleLocalDisconnection(Connection connection) {
        WorkshopSession session = current;
        if (session == null || session.returning || session.localConnection != connection
                || connection.isConnected() || connection.isConnecting()) return;
        Minecraft.getInstance().execute(() -> {
            if (current != session || session.returning || session.localFailure != null) return;
            DisconnectionDetails details = connection.getDisconnectionDetails();
            session.localFailure = details != null ? details : new DisconnectionDetails(Component.literal("本地工作间连接已关闭"));
            WorkshopManager.localConnectionFailed(session.localFailure.reason());
        });
    }

    /** Window close must not open an apply dialog or wait for a background login. */
    public static void shutdown() {
        WorkshopSession session = current;
        if (session == null) return;
        session.returning = true;
        Minecraft mc = Minecraft.getInstance();
        boolean successful = true;
        try {
            successful &= attempt(() -> {
                if (session.localConnection != null) session.localConnection.disconnect(Component.literal("Client closing"));
                if (session.server != null) session.server.halt(true);
            }, "stop workshop on client shutdown");
            successful &= attempt(() -> WorkshopManager.beforeRemoteRestore(session.remote.sameWorld(), false), "save source simulations on shutdown");
            var accessor = (WorkshopMinecraftAccessor) mc;
            accessor.workshop$server(null); accessor.workshop$pending(null); accessor.workshop$local(false);
            successful &= attempt(WorkshopMetadata::restore, "restore metadata on shutdown");
            successful &= attempt(session.remote::restoreForShutdown, "restore source namespace on shutdown");
            session.remote.connection().disconnect(Component.literal("Client closing"));
        } finally {
            WorkshopDataManagerAccessor.simulatica$canSave(session.sourceCanSave);
            current = null;
            WorkshopMetadata.clear();
            if (successful && session.server != null && session.server.isShutdown()) deleteDisposable(session.server);
            else if (session.server != null) Simulatica.LOGGER.error("Workshop shutdown failed; recovery world retained at {}", session.server.directory());
        }
    }

    /** Wait before committing: a timed-out transition leaves the editable local world intact. */
    public void prepareReturn() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Prepare return on the client thread");
        if (!remote.connected() || remote.foregroundReady()) return;
        notifyLocal("等待远端服务器加载完成后返回");
        long deadline = System.nanoTime() + 120_000_000_000L;
        mc.managedBlock(() -> {
            mc.packetProcessor().processQueuedPackets();
            remote.tick();
            mc.renderFrame(false);
            return !mc.isRunning() || !remote.connected() || remote.foregroundReady() || System.nanoTime() >= deadline;
        });
        if (!mc.isRunning()) throw new IllegalStateException("客户端正在关闭，返回交接已取消");
        if (remote.connected() && !remote.foregroundReady()) throw new IllegalStateException("远端服务器仍在加载，工作间已保留；请稍后重试返回");
    }

    public void returnToRemote() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) { mc.execute(this::returnToRemote); return; }
        if (returning) return;
        prepareReturn();
        returning = true;
        Throwable handoffFailure = null;
        try {
            if (server != null) {
                server.halt(true);
                if (!server.isShutdown()) throw new IllegalStateException("工作间服务器尚未停止，原连接与工作间已保留");
            }
        } catch (Throwable failure) {
            if (server == null || !server.isShutdown()) {
                returning = false;
                throw new IllegalStateException("工作间停止失败，尚未切换远端；临时世界保留于 " + (server == null ? "启动尚未完成" : server.directory()), failure);
            }
            handoffFailure = failure;
        }
        try {
            if (localConnection != null) {
                localConnection.disconnect(Component.literal("Leaving Simulatica workshop"));
                if (localConnection.getPacketListener() instanceof ClientPacketListener play) play.close();
            }
            WorkshopManager.beforeRemoteRestore(remote.sameWorld(), remote.connected());
        } catch (Throwable failure) { handoffFailure = combine(handoffFailure, failure); }
        try {
            var accessor = (WorkshopMinecraftAccessor) mc;
            accessor.workshop$server(null);
            accessor.workshop$local(false);
            accessor.workshop$pending(null);
            mc.gameRenderer.resetData();
            mc.setLevel(null);
            mc.player = null; mc.gameMode = null;
            try { WorkshopMetadata.restore(); }
            catch (Throwable failure) { handoffFailure = combine(handoffFailure, failure); }
            try {
                if (remote.connected()) remote.restore();
                else remote.restoreForShutdown();
            }
            catch (Throwable failure) { handoffFailure = combine(handoffFailure, failure); }
        } finally {
            // The still-loaded placements belong to the source; a subsequent world load
            // may replace them and establish its own save state after current is cleared.
            WorkshopDataManagerAccessor.simulatica$canSave(sourceCanSave);
            current = null;
            attempt(WorkshopMetadata::clear, "clear workshop metadata");
        }
        try {
            if (!remote.connected()) mc.disconnect(remote.disconnectScreen(), false);
            else if (!remote.sameWorld() && remote.level() != null) {
                var loads = (fi.dy.masa.malilib.event.WorldLoadHandler) fi.dy.masa.malilib.event.WorldLoadHandler.getInstance();
                loads.onWorldLoadImmutable(remote.level().registryAccess().freeze());
                // The old manager was saved under its own key on entry; do not save it under the new server's key.
                loads.onWorldLoadPre(null, remote.level(), mc);
                loads.onWorldLoadPost(null, remote.level(), mc);
            }
        } catch (Throwable failure) { handoffFailure = combine(handoffFailure, failure); }
        if (handoffFailure != null) throw new IllegalStateException(
                "返回远端的界面恢复失败；编辑缓存与临时世界保留于 " + (server == null ? "启动尚未完成" : server.directory()), handoffFailure);
        if (server != null && server.isShutdown()) deleteDisposable(server);
    }

    private static void deleteDisposable(WorkshopServer server) {
        deleteDisposableDirectory(server.directory());
    }

    static void deleteDisposableDirectory(java.nio.file.Path directory) {
        var root = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("simulatica/workshops");
        if (!directory.toAbsolutePath().normalize().startsWith(root) || !directory.getFileName().toString().startsWith("workshop-"))
            throw new IllegalStateException("Refusing to remove an unowned workshop directory: " + directory);
        attempt(() -> {
            try (var paths = java.nio.file.Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }, "remove disposable workshop directory");
    }


    private static Throwable combine(Throwable previous, Throwable failure) {
        if (previous == null) return failure;
        if (previous != failure) previous.addSuppressed(failure);
        return previous;
    }

    private static boolean attempt(Runnable action, String phase) {
        try { action.run(); return true; }
        catch (Throwable failure) { Simulatica.LOGGER.error("Could not {}", phase, failure); return false; }
    }

    public static void notifyLocal(String message) {
        Minecraft mc = Minecraft.getInstance();
        scheduleLocal(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[Simulatica] " + message));
            else Simulatica.LOGGER.info("[Simulatica] {}", message);
        });
    }

    public static void scheduleLocal(Runnable task) {
        RemoteSession previous = scope.get();
        scope.remove();
        try { Minecraft.getInstance().schedule(task); }
        finally { if (previous != null) scope.set(previous); }
    }

    public static void queueRemoteScreen(Screen screen) {
        if (screen == null) { if (remote() != null) remote().recordScreen(null); return; }
        if (screen instanceof net.minecraft.client.gui.screens.WinScreen) {
            // Ending the credits acknowledges the server's dimension transition, without input or movement.
            screen.onClose();
            notifyLocal("远端末地返回流程已在后台完成");
            return;
        }
        String name = screen.getClass().getName();
        if (name.contains("PackConfirm") || name.contains("CodeOfConduct") || name.contains("Dialog")) {
            // Native server requests keep their bound remote packet sender; only the modal is foreground.
            RemoteSession owner = remote();
            long generation = owner == null ? -1 : owner.generation();
            scheduleLocal(() -> {
                if (remote() == owner && owner != null && owner.generation() == generation) Minecraft.getInstance().gui.setScreen(screen);
            });
        } else if (!name.contains("LevelLoading") && !name.contains("ServerReconfig")) {
            if (remote() != null) remote().recordScreen(screen);
            notifyLocal("远端服务器状态发生变化：" + screen.getTitle().getString());
        }
    }
}
