package ml.pypals.simulatica.workshop;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** A normal integrated server whose world is disposable and populated before login. */
public final class WorkshopServer extends IntegratedServer {
    private final ResourceKey<Level> dimension;
    private final BlockPos spawn;
    private final Consumer<ServerLevel> initializer;
    private final Path directory;

    private WorkshopServer(Thread thread, LevelStorageSource.LevelStorageAccess storage,
                           PackRepository packs, WorldStem stem, GameRules rules,
                           ResourceKey<Level> dimension, BlockPos spawn,
                           Consumer<ServerLevel> initializer, Path directory) {
        super(thread, Minecraft.getInstance(), storage, packs, stem, Optional.of(rules),
                Minecraft.getInstance().services(), net.minecraft.server.level.progress.LoggingLevelLoadListener.forSingleplayer());
        this.dimension = dimension;
        this.spawn = spawn.immutable();
        this.initializer = initializer;
        this.directory = directory;
    }

    static WorkshopServer start(ResourceKey<Level> dimension, BlockPos spawn, JsonElement sourceType,
                                Consumer<ServerLevel> initializer) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        Path root = mc.gameDirectory.toPath().resolve("simulatica").resolve("workshops");
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "workshop-");
        var storage = LevelStorageSource.createDefault(root).createAccess(directory.getFileName().toString());
        WorldStem loadedStem = null;
        try {
            PackRepository packs = ServerPacksSource.createPackRepository(storage);
            AtomicReference<GameRules> rules = new AtomicReference<>();
            var config = new WorldLoader.InitConfig(
                    new WorldLoader.PackConfig(packs, WorldDataConfiguration.DEFAULT, false, true),
                    Commands.CommandSelection.INTEGRATED, LevelBasedPermissionSet.OWNER);
            var future = WorldLoader.load(config, context -> {
                var types = context.datapackWorldgen().lookupOrThrow(Registries.DIMENSION_TYPE);
                var biome = context.datapackWorldgen().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
                Map<ResourceKey<LevelStem>, LevelStem> stems = new LinkedHashMap<>();
                for (var key : List.of(LevelStem.OVERWORLD, LevelStem.NETHER, LevelStem.END)) {
                    var type = key == LevelStem.NETHER ? BuiltinDimensionTypes.NETHER
                            : key == LevelStem.END ? BuiltinDimensionTypes.END : BuiltinDimensionTypes.OVERWORLD;
                    var settings = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), biome, List.of());
                    settings.updateLayers();
                    stems.put(key, new LevelStem(types.getOrThrow(type), new FlatLevelSource(settings)));
                }
                var none = new MappedRegistry<LevelStem>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze();
                var dimensions = new WorldDimensions(stems).bake(none);
                var settings = new LevelSettings("Simulatica workshop", GameType.CREATIVE,
                        LevelSettings.DifficultySettings.DEFAULT, true, context.dataConfiguration());
                var data = new PrimaryLevelData(settings, dimensions.specialWorldProperty(), dimensions.lifecycle());
                data.setSpawn(new LevelData.RespawnData(GlobalPos.of(dimension, spawn), 0, 0));
                rules.set(new GameRules(context.dataConfiguration().enabledFeatures()));
                var gen = new WorldGenSettings(new WorldOptions(0, false, false), new WorldDimensions(dimensions.dimensions()));
                return new WorldLoader.DataLoadOutput<>(
                        new LevelDataAndDimensions.WorldDataAndGenSettings(data, gen), dimensions.dimensionsRegistryAccess());
            }, WorldStem::new, Util.backgroundExecutor(), mc);
            mc.managedBlock(() -> {
                mc.packetProcessor().processQueuedPackets();
                WorkshopSession.tick();
                return future.isDone();
            });
            WorldStem stem = future.get();
            loadedStem = stem;
            // WorldLoader has now applied postponed tags/components; its earlier lookup had temporary owners.
            var completedRegistries = stem.registries().compositeAccess();
            var sourceKey = dimension.equals(Level.NETHER) ? BuiltinDimensionTypes.NETHER
                    : dimension.equals(Level.END) ? BuiltinDimensionTypes.END : BuiltinDimensionTypes.OVERWORLD;
            var localType = DimensionType.NETWORK_CODEC.encodeStart(completedRegistries.createSerializationContext(JsonOps.INSTANCE),
                    completedRegistries.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(sourceKey).value()).getOrThrow();
            if (!sourceType.equals(localType)) {
                ml.pypals.simulatica.Simulatica.LOGGER.error("Unsupported workshop source dimension {}: source={}, local={}", dimension, sourceType, localType);
                throw new UnsupportedOperationException("工作间暂不支持自定义维度类型：" + dimension);
            }
            storage.saveDataTag(stem.worldDataAndGenSettings().data());
            return MinecraftServer.spin(thread -> new WorkshopServer(thread, storage, packs, stem,
                    rules.get(), dimension, spawn, initializer, directory));
        } catch (Throwable failure) {
            if (loadedStem != null) {
                try { loadedStem.close(); }
                catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            try {
                storage.close();
                WorkshopSession.deleteDisposableDirectory(directory);
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                ml.pypals.simulatica.Simulatica.LOGGER.error("Failed workshop startup retained resources at {}", directory, cleanupFailure);
            }
            throw failure;
        }
    }

    @Override protected boolean initServer() {
        if (!super.initServer()) return false;
        // A void workshop has no automatic End arena; explicitly summoned dragons still tick normally.
        Objects.requireNonNull(getLevel(Level.END)).setDragonFight(null);
        ServerLevel target = Objects.requireNonNull(getLevel(dimension), "Workshop dimension missing");
        initializer.accept(target);
        setRespawnData(new LevelData.RespawnData(GlobalPos.of(dimension, spawn), 0, 0));
        return true;
    }

    @Override public LevelBasedPermissionSet getProfilePermissions(NameAndId player) {
        return LevelBasedPermissionSet.OWNER;
    }

    @Override protected void onServerCrash(net.minecraft.CrashReport report) {
        ml.pypals.simulatica.Simulatica.LOGGER.error("Workshop server crashed: {}", report.getFriendlyReport(net.minecraft.ReportType.CRASH));
    }

    public ResourceKey<Level> sourceDimension() { return dimension; }
    public BlockPos spawnPosition() { return spawn; }
    public Path directory() { return directory; }
}
