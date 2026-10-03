package ml.pypals.simulatica.simulation.server;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.Difficulty;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import ml.pypals.simulatica.mixin.simulation.LivingEntityDeathSoundInvoker;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 覆写 getEntityCollisions 注入碰撞式边界墙与底部地面
 * - 覆写 blockEvent 播放方块事件声音；覆写 levelEvent / globalLevelEvent 直连客户端
 * - 覆写 broadcastEntityEvent 转发死亡声（event 3，原走区块追踪 viewer 收不到）
 * - 覆写 playSeededSound 两个重载把模拟世界全部声音（生物环境/受伤/脚步/移动、方块放置与破坏等）重放到真实客户端
 */
/**
 * A real {@link ServerLevel} that exists only to run schematic simulations.
 */
public class SimulationLevel extends ServerLevel {
    public final SimulationClock clock = new SimulationClock();
    private final TickRateManager simulationTickRate = new TickRateManager();
    private String projectionName;
    private ServerClockManager projectionClock;
    private WeatherData projectionWeather;
    private final java.util.Set<SoundEvent> playedSounds = new java.util.HashSet<>();
    private final java.util.Set<Integer> playedLevelEvents = new java.util.HashSet<>();

    void beginClientTick() { playedSounds.clear(); playedLevelEvents.clear(); }

    boolean allowLevelEvent(int event) { return clock.target() <= 20 || playedLevelEvents.add(event); }

    boolean allowSound(SoundEvent sound) {
        // ponytail: sample each sound type once per client tick above 20 TPS; add spatial mixing if needed.
        return clock.target() <= 20 || playedSounds.add(sound);
    }

    public String projectionName() { return projectionName; }
    public void setProjectionName(String name) { projectionName = name; }
    private final List<Consumer<BlockPos>> blockChangeListeners = new ArrayList<>();

    public SimulationLevel(SimulationServer server,
                           Executor executor,
                           LevelStorageSource.LevelStorageAccess storage,
                           ServerLevelData levelData,
                           ResourceKey<Level> dimension,
                           LevelStem stem,
                           long seed,
                           List<CustomSpawner> customSpawners,
                           boolean tickTime) {
        super(server, executor, storage, levelData, dimension, stem, false, seed, customSpawners, tickTime);
        // Void simulations keep End physics without creating an unrelated dragon arena.
        // Explicitly summoned or schematic dragons remain normal entities.
        setDragonFight(null);
        projectionClock = getDataStorage().computeIfAbsent(ServerClockManager.TYPE);
        projectionClock.init(server);
        projectionWeather = getDataStorage().computeIfAbsent(WeatherData.TYPE);
        setRainLevel(projectionWeather.isRaining() ? 1 : 0);
        setThunderLevel(projectionWeather.isThundering() ? 1 : 0);
        setEnvironmentAttributes(EnvironmentAttributeSystem.builder()
                .addDefaultLayers(this).build());
    }

    @Override
    public ServerClockManager clockManager() {
        return projectionClock == null ? super.clockManager() : projectionClock;
    }

    @Override
    public WeatherData getWeatherData() {
        return projectionWeather == null ? super.getWeatherData() : projectionWeather;
    }

    @Override
    protected void tickTime() {
        if (projectionClock != null) projectionClock.tick();
        super.tickTime();
    }

    public void setSimulationTime(ResourceKey<ClockTimeMarker> marker) {
        var overworld = registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
        if (!clockManager().moveToTimeMarker(overworld, marker)) {
            throw new IllegalArgumentException("Unknown time marker: " + marker);
        }
    }

    public void setSimulationWeather(boolean rain, boolean thunder) {
        setSimulationWeather(rain ? 0 : 12000, 12000, rain, thunder);
    }

    public void setSimulationWeather(int clearTime, int duration, boolean rain, boolean thunder) {
        var weather = getWeatherData();
        weather.setClearWeatherTime(clearTime);
        weather.setRainTime(duration);
        weather.setThunderTime(duration);
        weather.setRaining(rain);
        weather.setThundering(thunder);
    }

    public void setSimulationDifficulty(Difficulty difficulty) {
        ((PrimaryLevelData) getLevelData()).setDifficulty(difficulty);
    }

    private final List<Consumer<BlockPos>> blockEntityChangeListeners = new ArrayList<>();

    public void addBlockChangeListener(Consumer<BlockPos> listener) {
        this.blockChangeListeners.add(listener);
    }

    public void addBlockEntityChangeListener(Consumer<BlockPos> listener) {
        this.blockEntityChangeListeners.add(listener);
    }

    @Override
    public void blockEntityChanged(@NonNull BlockPos pos) {
        super.blockEntityChanged(pos);

        if (this.blockEntityChangeListeners.isEmpty()) {
            return;
        }
        BlockPos immutable = pos.immutable();
        for (int i = 0; i < this.blockEntityChangeListeners.size(); i++) {
            this.blockEntityChangeListeners.get(i).accept(immutable);
        }
    }

    public void removeBlockChangeListener(Consumer<BlockPos> listener) {
        this.blockChangeListeners.remove(listener);
    }

    /**
     * Overriding {@code setBlock} to forward all block changes directly to the client。
     */
    @Override
    public boolean setBlock(@NonNull BlockPos pos, @NonNull BlockState state, int flags, int recursionLeft) {
        boolean changed = super.setBlock(pos, state, flags, recursionLeft);
        if (!changed || this.blockChangeListeners.isEmpty()) {
            return changed;
        }

        BlockPos immutable = pos.immutable();
        for (int i = 0; i < this.blockChangeListeners.size(); i++) {
            this.blockChangeListeners.get(i).accept(immutable);
        }
        return true;
    }

    /**
     *  There are never any players in this level, so this is useless.
     */
    @Override
    public void sendBlockUpdated(@NonNull BlockPos pos, @NonNull BlockState oldState, @NonNull BlockState newState, int flags) {
    }

    /**
     * Adds collision-only walls and a floor around every simulated region.
     *
     * <p>Only entity movement consults this method, so the boundary stops mobs, items and
     * projectiles physically while staying invisible to block logic, ray tracing and mob
     * pathfinding.</p>
     */
    @Override
    public @NonNull List<VoxelShape> getEntityCollisions(@Nullable Entity entity, @NonNull AABB box) {
        List<VoxelShape> collisions = super.getEntityCollisions(entity, box);

        SimulationServer server = (SimulationServer) this.getServer();
        List<ProjectionBridge> bridges = server.bridges();
        if (bridges.isEmpty()) {
            return collisions;
        }

        AABB query = box.inflate(1.0);
        List<VoxelShape> boundary = null;
        for (ProjectionBridge bridge : bridges) {
            if (bridge.level() != this) {
                continue;
            }
            AABB bounds = bridge.region().simBounds();
            // The test must cover the whole sealed volume (region + wall band up to the ceiling),
            // not just the region: an entity knocked above the region top would otherwise leave
            // the test's range and lose every boundary collision exactly where it could then
            // drift out over the walls. That was the escape wind charges exploited.
            AABB sealed = SimulationBoundary.sealedBounds(bounds);
            if (!query.intersects(sealed)) {
                continue;
            }
            if (boundary == null) {
                boundary = new ArrayList<>();
            }
            SimulationBoundary.collect(bridges, bridge, bounds, query, boundary);
        }

        if (boundary == null) {
            return collisions;
        }
        List<VoxelShape> all = new ArrayList<>(collisions.size() + boundary.size());
        all.addAll(collisions);
        all.addAll(boundary);
        return all;
    }

    /** Each projection advances independently of the real world's tick controls. */
    @Override
    public @NonNull TickRateManager tickRateManager() {
        return simulationTickRate;
    }

    /**
     * The simulation's flat generator gives every chunk {@code THE_VOID} biome, whose mob spawn
     * settings are empty -- natural spawning would always come up empty. Delegate to the real
     * client world instead (sim coordinates equal world coordinates, the region offset is always
     * zero), so a swamp farm spawns swamp mobs, a plains machine spawns plains mobs, and so on.
     */
    @Override
    public @NonNull Holder<Biome> getBiome(@NonNull BlockPos pos) {
        ClientLevel client = Minecraft.getInstance().level;
        return client != null ? client.getBiome(pos) : super.getBiome(pos);
    }

    /**
     * We dont want to save anything.
     */
    @Override
    public void save(@Nullable ProgressListener progressListener, boolean flush, boolean skipSave) {
        super.save(progressListener, flush, true);
    }

    /**
     * Block events (pistons, note blocks, chest lids) would otherwise travel through chunk
     * tracking, which the viewer never registers for, and their sounds would be lost. The state
     * is read here because the real world has air where the projection is.
     */
    @Override
    public void blockEvent(@NonNull BlockPos pos, @NonNull Block block, int eventId, int eventParam) {
        super.blockEvent(pos, block, eventId, eventParam);

        ClientLevel client = Minecraft.getInstance().level;
        if (client != null) {
            SimulationBlockEventSounds.play(client, this, this.getBlockState(pos), pos, eventId, eventParam);
        }
    }

    @Override
    public void globalLevelEvent(int i, @NonNull BlockPos blockPos, int j) {        if (this.getGameRules().get(GameRules.GLOBAL_SOUND_EVENTS) && Minecraft.getInstance().getConnection() != null) {
            if (allowLevelEvent(i)) Minecraft.getInstance().getConnection().handleLevelEvent(new ClientboundLevelEventPacket(i, blockPos, j, true));
        } else {
            this.levelEvent(null, i, blockPos, j);
        }
    }

    @Override
    public void levelEvent(@org.jspecify.annotations.Nullable Entity entity, int i, @NonNull BlockPos blockPos, int j) {
        if(Minecraft.getInstance().getConnection() != null && allowLevelEvent(i)){
            Minecraft.getInstance().getConnection().handleLevelEvent(new ClientboundLevelEventPacket(i, blockPos, j, false));
        }
    }

    /**
     * Every sound in the simulation funnels through {@code playSeededSound}: mob ambience, hurt,
     * step and death sounds, block place/break, doors, portals. On a real server this broadcast
     * reaches players over chunk tracking, which the simulation's viewer never subscribes to, so
     * the whole soundscape was silent. Replaying it on the real client restores it.
     */
    @Override
    public void playSeededSound(@Nullable Entity excluded, double x, double y, double z,
                                Holder<SoundEvent> sound, SoundSource source,
                                float volume, float pitch, long seed) {
        super.playSeededSound(excluded, x, y, z, sound, source, volume, pitch, seed);
        replayOnClient(x, y, z, sound, source, volume, pitch, seed);
    }

    @Override
    public void playSeededSound(@Nullable Entity excluded, @Nullable Entity origin,
                                Holder<SoundEvent> sound, SoundSource source,
                                float volume, float pitch, long seed) {
        super.playSeededSound(excluded, origin, sound, source, volume, pitch, seed);
        if (origin != null) {
            replayOnClient(origin.getX(), origin.getY(), origin.getZ(), sound, source, volume, pitch, seed);
        }
    }

    private void replayOnClient(double x, double y, double z, Holder<SoundEvent> sound,
                                SoundSource source, float volume, float pitch, long seed) {
        ClientLevel client = Minecraft.getInstance().level;
        if (client == null) {
            return;
        }
        LocalPlayer viewer = Minecraft.getInstance().player;
        if (viewer != null && viewer.position().distanceToSqr(x, y, z) > 16384.0) {
            return;
        }
        // ClientLevel.playSeededSound only plays anything when its first argument is the local
        // player (bytecode: it returns immediately otherwise), and that is not a contract worth
        // relying on. playLocalSound is what vanilla's own packet handlers use.
        if (allowSound(sound.value())) client.playLocalSound(x, y, z, sound.value(), source, volume, pitch, false);
    }

    /**
     * Entity event 3 (death) carries the death sound, but the broadcast travels over chunk
     * tracking, which the simulation's viewer is not part of -- a dying mob would vanish in
     * silence. The fall-and-fade animation needs nothing: the projection renders the very
     * same entity object whose deathTime the server is already advancing. Only the sound is
     * replayed here, straight to the client.
     */
    @Override
    public void broadcastEntityEvent(@NonNull Entity entity, byte eventId) {
        super.broadcastEntityEvent(entity, eventId);

        if (eventId != 3 || !(entity instanceof LivingEntity living)) {
            return;
        }
        SimulationServer server = (SimulationServer) this.getServer();
        if (ProjectionBridge.covering(server.bridges(), this, entity.blockPosition()) == null) {
            return;
        }
        ClientLevel client = Minecraft.getInstance().level;
        if (client == null) {
            return;
        }

        LivingEntityDeathSoundInvoker invoker = (LivingEntityDeathSoundInvoker) living;
        net.minecraft.sounds.SoundEvent sound = invoker.simulatica$deathSound();
        if (sound != null && allowSound(sound)) {
            float pitch = (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F;
            client.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), sound,
                    living.getSoundSource(), invoker.simulatica$soundVolume(), pitch, false);
        }
    }
}
