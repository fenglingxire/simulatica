package ml.pypals.simulatica.simulation.server;

import ml.pypals.simulatica.Simulatica;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 模拟生物本地留档：按「放置/区域」把存活实体压缩 NBT 存到 <游戏目录>/simulatica/leftovers/<存档名或服务器地址>/，下次启动模拟时以停止时的状态恢复（优先于原理图原实体），消费后删除。
 */
/**
 * Local persistence for the entities a simulation leaves behind when it stops.
 *
 * <p>One compressed NBT file per region label ({@code placement/region}), keyed by the world
 * the simulation ran in, under {@code <gameDir>/simulatica/leftovers/}. The store is what makes
 * summoned creatures survive a world restart: the projection itself only ever holds the
 * schematic's own entities, so everything the simulation produced is written here on stop and
 * loaded back when the region is simulated again. Files are deleted once consumed.</p>
 */
public final class LeftoverStore {
    private static final ThreadLocal<String> WORLD_OVERRIDE = new ThreadLocal<>();

    private LeftoverStore() {
    }

    /** Storage identity survives renames and separates equal names, regions and dimensions. */
    public static String key(fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement, String region) {
        Minecraft mc = Minecraft.getInstance();
        String identity = currentWorldKey() + "|" + (mc.level == null ? "" : mc.level.dimension().identifier()) + "|" + region;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return placement.getHashId() + "/" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Only migrate a legacy name-based save when its owner is unambiguous and unedited. */
    public static String prepareKey(fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement, String region) {
        String key = key(placement, region);
        if (ml.pypals.simulatica.workshop.EditedPlacementCache.hasEdit(placement)) return key;
        Path legacy = fileFor(placement.getName() + "/" + region), target = fileFor(key);
        long owners = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                .filter(other -> other.getSchematic() != null)
                .flatMap(other -> other.getSchematic().getAreaSizes().keySet().stream()
                        .map(name -> fileFor(other.getName() + "/" + name)))
                .filter(path -> java.util.Objects.equals(path, legacy)).count();
        if (owners == 1 && legacy != null && target != null && Files.exists(legacy) && !Files.exists(target)) {
            try { Files.move(legacy, target); }
            catch (IOException failure) { throw new java.io.UncheckedIOException("Cannot migrate leftover entity save", failure); }
        }
        return key;
    }

    /** Saves the given entities for the region label, replacing any previous save. */
    public static void save(String label, List<Entity> entities, ServerLevel level) {
        ListTag list = new ListTag();
        for (Entity entity : entities) {
            if (entity instanceof EnderDragonPart || entity.isRemoved()) {
                continue;
            }
            try {
                TagValueOutput output =
                        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                if (entity.save(output)) {
                    list.add(output.buildResult());
                }
            } catch (Exception e) {
                Simulatica.LOGGER.warn("[Simulatica] Failed to save leftover entity {}: {}",
                        entity.getType(), e.getMessage());
            }
        }

        Path file = fileFor(label);
        if (file == null) {
            return;
        }
        try {
            if (list.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            root.put("Entities", list);
            NbtIo.writeCompressed(root, file);
            Simulatica.LOGGER.info("[Simulatica] Saved {} leftover entit{} for '{}'",
                    list.size(), list.size() == 1 ? "y" : "ies", label);
        } catch (IOException e) {
            Simulatica.LOGGER.warn("[Simulatica] Could not save leftover entities for '{}': {}", label, e.getMessage());
        }
    }

    /**
     * The UUIDs saved for a label, without loading the entities. Used to keep the schematic's
     * own copy of an entity from overwriting its more recent saved state.
     */
    public static Set<UUID> savedIds(String label) {
        Set<UUID> ids = new HashSet<>();
        Path file = fileFor(label);
        if (file == null || !Files.exists(file)) {
            return ids;
        }

        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            for (int i = 0; i < root.getListOrEmpty("Entities").size(); i++) {
                Optional<int[]> raw = root.getListOrEmpty("Entities").getCompoundOrEmpty(i).getIntArray("UUID");
                if (raw.isPresent() && raw.get().length == 4) {
                    ids.add(UUIDUtil.uuidFromIntArray(raw.get()));
                }
            }
        } catch (IOException e) {
            Simulatica.LOGGER.warn("[Simulatica] Could not read leftover entities for '{}': {}", label, e.getMessage());
        }
        return ids;
    }

    /**
     * Loads the saved entities into the level, skipping any UUID that already exists there
     * (a live leftover retaken by the new bridge, or a copy the schematic just provided).
     * The file is deleted afterwards regardless: its contents now live in the simulation,
     * which will save them again on the next stop.
     */
    public static int load(String label, ServerLevel level, Set<UUID> alreadyPresent) {
        Path file = fileFor(label);
        if (file == null || !Files.exists(file)) {
            return 0;
        }

        int restored = 0;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("Entities");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompoundOrEmpty(i);
                Optional<int[]> raw = tag.getIntArray("UUID");
                if (raw.isPresent() && raw.get().length == 4
                        && alreadyPresent.contains(UUIDUtil.uuidFromIntArray(raw.get()))) {
                    continue;
                }
                try {
                    Entity entity = EntityType.loadEntityRecursive(tag, level,
                            new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.LOAD, false),
                            e -> e);
                    if (entity != null) {
                        level.addFreshEntityWithPassengers(entity);
                        restored++;
                    }
                } catch (Exception e) {
                    Simulatica.LOGGER.warn("[Simulatica] Failed to restore a leftover entity for '{}': {}",
                            label, e.getMessage());
                }
            }
        } catch (IOException e) {
            Simulatica.LOGGER.warn("[Simulatica] Could not read leftover entities for '{}': {}", label, e.getMessage());
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Simulatica.LOGGER.warn("[Simulatica] Could not delete consumed leftover file {}: {}", file, e.getMessage());
        }

        if (restored > 0) {
            Simulatica.LOGGER.info("[Simulatica] Restored {} leftover entit{} for '{}'",
                    restored, restored == 1 ? "y" : "ies", label);
        }
        return restored;
    }

    @Nullable
    public static Path fileFor(String label) {
        String worldKey = WORLD_OVERRIDE.get();
        if (worldKey == null) worldKey = currentWorldKey();
        if (worldKey == null || worldKey.isEmpty()) return null;
        return FabricLoader.getInstance().getGameDir()
                .resolve(Simulatica.MOD_ID).resolve("leftovers")
                .resolve(sanitize(worldKey)).resolve(sanitize(label) + ".nbt");
    }

    @Nullable
    public static String currentWorldKey() {
        Minecraft client = Minecraft.getInstance();
        String worldKey;
        if (client.getSingleplayerServer() != null) {
            worldKey = client.getSingleplayerServer().getWorldData().getLevelName();
        } else if (client.getCurrentServer() != null) {
            worldKey = client.getCurrentServer().ip;
        } else {
            return null;
        }
        return worldKey;
    }

    /** World switches must finish the original simulation's persistence in its original namespace. */
    public static void withWorldKey(@Nullable String worldKey, Runnable action) {
        String previous = WORLD_OVERRIDE.get();
        WORLD_OVERRIDE.set(worldKey == null ? "" : worldKey);
        try { action.run(); }
        finally {
            if (previous == null) WORLD_OVERRIDE.remove(); else WORLD_OVERRIDE.set(previous);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
