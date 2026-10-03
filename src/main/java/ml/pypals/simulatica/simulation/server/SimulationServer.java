package ml.pypals.simulatica.simulation.server;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.serialization.Lifecycle;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.mixin.simulation.MinecraftServerLevelsAccessor;
import ml.pypals.simulatica.mixin.simulation.MinecraftServerTickAccessor;
import ml.pypals.simulatica.mixin.simulation.ServerLevelEntityManagerAccessor;
import net.fabricmc.fabric.api.resource.v1.DataResourceStore;
import net.fabricmc.fabric.impl.resource.FabricDataResourceStoreHolder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SystemReport;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 26.2 构造与启动适配：MinecraftServer 构造器新增 Optional<GameRules>/NotificationManager、WorldStem 第 4 分量改 WorldDataAndGenSettings、LevelSettings 变 record、PrimaryLevelData 参数调整
 * - attach 接受 preserved UUID 集合并在复制后加载 LeftoverStore 留档；新增 moveRegion / republishEntities / detachAll
 */
public final class SimulationServer extends MinecraftServer {

    private static final String SCRATCH_DIR = Simulatica.MOD_ID;
    private static final String SCRATCH_LEVEL_ID = "simulation";
    private static final long SEED = 0L;
    public static final int TICKING_MARGIN_CHUNKS = 2;

    private static final int MAX_CHUNK_TASKS_PER_TICK = 512;

    // How long to wait for a freshly force-loaded region to reach a ticking state before giving up.
    private static final int MAX_PROMOTION_TICKS = 200;
    private static final long TICK_BUDGET_NANOS = 50L * 1000L * 1000L;

    @Nullable
    private static SimulationServer instance;

    private final LevelStorageSource.LevelStorageAccess storage;
    private final List<ProjectionBridge> bridges = new ArrayList<>();
    private int nextProjectionId;
    private int nextTickingLevel;

    private final ReloadableServerResources dataPackResources;
    private final LocalSampleLogger tickTimeLogger = new LocalSampleLogger(1);

    private SimulationServer(Thread thread,
                             LevelStorageSource.LevelStorageAccess storage,
                             PackRepository packRepository,
                             WorldStem worldStem,
                             Services services,
                             GameRules gameRules) {
        super(thread, storage, packRepository, worldStem, Optional.of(gameRules), Proxy.NO_PROXY,
                Minecraft.getInstance().getFixerUpper(), services, NoopLevelLoadListener.INSTANCE,
                false, new NotificationManager());
        this.storage = storage;
        this.dataPackResources = worldStem.dataPackResources();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nullable
    public static SimulationServer getRunning() {
        return instance;
    }

    //Boots the shared simulation server or returns the running one.
    public static SimulationServer getOrCreate() throws Exception {
        if (instance != null) {
            return instance;
        }

        Path root = FabricLoader.getInstance().getGameDir().resolve(SCRATCH_DIR);
        deleteRecursively(root.resolve(SCRATCH_LEVEL_ID));
        Files.createDirectories(root);

        LevelStorageSource.LevelStorageAccess storage =
                LevelStorageSource.createDefault(root).createAccess(SCRATCH_LEVEL_ID);

        SimulationServer server;
        try {
            PackRepository packRepository = ServerPacksSource.createPackRepository(storage);
            AtomicReference<GameRules> gameRules = new AtomicReference<>();
            WorldStem worldStem = loadWorldStem(packRepository, gameRules);
            Services services = Services.create(new YggdrasilAuthenticationService(Proxy.NO_PROXY), root.toFile());

            server = new SimulationServer(Thread.currentThread(), storage, packRepository, worldStem, services,
                    gameRules.get());
            server.createSimulationLevels();
        } catch (Exception e) {
            storage.close();
            throw e;
        }

        instance = server;
        fixCarpetSpawnTries();
        Simulatica.LOGGER.info("[Simulatica] Simulation server started with dimensions {}",
                server.levelKeys().stream().map(k -> k.identifier().toString()).toList());
        return server;
    }

    /**
     * Carpet's {@code SpawnReporter.spawn_tries} is only populated by
     * {@code CarpetServer.onServerLoaded}, which never runs on a client in multiplayer. Its
     * {@code NaturalSpawnerMixin.spawnMultipleTimes} redirect reads that map without a null check,
     * so the moment the simulation's natural-spawning phase runs it throws an NPE that unwinds out
     * of {@code ServerLevel.tick} every tick -- silently killing entity ticking, block-entity
     * ticking and block events. Populating the map here restores the full tick on multiplayer.
     */
    private static void fixCarpetSpawnTries() {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) {
            return;
        }
        try {
            Object raw = Class.forName("carpet.utils.SpawnReporter").getField("spawn_tries").get(null);
            if (!(raw instanceof Map<?, ?>)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<MobCategory, Integer> tries = (Map<MobCategory, Integer>) raw;
            if (!tries.isEmpty()) {
                return;
            }
            for (MobCategory category : MobCategory.values()) {
                tries.put(category, 1);
            }
            Simulatica.LOGGER.info("[Simulatica] Populated carpet spawn_tries for {} mob categories",
                    tries.size());
        } catch (ClassNotFoundException e) {
            // Carpet present but not on this classpath; nothing to do.
        } catch (Exception e) {
            Simulatica.LOGGER.warn("[Simulatica] Failed to populate carpet spawn_tries: {}", e.toString());
        }
    }

    public static void shutdown() {
        SimulationServer server = instance;
        if (server == null) {
            return;
        }
        instance = null;
        server.bridges.clear();

        for (ServerLevel level : server.getAllLevels()) {
            try {
                level.close();
            } catch (IOException e) {
                Simulatica.LOGGER.warn("[Simulatica] Failed to close simulation level {}",
                        level.dimension().identifier(), e);
            }
        }
        try {
            server.storage.close();
        } catch (IOException e) {
            Simulatica.LOGGER.warn("[Simulatica] Failed to release the scratch world lock", e);
        }
        deleteRecursively(FabricLoader.getInstance().getGameDir().resolve(SCRATCH_DIR).resolve(SCRATCH_LEVEL_ID));
        Simulatica.LOGGER.info("[Simulatica] Simulation server stopped");
    }

    // =========================================================================
    // Bootstrap
    // =========================================================================

    /**
     * Built the way the integrated server builds its repository, not with
     * {@code createVanillaTrustedRepository()}: Fabric only injects mod data packs into a repository
     * that already contains a world-level {@code FolderRepositorySource}, and the vanilla-trusted
     * one has none. Without it the registries come out with no modded entries。
     * Everything is read locally, so this works in
     * multiplayer too.
     */
    private static WorldStem loadWorldStem(PackRepository packRepository, AtomicReference<GameRules> gameRulesOut) throws Exception {
        WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(
                new WorldLoader.PackConfig(packRepository, WorldDataConfiguration.DEFAULT, false, true),
                Commands.CommandSelection.INTEGRATED,
                LevelBasedPermissionSet.GAMEMASTER);

        CompletableFuture<WorldStem> future = WorldLoader.load(
                initConfig,
                context -> {
                    Registry<LevelStem> none =
                            new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze();
                    WorldDimensions.Complete dimensions =
                            VoidDimensions.create(context.datapackWorldgen()).bake(none);

                    WorldData worldData = getWorldData(context, dimensions);
                    gameRulesOut.set(new GameRules(context.dataConfiguration().enabledFeatures()));

                    // 26.x wraps the world data together with the gen settings in the stem
                    WorldGenSettings genSettings = new WorldGenSettings(
                            new WorldOptions(SEED, false, false), new WorldDimensions(dimensions.dimensions()));
                    return new WorldLoader.DataLoadOutput<>(
                            new LevelDataAndDimensions.WorldDataAndGenSettings(worldData, genSettings),
                            dimensions.dimensionsRegistryAccess());
                },
                WorldStem::new,
                Util.backgroundExecutor(),
                Minecraft.getInstance());

        Minecraft.getInstance().managedBlock(future::isDone);
        return future.get();
    }

    private static @NonNull WorldData getWorldData(WorldLoader.DataLoadContext context, WorldDimensions.Complete dimensions) {
        LevelSettings settings =
                new LevelSettings("Simulatica", GameType.CREATIVE,
                        LevelSettings.DifficultySettings.DEFAULT, true, context.dataConfiguration());

        return new PrimaryLevelData(settings, dimensions.specialWorldProperty(), dimensions.lifecycle());
    }

    /**
     * Builds one {@link SimulationLevel} per dimension.
     *
     * <p>Do not use {@code createLevels()}, that also searches for an initial spawn,
     * and do other things that we don't want at all.</p>
     */
    private void createSimulationLevels() {
        this.setPlayerList(new SimulationPlayerList(this, this.storage));

        WorldData worldData = this.getWorldData();
        ServerLevelData overworldData = worldData.overworldData();
        Registry<LevelStem> stems = this.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        long seed = BiomeManager.obfuscateSeed(SEED);
        Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerLevelsAccessor) (Object) this).simulatica$levels();

        // The overworld has to come first. Other dimensions will use it's data.
        SimulationLevel overworld = new SimulationLevel(this, Util.backgroundExecutor(), this.storage,
                overworldData, Level.OVERWORLD, stems.getValueOrThrow(LevelStem.OVERWORLD),
                seed, List.of(), true);
        levels.put(Level.OVERWORLD, overworld);

        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : stems.entrySet()) {
            if (entry.getKey().equals(LevelStem.OVERWORLD)) {
                continue;
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, entry.getKey().identifier());
            levels.put(dimension, new SimulationLevel(this, Util.backgroundExecutor(), this.storage,
                    new DerivedLevelData(worldData, overworldData), dimension, entry.getValue(),
                    seed, List.of(), false));
        }

        for (ServerLevel level : this.getAllLevels()) {
            SimulationLevel simulationLevel = (SimulationLevel) level;
            listen(simulationLevel);
        }
    }

    private void listen(SimulationLevel level) {
        level.addBlockChangeListener(pos -> onBlockChanged(level, pos));
        level.addBlockEntityChangeListener(pos -> onBlockEntityChanged(level, pos));
    }

    /** One vanilla world per placement, with the source dimension's physical properties. */
    public SimulationLevel createProjectionLevel(ResourceKey<Level> sourceDimension, String name) {
        Registry<LevelStem> stems = registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        LevelStem stem = stems.getValue(ResourceKey.create(Registries.LEVEL_STEM, sourceDimension.identifier()));
        if (stem == null) stem = stems.getValueOrThrow(LevelStem.OVERWORLD);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath(Simulatica.MOD_ID, "projection/" + nextProjectionId++));
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
                LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
        PrimaryLevelData data = new PrimaryLevelData(settings, PrimaryLevelData.SpecialWorldProperty.FLAT, Lifecycle.stable());
        SimulationLevel level = new SimulationLevel(this, Util.backgroundExecutor(), storage, data, key, stem,
                BiomeManager.obfuscateSeed(SEED), List.of(), true);
        level.setProjectionName(name);
        listen(level);
        ((MinecraftServerLevelsAccessor) (Object) this).simulatica$levels().put(key, level);
        return level;
    }

    // =========================================================================
    // Projection bridges
    // =========================================================================

    /**
     * Maps a box into the simulation level and copies the projection into it.
     * The box is laid out in its own region and force loaded.
     */
    /**
     * @param preserved UUIDs of live leftovers the new bridge retakes; they must not be
     *                  duplicated out of the projection.
     */
    public ProjectionBridge attach(ResourceKey<Level> dimension, BlockPos worldMin, BlockPos worldMax,
                                   String label, Set<UUID> preserved) {
        return attach(levelFor(dimension), worldMin, worldMax, label, preserved);
    }

    public ProjectionBridge attach(SimulationLevel level, BlockPos worldMin, BlockPos worldMax,
                                   String label, Set<UUID> preserved) {
        return attach(level, worldMin, worldMax, label, preserved, label);
    }

    public ProjectionBridge attach(SimulationLevel level, BlockPos worldMin, BlockPos worldMax,
                                   String label, Set<UUID> preserved, String storageKey) {
        ResourceKey<Level> dimension = level.dimension();
        SimulationRegion region = this.allocate(dimension, worldMin, worldMax, label);

        ChunkPos min = region.simChunkMin();
        ChunkPos max = region.simChunkMax();
        this.force(level, region);
        this.pumpUntilTicking(level, min, max);

        // The store's state is newer than the schematic's, so its entities win the copy too.
        Set<UUID> saved = LeftoverStore.savedIds(storageKey);
        Set<UUID> present = new HashSet<>(preserved);
        present.addAll(saved);

        ProjectionBridge bridge = new ProjectionBridge(level, region, label, storageKey);
        int copied = bridge.copyIn(present);
        this.bridges.add(bridge);
        bridge.setViewer(SimulationViewer.create(this, level, region.simBounds().getCenter(), bridge::onPacket));

        // Saved entities are not in the level yet -- only live leftovers and fresh copies are.
        present.removeAll(saved);
        for (UUID uuid : saved) if (level.getEntity(uuid) != null) present.add(uuid);
        LeftoverStore.load(storageKey, level, present);

        if (copied == 0) {
            Simulatica.LOGGER.warn("[Simulatica] Attached '{}' but copied no blocks, is the projection loaded?",
                    label);
        } else {
            Simulatica.LOGGER.info("[Simulatica] Attached '{}' at [{}, {}]..[{}, {}], {} block(s) copied",
                    label, min.x(), min.z(), max.x(), max.z(), copied);
        }
        return bridge;
    }

    /**
     * Drives the chunk pipeline until every chunk of the region reports as ticking.
     *
     * <p>Chunk promotion is asynchronous, and a region whose chunks have not been promoted looks
     * exactly like a working one that happens to be doing nothing, scheduled ticks queue up and
     * are never run. Better to spend the work here than to hand back a region that silently does not
     * simulate.</p>
     */
    private void pumpUntilTicking(SimulationLevel level, ChunkPos min, ChunkPos max) {
        for (int attempt = 0; attempt < MAX_PROMOTION_TICKS; attempt++) {
            if (allTicking(level, min, max)) {
                return;
            }

            grantTaskBudget();
            this.runAllTasks();
            drainChunkSource(level);
            level.getChunkSource().tick(() -> true, false);
            ((ServerLevelEntityManagerAccessor) level).simulatica$entityManager().tick();
        }
        Simulatica.LOGGER.warn("[Simulatica] Region [{}, {}]..[{}, {}] did not become ticking within {} ticks",
                min.x(), min.z(), max.x(), max.z(), MAX_PROMOTION_TICKS);
    }

    private static boolean allTicking(SimulationLevel level, ChunkPos min, ChunkPos max) {
        for (int cx = min.x(); cx <= max.x(); cx++) {
            for (int cz = min.z(); cz <= max.z(); cz++) {
                long key = ChunkPos.pack(cx, cz);
                if (!level.areEntitiesLoaded(key) || !level.getChunkSource().isPositionTicking(key)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Places a region in the simulation level at the coordinates it already occupies in the world.
     * A placement too close to one already running is refused rather than left to interfere with it.
     */
    private SimulationRegion allocate(ResourceKey<Level> dimension, BlockPos worldMin, BlockPos worldMax, String label) {
        SimulationRegion region = new SimulationRegion(dimension, worldMin, worldMax, 0, 0);

        // Adjacent regions are allowed to reach into each other -- two machines side by side
        // interacting is the same thing the projection already shows, and it is useful. Sharing
        // space is not: the two would overwrite each other's blocks.
        for (ProjectionBridge existing : this.bridges) {
            if (existing.region().isWithin(region, 0)) {
                throw new IllegalStateException(
                        "Region overlaps the already-simulated '" + existing.label() + "'");
            }
        }
        return region;
    }

    /** Removes one region, clears its slot and releases its chunks. */
    public void detach(ProjectionBridge bridge) {

        if (!this.bridges.remove(bridge)) {
            return;
        }
        bridge.clear();
        bridge.setViewer(null);

        SimulationLevel level = this.levelFor(bridge.region().dimension());
        this.release(level, bridge.region());
    }

    /**
     * Moves a running region without resetting it.
     *
     * <p>The destination has to be loaded and ticking before anything is carried over. </p>
     */
    public void moveRegion(ProjectionBridge bridge, BlockPos worldMin, BlockPos worldMax) {
        moveRegions(java.util.Map.of(bridge, new SimulationRegion(bridge.region().dimension(), worldMin, worldMax, 0, 0)));
    }

    /** Prepare every destination, then migrate the placement from a single snapshot. */
    public void moveRegions(java.util.Map<ProjectionBridge, SimulationRegion> moves) {
        java.util.Map<ProjectionBridge, SimulationRegion> sources = new java.util.LinkedHashMap<>();
        moves.forEach((bridge, target) -> {
            sources.put(bridge, bridge.region());
            force(bridge.level(), target);
            pumpUntilTicking(bridge.level(), target.simChunkMin(), target.simChunkMax());
        });
        ProjectionBridge.translateAll(moves);
        moves.forEach((bridge, target) -> bridge.setViewer(
                SimulationViewer.create(this, bridge.level(), target.simBounds().getCenter(), bridge::onPacket)));
        sources.forEach((bridge, source) -> release(bridge.level(), source));
    }

    private void force(SimulationLevel level, SimulationRegion region) {
        forEachChunk(region, (cx, cz) -> level.setChunkForced(cx, cz, true));
    }

    /**
     * Drops the forced tickets a region no longer needs.
     *
     * <p>Skips anything another attached region still covers. Two regions that meet inside one chunk
     * both force it, and there is only one ticket to remove -- releasing it because one of them
     * moved away would unload the chunk out from under the other.</p>
     */
    private void release(SimulationLevel level, SimulationRegion region) {
        forEachChunk(region, (cx, cz) -> {
            if (!requiredByAttachedRegion(level, cx, cz)) {
                level.setChunkForced(cx, cz, false);
            }
        });
    }

    private boolean requiredByAttachedRegion(SimulationLevel level, int chunkX, int chunkZ) {
        for (int i = 0; i < this.bridges.size(); i++) {
            SimulationRegion other = this.bridges.get(i).region();
            if (this.bridges.get(i).level() != level) continue;
            ChunkPos min = other.simChunkMin();
            ChunkPos max = other.simChunkMax();
            if (chunkX >= min.x() - TICKING_MARGIN_CHUNKS && chunkX <= max.x() + TICKING_MARGIN_CHUNKS
                    && chunkZ >= min.z() - TICKING_MARGIN_CHUNKS && chunkZ <= max.z() + TICKING_MARGIN_CHUNKS) {
                return true;
            }
        }
        return false;
    }

    private static void forEachChunk(SimulationRegion region, ChunkConsumer action) {
        ChunkPos min = region.simChunkMin();
        ChunkPos max = region.simChunkMax();
        for (int cx = min.x() - TICKING_MARGIN_CHUNKS; cx <= max.x() + TICKING_MARGIN_CHUNKS; cx++) {
            for (int cz = min.z() - TICKING_MARGIN_CHUNKS; cz <= max.z() + TICKING_MARGIN_CHUNKS; cz++) {
                action.accept(cx, cz);
            }
        }
    }

    @FunctionalInterface
    private interface ChunkConsumer {
        void accept(int chunkX, int chunkZ);
    }

    public void detachAll() {
        for (ProjectionBridge bridge : new ArrayList<>(this.bridges)) {
            this.detach(bridge);
        }
    }

    public List<ProjectionBridge> bridges() {
        return this.bridges;
    }

    private void onBlockChanged(SimulationLevel level, BlockPos sim) {
        ProjectionBridge bridge = ProjectionBridge.covering(this.bridges, level, sim);
        if (bridge != null) {
            bridge.onBlockChanged(sim);
        }
    }

    private void onBlockEntityChanged(SimulationLevel level, BlockPos sim) {
        ProjectionBridge bridge = ProjectionBridge.covering(this.bridges, level, sim);
        if (bridge != null) {
            bridge.onBlockEntityChanged(sim);
        }
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    public void tickSimulation() {
        for (ServerLevel level : getAllLevels()) ((SimulationLevel) level).beginClientTick();
        grantTaskBudget();
        this.runAllTasks();
        for (ServerLevel level : this.getAllLevels()) {
            if (((SimulationLevel) level).projectionName() != null) continue;
            drainChunkSource(level);
            level.tick(() -> true);
        }
        List<SimulationLevel> ticking = this.bridges.stream().map(ProjectionBridge::level)
                .filter(level -> level.projectionName() != null).distinct().toList();
        long now = System.nanoTime();
        long deadline = System.nanoTime() + 10_000_000L;
        for (SimulationLevel level : ticking) level.clock.accrue(now);
        int idle = 0;
        while (!ticking.isEmpty() && idle < ticking.size() && System.nanoTime() < deadline) {
            SimulationLevel level = ticking.get(Math.floorMod(nextTickingLevel++, ticking.size()));
            if (!level.clock.due()) { idle++; continue; }
            idle = 0;
            grantTaskBudget();
            drainChunkSource(level);
            level.tickRateManager().tick();
            level.tick(() -> true);
            level.clock.advanced();
        }
        for (int i = 0; i < this.bridges.size(); i++) {
            this.bridges.get(i).syncToProjection();
        }
    }

    public void suspendClocks() {
        for (ServerLevel level : getAllLevels()) ((SimulationLevel) level).clock.suspend();
    }

    /**
     * Re-registers every simulated entity with the projection.
     *
     * <p>Litematica drops a chunk's entities whenever it rebuilds it, so anything the simulation
     * owns has to be handed back or it blinks out until the next change.</p>
     */
    public void republishEntities() {
        for (ProjectionBridge bridge : this.bridges) {
            bridge.publishEntities();
        }
    }

    public boolean isSimulatedChunk(int chunkX, int chunkZ) {
        for (ProjectionBridge bridge : this.bridges) {
            SimulationRegion region = bridge.region();
            ChunkPos min = region.simChunkMin();
            ChunkPos max = region.simChunkMax();
            if (chunkX >= min.x() && chunkX <= max.x() && chunkZ >= min.z() && chunkZ <= max.z()) {
                return true;
            }
        }
        return false;
    }

    public boolean isSimulatedChunk(SimulationLevel level, int chunkX, int chunkZ) {
        return bridges.stream().anyMatch(bridge -> bridge.level() == level
                && chunkX >= bridge.region().simChunkMin().x() && chunkX <= bridge.region().simChunkMax().x()
                && chunkZ >= bridge.region().simChunkMin().z() && chunkZ <= bridge.region().simChunkMax().z());
    }

    private void grantTaskBudget() {
        MinecraftServerTickAccessor budget = (MinecraftServerTickAccessor) (Object) this;
        budget.simulatica$setTickCount(budget.simulatica$getTickCount() + 1);
        budget.simulatica$setNextTickTimeNanos(Util.getNanos() + TICK_BUDGET_NANOS);
    }

    /** Bounded so a task that keeps queueing more work cannot hang the client thread. */
    private static void drainChunkSource(ServerLevel level) {
        for (int i = 0; i < MAX_CHUNK_TASKS_PER_TICK && level.getChunkSource().pollTask(); i++) {
            // pollTask does the work; the loop only bounds it.
        }
    }

    /** The level for a dimension, falling back to the overworld when that dimension is absent. */
    public SimulationLevel levelFor(ResourceKey<Level> dimension) {
        ServerLevel level = this.getLevel(dimension);
        return (SimulationLevel) (level != null ? level : this.overworld());
    }

    // =========================================================================
    // MinecraftServer plumbing
    // =========================================================================

    @Override
    protected boolean initServer() {
        // No.
        return true;
    }

    @Override
    public @NonNull LevelBasedPermissionSet operatorUserPermissions() {
        return LevelBasedPermissionSet.GAMEMASTER;
    }

    @Override
    public @NonNull PermissionSet getFunctionCompilationPermissions() {
        return LevelBasedPermissionSet.GAMEMASTER;
    }

    @Override
    public boolean shouldRconBroadcast() {
        return false;
    }

    @Override
    protected @NonNull LocalSampleLogger getTickTimeLogger() {
        return this.tickTimeLogger;
    }

    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }

    @Override
    public @NonNull SystemReport fillServerSystemReport(SystemReport systemReport) {
        systemReport.setDetail("Type", "Simulatica simulation server");
        return systemReport;
    }

    @Override
    public boolean isDedicatedServer() {
        return false;
    }

    @Override
    public int getRateLimitPacketsPerSecond() {
        return 0;
    }

    @Override
    public int getCommandSpamThresholdSeconds() {
        return 0;
    }

    @Override
    public int getChatSpamThresholdSeconds() {
        return 0;
    }

    @Override
    public boolean useNativeTransport() {
        return false;
    }

    @Override
    public boolean isPublished() {
        return false;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(@NonNull NameAndId nameAndId) {
        return false;
    }

    @Override
    public int getMaxPlayers() {
        return 0;
    }

    @Override
    public <T> @NonNull T getOrThrow(DataResourceStore.@NonNull Key<T> key) {
        return ((FabricDataResourceStoreHolder) this.dataPackResources).fabric$getDataResourceStore().getOrThrow(key);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // :(
                }
            });
        } catch (IOException e) {
            Simulatica.LOGGER.warn("[Simulatica] Could not clear the scratch world at {}", path, e);
        }
    }

    private static final class NoopLevelLoadListener implements LevelLoadListener {
        static final NoopLevelLoadListener INSTANCE = new NoopLevelLoadListener();

        @Override
        public void start(@NonNull Stage stage, int i) {}

        @Override
        public void update(@NonNull Stage stage, int i, int j) {}

        @Override
        public void finish(@NonNull Stage stage) {}

        @Override
        public void updateFocus(@NonNull ResourceKey<Level> resourceKey, @NonNull ChunkPos chunkPos) {}
    }
}
