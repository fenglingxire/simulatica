package ml.pypals.simulatica.workshop;

import ml.pypals.simulatica.mixin.workshop.WorkshopMinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.client.multiplayer.resolver.*;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.entity.Entity;

import java.net.InetSocketAddress;
import java.util.*;

/** The server connection persists while its client world is not rendered. */
public final class RemoteSession {
    private volatile Connection connection;
    private final Set<Connection> connections = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final Map<PacketListener, Connection> listeners = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long generation;
    private ClientLevel level;
    private LocalPlayer player;
    private MultiPlayerGameMode gameMode;
    private Entity camera;
    private volatile ServerData serverData;
    private volatile DisconnectionDetails disconnected;
    private volatile Screen disconnectScreen;
    private Screen pendingScreen;
    private volatile boolean transferring;
    private int clientTicks;
    private long nextTick;
    private final ClientLevel initialLevel;
    private final LocalPlayer initialPlayer;
    private final MultiPlayerGameMode initialGameMode;
    private final String initialAddress;

    RemoteSession(Minecraft mc) {
        level = mc.level;
        player = mc.player;
        gameMode = mc.gameMode;
        camera = mc.getCameraEntity();
        serverData = mc.getCurrentServer();
        initialLevel = level;
        initialPlayer = player;
        initialGameMode = gameMode;
        initialAddress = serverData == null ? "" : serverData.ip;
        connection = Objects.requireNonNull(mc.getConnection()).getConnection();
        connections.add(connection);
        listeners.put(mc.getConnection(), connection);
        WorkshopSession.rememberListener(connection, mc.getConnection());
    }

    public Connection connection() { return connection; }
    public long generation() { return generation; }
    public LocalPlayer player() { return player; }
    public ClientLevel level() { return level; }
    public boolean owns(Connection value) { return connections.contains(value); }
    public boolean owns(PacketListener value) { return listeners.containsKey(value); }
    public boolean accepts(PacketListener value) { return listeners.get(value) == connection && value == connection.getPacketListener(); }
    public boolean active(Connection value) { return value == connection; }
    public ClientLevel initialLevel() { return initialLevel; }
    public boolean sameWorld() { return level == initialLevel && level != null && level.dimension().equals(initialLevel.dimension())
            && Objects.equals(initialAddress, serverData == null ? "" : serverData.ip); }
    public boolean connected() { return disconnected == null; }
    public boolean foregroundReady() { return !transferring && connection.isConnected() && player != null && level != null
            && connection.getPacketListener() instanceof ClientPacketListener play && play.hasClientLoaded(); }
    public Screen disconnectScreen() { return disconnectScreen; }
    public void recordScreen(Screen screen) { pendingScreen = screen; }

    public void listenerChanged(Connection source, PacketListener listener) {
        if (owns(source)) listeners.put(listener, source);
    }

    public void setLevel(ClientLevel value) { level = value; }
    public void clearLevel() { level = null; player = null; gameMode = null; camera = null; }
    public void setCamera(Entity value) { camera = value; }

    public void run(Runnable action) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) { mc.execute(() -> run(action)); return; }
        if (WorkshopSession.isWorldScope()) { action.run(); return; }
        var localLevel = mc.level;
        var localPlayer = mc.player;
        var localMode = mc.gameMode;
        WorkshopSession.enterRemoteScope(this);
        mc.level = level;
        mc.player = player;
        mc.gameMode = gameMode;
        try { action.run(); }
        finally {
            level = mc.level;
            player = mc.player;
            gameMode = mc.gameMode;
            mc.level = localLevel;
            mc.player = localPlayer;
            mc.gameMode = localMode;
            WorkshopSession.leaveRemoteScope();
        }
    }

    void restore() {
        Minecraft mc = Minecraft.getInstance();
        mc.player = player;
        mc.gameMode = gameMode;
        mc.setLevel(level);
        mc.setCameraEntity(camera != null ? camera : player);
        if (disconnectScreen != null) mc.gui.setScreen(disconnectScreen);
        else mc.gui.setScreen(pendingScreen);
    }

    void restoreForShutdown() {
        // Litematica's still-loaded placements belong to the entering server, even after transfer.
        Minecraft mc = Minecraft.getInstance();
        mc.player = initialPlayer;
        mc.gameMode = initialGameMode;
        mc.setLevel(initialLevel);
        mc.setCameraEntity(initialPlayer);
    }

    void tick() {
        if (transferring) return;
        long now = System.nanoTime();
        if (now < nextTick) return;
        nextTick = now + 50_000_000L;
        run(() -> {
            if (level != null) level.update();
            connection.tick();
            if (player != null && connection.getPacketListener() instanceof ClientPacketListener play && play.hasClientLoaded()) {
                play.send(net.minecraft.network.protocol.game.ServerboundClientTickEndPacket.INSTANCE);
            }
        });
        clientTicks++;
    }

    public void disconnect(Connection source, DisconnectionDetails details, Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) { mc.execute(() -> disconnect(source, details, screen)); return; }
        if (WorkshopSession.remote() != this) return;
        if (source != connection || transferring) return;
        disconnected = details;
        disconnectScreen = screen;
        WorkshopSession.notifyLocal("远端连接已断开：" + details.reason().getString());
    }

    public void transfer(String host, int port, TransferState state) {
        Connection previous = connection;
        transferring = true;
        generation++;
        long expectedGeneration = generation;
        clearLevel();
        if (WorkshopSession.isWorldScope()) {
            Minecraft mc = Minecraft.getInstance();
            mc.player = null; mc.level = null; mc.gameMode = null;
        }
        WorkshopSession.retireConnection(previous);
        previous.disconnect(Component.translatable("disconnect.transfer"));
        previous.setReadOnly();
        previous.handleDisconnection();
        ServerData nextData = new ServerData(serverData == null ? host : serverData.name, host + ":" + port,
                serverData == null ? ServerData.Type.OTHER : serverData.type());
        if (serverData != null) nextData.setResourcePackStatus(serverData.getResourcePackStatus());
        serverData = nextData;
        WorkshopSession.notifyLocal("远端服务器正在转移至 " + host + ":" + port);
        Thread.ofPlatform().name("Simulatica remote transfer").daemon().start(() -> {
            try {
                InetSocketAddress address = ServerNameResolver.DEFAULT.resolveAddress(new ServerAddress(host, port))
                        .map(ResolvedServerAddress::asInetSocketAddress).orElseThrow(() -> new IllegalStateException("Unknown host"));
                Connection next = new Connection(PacketFlow.CLIENTBOUND);
                if (WorkshopSession.remote() != this || generation != expectedGeneration) return;
                connections.add(next);
                connection = next;
                Connection.connect(address, EventLoopGroupHolder.remote(Minecraft.getInstance().options.useNativeTransport()), next).syncUninterruptibly();
                if (WorkshopSession.remote() != this || generation != expectedGeneration) { next.disconnect(Component.literal("Transfer cancelled")); return; }
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (WorkshopSession.remote() != this || generation != expectedGeneration) {
                        next.disconnect(Component.literal("Transfer cancelled")); return;
                    }
                    run(() -> {
                        var status = switch (serverData.getResourcePackStatus()) {
                            case ENABLED -> net.minecraft.client.resources.server.ServerPackManager.PackPromptStatus.ALLOWED;
                            case DISABLED -> net.minecraft.client.resources.server.ServerPackManager.PackPromptStatus.DECLINED;
                            case PROMPT -> net.minecraft.client.resources.server.ServerPackManager.PackPromptStatus.PENDING;
                        };
                        mc.getDownloadedPackSource().configureForServerControl(next, status);
                        var listener = new ClientHandshakePacketListenerImpl(next, mc, serverData, null, false, null,
                                message -> {}, new LevelLoadTracker(), state);
                        listeners.put(listener, next);
                        next.initiateServerboundPlayConnection(address.getHostName(), address.getPort(),
                                LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND, listener, true);
                        next.send(new ServerboundHelloPacket(mc.getUser().getName(), mc.getUser().getProfileId()));
                        transferring = false;
                    });
                });
            } catch (Throwable error) {
                if (WorkshopSession.remote() != this || generation != expectedGeneration) return;
                transferring = false;
                disconnect(connection, new DisconnectionDetails(Component.literal(error.toString())),
                        new net.minecraft.client.gui.screens.DisconnectedScreen(new net.minecraft.client.gui.screens.TitleScreen(),
                                Component.translatable("connect.failed"), Component.literal(error.toString())));
            }
        });
    }
}
