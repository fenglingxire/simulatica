package ml.pypals.simulatica.workshop;

import com.google.gson.JsonObject;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.SchematicPlacingUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.StringUtils;
import ml.pypals.simulatica.mixin.workshop.WorkshopPlacementAccessor;
import ml.pypals.simulatica.mixin.workshop.WorkshopPlacementManagerInvoker;
import ml.pypals.simulatica.mixin.workshop.WorkshopEntitySectionAccessor;
import ml.pypals.simulatica.mixin.simulation.ServerLevelEntityManagerAccessor;
import ml.pypals.simulatica.simulation.SimulationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import java.util.*;

/** A detached snapshot. Only create/commit touch client-owned Litematica objects. */
public final class WorkshopEdit {
    private static final Map<ServerLevel, WorkshopEdit> TRACKED = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ThreadLocal<Integer> PLAYER_MUTATIONS = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> GRASS_TICKS = ThreadLocal.withInitial(() -> 0);
    private final SchematicPlacement source;
    private final LitematicaSchematic original;
    private final CompoundTag originalData;
    private final int originalGeneration;
    private final ResourceKey<Level> dimension;
    private final String worldKey;
    private final BlockPos spawn;
    private final BlockPos origin;
    private final Mirror mirror;
    private final Rotation rotation;
    private final List<Region> regions = new ArrayList<>();
    private final Map<String, JsonObject> settings = new LinkedHashMap<>();
    private final Map<String, JsonObject> baselineSettings = new LinkedHashMap<>();
    private final boolean originalRendering;
    private final boolean originalEnabled;
    private final Map<BlockPos, Cell> initial = new LinkedHashMap<>();
    private final List<CompoundTag> initialEntities = new ArrayList<>();
    private final List<ScheduledTick<Block>> initialBlockTicks = new ArrayList<>();
    private final List<ScheduledTick<Fluid>> initialFluidTicks = new ArrayList<>();
    private final Set<BlockPos> dirty = new HashSet<>();
    private final Set<BlockPos> platform = new HashSet<>();
    private final Map<BlockPos, BlockState> movingPlatform = new HashMap<>();
    private final Map<ServerLevel, Set<Long>> forced = new IdentityHashMap<>();
    private final Map<String, Set<BlockPos>> originalVoids = new HashMap<>();
    private final Map<ResourceKey<Level>, Set<BlockPos>> foreignChanges = new HashMap<>();
    private ServerLevel local;
    private boolean initializing;
    private BlockPos min;
    private BlockPos max;
    private Result committedBefore;
    private Result committedResult;
    private Runnable undoCache;

    public record Result(LitematicaSchematic schematic, Map<String, JsonObject> regions) {}
    private record Cell(BlockState state, CompoundTag nbt) {}
    private record Region(String name, BlockPos pos, BlockPos size, Mirror mirror, Rotation rotation, boolean enabled) {
        BlockPos minimumOffset() {
            return PositionUtils.getMinCorner(BlockPos.ZERO, PositionUtils.getRelativeEndPositionFromAreaSize(size));
        }
    }
    public static final class DisabledRegionConflict extends IllegalStateException {
        private final List<String> regions;
        DisabledRegionConflict(Collection<String> regions) {
            super("新增内容位于关闭的子区域，请先启用：" + String.join("、", regions));
            this.regions = List.copyOf(regions);
        }
        public List<String> regions() { return regions; }
    }

    public static WorkshopEdit create(SchematicPlacement placement) {
        return new WorkshopEdit(Objects.requireNonNull(placement));
    }

    private WorkshopEdit(SchematicPlacement placement) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("请先进入世界。");
        source = placement;
        original = placement.getSchematic();
        preflightOriginal(original);
        originalGeneration = EditedPlacementCache.generation(original);
        originalData = original.writeToNBT().copy();
        dimension = mc.level.dimension();
        worldKey = currentWorldKey();
        origin = placement.getOrigin();
        mirror = placement.getMirror();
        rotation = placement.getRotation();
        originalRendering = placement.isRenderingEnabled();
        originalEnabled = placement.isEnabled();
        LitematicaSchematic detached = new LitematicaSchematic(original.getFile(), originalData.copy(), FileType.LITEMATICA_SCHEMATIC);
        var simulations = SimulationManager.getInstance().getSimulations(placement);
        var ownedLevel = SimulationManager.getInstance().projectionLevel(placement);
        var projection = SchematicWorldHandler.getSchematicWorld();
        validateProjection(placement, projection);
        Set<UUID> entityIds = new HashSet<>();
        for (String name : new TreeSet<>(detached.getAreaPositions().keySet())) {
            SubRegionPlacement sub = placement.getRelativeSubRegionPlacement(name);
            if (sub == null) throw new IllegalStateException("缺少子区域放置：" + name);
            settings.put(name, sub.toJson().deepCopy());
            baselineSettings.put(name, sub.toJson().deepCopy());
            Region region = new Region(name, sub.getPos(), detached.getAreaSize(name), sub.getMirror(), sub.getRotation(), sub.isEnabled());
            regions.add(region);
            if (!region.enabled()) continue;
            var bridge = simulations == null ? null : simulations.get(name);
            var container = detached.getSubRegionContainer(name);
            BlockPos offset = region.minimumOffset();
            for (int y = 0; y < Math.abs(region.size().getY()); y++) {
                for (int z = 0; z < Math.abs(region.size().getZ()); z++) {
                    for (int x = 0; x < Math.abs(region.size().getX()); x++) {
                        BlockPos index = new BlockPos(x, y, z);
                        BlockPos world = blockToWorld(region, index.offset(offset));
                        expand(world);
                        if (container.get(x, y, z).is(Blocks.STRUCTURE_VOID)) { originalVoids.computeIfAbsent(name, n -> new HashSet<>()).add(world); continue; }
                        BlockState state;
                        CompoundTag nbt = null;
                        if (bridge != null) {
                            BlockPos sim = bridge.toSim(world);
                            state = bridge.level().getBlockState(sim);
                            BlockEntity be = bridge.level().getBlockEntity(sim);
                            if (be != null) nbt = saveBlockEntity(be, bridge.level());
                        } else if (projection != null) {
                            state = projection.getBlockState(world);
                            BlockEntity be = projection.getBlockEntity(world);
                            if (be != null) nbt = saveBlockEntity(be, projection);
                        } else {
                            state = stateToWorld(region, container.get(x, y, z));
                            nbt = MasaNbt.tag(detached.getBlockEntityMapForRegion(name).get(index));
                        }
                        initial.put(world, new Cell(state, nbt));
                    }
                }
            }
            // A stopped bridge clears its blocks, but its suspended level still owns the
            // current queues. Replaying blueprint ticks would revive consumed work.
            var tickLevel = bridge != null ? bridge.level() : ownedLevel;
            if (tickLevel != null) {
                snapshotTicks(tickLevel, bounds(region), initialBlockTicks, initialFluidTicks);
            } else {
                detached.getScheduledBlockTicksForRegion(name).values().forEach(t -> initialBlockTicks.add(toWorldTick(t, region)));
                detached.getScheduledFluidTicksForRegion(name).values().forEach(t -> initialFluidTicks.add(toWorldTick(t, region)));
            }
        }
        if (ownedLevel != null) {
            for (Entity entity : allEntities(ownedLevel)) addSavedEntity(entity, entityIds, initialEntities);
        } else if (projection != null) {
            for (Region r : regions) if (r.enabled()) {
                Cuboid b = bounds(r);
                for (Entity entity : projection.getEntities((Entity) null, net.minecraft.world.phys.AABB.encapsulatingFullBlocks(b.min(), b.max()), e -> !(e instanceof Player))) addSavedEntity(entity, entityIds, initialEntities);
            }
        }
        for (CompoundTag tag : initialEntities) expand(BlockPos.containing(readVector(tag, "Pos")));
        if (min == null) min = max = origin;
        int floorY = Math.max(mc.level.getMinY(), Math.min(mc.level.getMaxY() - 2, min.getY() - 1));
        spawn = new BlockPos(min.getX() + (max.getX() - min.getX()) / 2, floorY + 1, min.getZ() - 24);
        expand(spawn.offset(-8, -1, -8));
        expand(spawn.offset(7, 0, 7));
    }

    public ResourceKey<Level> dimension() { return dimension; }
    public SchematicPlacement placement() { return source; }
    public BlockPos min() { return min; }
    public BlockPos max() { return max; }
    public BlockPos spawnPosition() { return spawn; }
    public String worldKey() { return worldKey; }
    public boolean worldMatchesNow() { return worldKey.equals(currentWorldKey()); }
    public static String currentWorldKey() {
        return StringUtils.getStorageFileName(false, "litematica_", ".json", "default");
    }

    public void populate(ServerLevel level) {
        if (local != null) throw new IllegalStateException("工作间已经初始化。");
        local = level;
        for (ServerLevel world : level.getServer().getAllLevels()) TRACKED.put(world, this);
        initializing = true;
        try {
            initial.forEach((pos, cell) -> putCell(level, pos, cell));
            for (int x = -8; x < 8; x++) for (int z = -8; z < 8; z++) {
                BlockPos p = spawn.offset(x, -1, z);
                if (level.getBlockState(p).isAir()) {
                    platform.add(p);
                    level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                }
            }
            for (CompoundTag tag : initialEntities) {
                forceChunk(level, BlockPos.containing(readVector(tag,"Pos")));
                Entity entity = loadEntity(tag.copy(), level, false);
                if (entity == null) throw new IllegalStateException("无法复制投影实体。");
                addEntity(level,entity);
            }
            initialBlockTicks.forEach(t -> level.getBlockTicks().schedule(new ScheduledTick<>(t.type(), t.pos(), level.getGameTime() + Math.max(0, t.triggerTick()), t.priority(), t.subTickOrder())));
            initialFluidTicks.forEach(t -> level.getFluidTicks().schedule(new ScheduledTick<>(t.type(), t.pos(), level.getGameTime() + Math.max(0, t.triggerTick()), t.priority(), t.subTickOrder())));
        } finally { initializing = false; }
    }

    public static void onBlockChanged(ServerLevel level, BlockPos pos) {
        onBlockChanged(level,pos,null,level.getBlockState(pos));
    }
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState state) {
        WorkshopEdit edit = TRACKED.get(level);
        if (edit != null && !edit.initializing) {
            BlockPos immutable = pos.immutable();
            edit.forceChunk(level,immutable);
            if (level != edit.local) {
                edit.foreignChanges.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(immutable);
                return;
            }
            edit.dirty.add(immutable);
            BlockState moving = edit.movingPlatform.get(immutable);
            boolean finishedMove = moving != null && moving.getBlock() == state.getBlock();
            boolean natural = oldState != null && (GRASS_TICKS.get() > 0 && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT))
                    || oldState.getBlock() == state.getBlock() && state.is(Blocks.GRASS_BLOCK));
            if (PLAYER_MUTATIONS.get() > 0 || !finishedMove && !natural) {
                edit.platform.remove(immutable);edit.movingPlatform.remove(immutable);
            } else if (finishedMove) edit.movingPlatform.remove(immutable);
            edit.expand(immutable);
        }
    }
    public static boolean isTracked(ServerLevel level) { return TRACKED.containsKey(level); }
    public static boolean isPlatformSource(ServerLevel level, BlockPos pos) { WorkshopEdit edit=TRACKED.get(level);return edit!=null&&edit.platform.contains(pos); }
    public static void platformMoved(ServerLevel level, Map<BlockPos,BlockState> moves) {
        WorkshopEdit edit=TRACKED.get(level);if(edit==null)return;
        edit.platform.addAll(moves.keySet());edit.movingPlatform.putAll(moves);
    }
    public static void beginGrassTick() { GRASS_TICKS.set(GRASS_TICKS.get()+1); }
    public static void endGrassTick() { int depth=GRASS_TICKS.get()-1;if(depth<=0)GRASS_TICKS.remove();else GRASS_TICKS.set(depth); }
    public static void onEntityMoved(ServerLevel level, Entity entity) {
        WorkshopEdit edit=TRACKED.get(level);
        if(edit!=null && !(entity instanceof Player) && !entity.isRemoved()) edit.forceChunk(level,entity.blockPosition());
    }
    private void forceChunk(ServerLevel level, BlockPos pos) {
        int x=pos.getX()>>4,z=pos.getZ()>>4;
        if(forced.computeIfAbsent(level,key->new HashSet<>()).add(net.minecraft.world.level.ChunkPos.pack(x,z))) level.setChunkForced(x,z,true);
    }
    private static List<Entity> allEntities(ServerLevel level) {
        var manager=((ServerLevelEntityManagerAccessor)level).simulatica$entityManager();
        var storage=((WorkshopEntitySectionAccessor)manager).simulatica$sections();
        List<Entity> entities=new ArrayList<>();
        for(long chunk:storage.getAllChunksWithExistingSections()) storage.getExistingSectionsInChunk(chunk).forEach(section->section.getEntities().forEach(entities::add));
        return entities;
    }
    private static void addEntity(ServerLevel level, Entity entity) {
        if(!level.tryAddFreshEntityWithPassengers(entity))throw new IllegalStateException("重复实体 UUID："+entity.getUUID());
        var manager=((ServerLevelEntityManagerAccessor)level).simulatica$entityManager();
        if(entity.getSelfAndPassengers().anyMatch(e->!manager.isLoaded(e.getUUID())))throw new IllegalStateException("无法登记投影实体 UUID："+entity.getUUID());
    }

    public static void beginPlayerMutation() { PLAYER_MUTATIONS.set(PLAYER_MUTATIONS.get() + 1); }
    public static void endPlayerMutation() {
        int depth = PLAYER_MUTATIONS.get() - 1;
        if (depth <= 0) PLAYER_MUTATIONS.remove(); else PLAYER_MUTATIONS.set(depth);
    }

    public void close() { synchronized (TRACKED) { TRACKED.entrySet().removeIf(entry -> entry.getValue() == this); } }

    public void enableRegion(String name, ServerLevel level) {
        requireLocal(level);
        Region region = regions.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
        if (region.enabled()) return;
        LitematicaSchematic baseline = new LitematicaSchematic(original.getFile(), originalData.copy(), FileType.LITEMATICA_SCHEMATIC);
        var container = baseline.getSubRegionContainer(name);
        initializing = true;
        try {
            BlockPos offset = region.minimumOffset();
            for (int y = 0; y < Math.abs(region.size().getY()); y++) for (int z = 0; z < Math.abs(region.size().getZ()); z++) for (int x = 0; x < Math.abs(region.size().getX()); x++) {
                BlockPos index = new BlockPos(x, y, z);
                BlockPos world = blockToWorld(region, index.offset(offset));
                expand(world);
                if (container.get(x, y, z).is(Blocks.STRUCTURE_VOID)) {
                    originalVoids.computeIfAbsent(name, n -> new HashSet<>()).add(world);
                    continue;
                }
                if (dirty.contains(world) || belongsToEnabled(world)) continue;
                CompoundTag tag = MasaNbt.tag(baseline.getBlockEntityMapForRegion(name).get(index));
                putCell(level, world, new Cell(stateToWorld(region, container.get(x, y, z)), tag));
                platform.remove(world);
            }
            Set<UUID> present = new HashSet<>();
            allEntities(level).forEach(e -> present.add(e.getUUID()));
            for (var info : baseline.getEntityListForRegion(name)) {
                CompoundTag tag = MasaNbt.entityTag(info);
                if (uuid(tag) != null && present.contains(uuid(tag))) continue;
                Entity entity = nativeEntity(tag, region, level);
                if (entity == null) throw new IllegalStateException("无法启用区域实体：" + name);
                addEntity(level,entity);
            }
            for (var t : baseline.getScheduledBlockTicksForRegion(name).values()) {
                var worldTick = toWorldTick(t, region);
                if (!dirty.contains(worldTick.pos()) && !belongsToEnabled(worldTick.pos())) level.getBlockTicks().schedule(new ScheduledTick<>(worldTick.type(), worldTick.pos(), level.getGameTime() + Math.max(0, worldTick.triggerTick()), worldTick.priority(), worldTick.subTickOrder()));
            }
            for (var t : baseline.getScheduledFluidTicksForRegion(name).values()) {
                var worldTick = toWorldTick(t, region);
                if (!dirty.contains(worldTick.pos()) && !belongsToEnabled(worldTick.pos())) level.getFluidTicks().schedule(new ScheduledTick<>(worldTick.type(), worldTick.pos(), level.getGameTime() + Math.max(0, worldTick.triggerTick()), worldTick.priority(), worldTick.subTickOrder()));
            }
            regions.set(regions.indexOf(region), new Region(name, region.pos(), region.size(), region.mirror(), region.rotation(), true));
            settings.get(name).addProperty("enabled", true);
        } finally { initializing = false; }
    }

    public Result capture(ServerLevel level) {
        requireLocal(level);
        Set<String> foreign = new TreeSet<>();
        for (ServerLevel world : level.getServer().getAllLevels()) {
            if (world == level) continue;
            if (foreignChanges.getOrDefault(world.dimension(), Set.of()).stream().anyMatch(p -> !world.getBlockState(p).isAir())) foreign.add(world.dimension().identifier().toString());
            for (Entity entity : allEntities(world)) if (saveEntity(entity) != null) foreign.add(world.dimension().identifier().toString());
        }
        if (!foreign.isEmpty()) throw new IllegalStateException("原理图只能保存源维度；以下其他维度仍有建筑或实体，请清理后重试：" + String.join("、", foreign));
        min = max = null;
        for (Region region : regions) if (region.enabled()) { Cuboid b = bounds(region); expand(b.min()); expand(b.max()); }
        if (min == null) min = max = origin;
        for (BlockPos pos : dirty) if (!level.getBlockState(pos).isAir() && !isPlatformOnly(level,pos)) expand(pos);
        List<Entity> entities = new ArrayList<>();
        for (Entity entity : allEntities(level)) {
            if (!entity.isRemoved() && !(entity instanceof Player) && !(entity instanceof EnderDragonPart) && !entity.isPassenger()) {
                entities.add(entity);
                expand(BlockPos.containing(entity.position()));
            }
        }
        Set<String> conflicts = new TreeSet<>();
        for (BlockPos pos : dirty) if (!belongsToEnabled(pos) && !level.getBlockState(pos).isAir()) {
            for (Region r : regions) if (!r.enabled() && bounds(r).contains(pos)) conflicts.add(r.name());
        }
        for (Entity entity : entities) {
            BlockPos pos = BlockPos.containing(entity.position());
            if (!belongsToEnabled(pos)) for (Region r : regions) if (!r.enabled() && bounds(r).contains(pos)) conflicts.add(r.name());
        }
        if (!conflicts.isEmpty()) throw new DisabledRegionConflict(conflicts);
        Map<Entity, BlockPos> entityStarts = new IdentityHashMap<>();
        for (Entity entity : entities) {
            CompoundTag saved = saveEntity(entity);
            if (saved == null) continue;
            Region owner = regions.stream().filter(r -> r.enabled() && bounds(r).contains(BlockPos.containing(entity.position()))).findFirst()
                    .orElse(new Region("", BlockPos.ZERO, new BlockPos(1,1,1), Mirror.NONE, Rotation.NONE, true));
            CompoundTag encoded = encodeEntityForNative(entity, saved, owner, level);
            BlockPos start = BlockPos.containing(entityPosition(owner, readVector(encoded, "Pos"), false));
            entityStarts.put(entity, start);
            expand(start);
        }
        long originalVolume = preflightOriginal(original);
        preflight(List.of(new Cuboid(min,max)), originalVolume);

        LitematicaSchematic candidate = new LitematicaSchematic(original.getFile(), originalData.copy(), FileType.LITEMATICA_SCHEMATIC);
        Map<String, JsonObject> resultSettings = new LinkedHashMap<>();
        settings.forEach((n, j) -> resultSettings.put(n, j.deepCopy()));
        List<Region> captured = new ArrayList<>();
        for (Region region : regions) if (region.enabled()) {
            captureBlocks(candidate, region, level);
            captured.add(region);
        }
        List<Cuboid> extension = new ArrayList<>(List.of(new Cuboid(min, max)));
        for (Region region : regions) {
            Cuboid occupied = bounds(region);
            extension = extension.stream().flatMap(b -> subtract(b, occupied).stream()).toList();
        }
        CompoundTag data = candidate.writeToNBT().copy();
        CompoundTag encodedRegions = data.getCompoundOrEmpty("Regions");
        int counter = 1;
        for (Cuboid box : extension) {
            if (!hasContent(level, box, entities, entityStarts.values())) continue;
            String name;
            do { name = "Simulatica 新增 " + counter++; } while (resultSettings.containsKey(name));
            BlockPos p1 = PositionUtils.getReverseTransformedBlockPos(box.min().subtract(origin), mirror, rotation);
            BlockPos p2 = PositionUtils.getReverseTransformedBlockPos(box.max().subtract(origin), mirror, rotation);
            Box localBox = new Box(p1, p2, name);
            AreaSelection area = new AreaSelection();
            area.setExplicitOrigin(BlockPos.ZERO);
            area.addSubRegionBox(localBox, false);
            LitematicaSchematic piece = LitematicaSchematic.createEmptySchematic(area, candidate.getMetadata().getAuthor());
            Region region = new Region(name, p1, localBox.getSize(), Mirror.NONE, Rotation.NONE, true);
            captureBlocks(piece, region, level);
            captured.add(region);
            encodedRegions.put(name, piece.writeToNBT().getCompoundOrEmpty("Regions").getCompoundOrEmpty(name).copy());
            resultSettings.put(name, new SubRegionPlacement(p1, name).toJson());
        }
        candidate = new LitematicaSchematic(original.getFile(), data, FileType.LITEMATICA_SCHEMATIC);
        Set<UUID> ids = new HashSet<>();
        for (Entity entity : entities) {
            CompoundTag tag = saveEntity(entity);
            if (tag == null) continue;
            collectIds(tag, ids);
            BlockPos pos = BlockPos.containing(entity.position());
            Region owner = null;
            CompoundTag encoded = null;
            List<Region> owners = new ArrayList<>();
            captured.stream().filter(r -> bounds(r).contains(pos)).forEach(owners::add);
            captured.stream().filter(r -> !owners.contains(r) && bounds(r).contains(entityStarts.get(entity))).forEach(owners::add);
            for (Region possible : owners) {
                CompoundTag trial = encodeEntityForNative(entity, tag, possible, level);
                BlockPos start = BlockPos.containing(entityPosition(possible, readVector(trial, "Pos"), false));
                Cuboid ownerBounds = bounds(possible);
                if (start.getX() >> 4 >= ownerBounds.min().getX() >> 4 && start.getX() >> 4 <= ownerBounds.max().getX() >> 4
                        && start.getZ() >> 4 >= ownerBounds.min().getZ() >> 4 && start.getZ() >> 4 <= ownerBounds.max().getZ() >> 4) {
                    owner = possible; encoded = trial; break;
                }
            }
            if (owner == null) {
                for (Region r : regions) if (!r.enabled() && bounds(r).contains(entityStarts.get(entity))) conflicts.add(r.name());
                if (!conflicts.isEmpty()) throw new DisabledRegionConflict(conflicts);
                throw new IllegalStateException("实体的原生加载位置没有导出区域：" + entity.getType());
            }
            tag = encoded;
            var list = candidate.getEntityListForRegion(owner.name());
            Vec3 canonical = readVector(tag, "Pos");
            list.add(MasaNbt.entity(canonical, tag));
        }
        updateMetadata(candidate);
        candidate.getMetadata().setTimeModifiedToNow();
        candidate.getMetadata().setModifiedSinceSaved();
        LitematicaSchematic validated = new LitematicaSchematic(original.getFile(), candidate.writeToNBT().copy(), FileType.LITEMATICA_SCHEMATIC);
        if (validated.getSubRegionCount() != candidate.getSubRegionCount()) throw new IllegalStateException("候选投影加载验证失败。");
        CompoundTag expectedRegions = candidate.writeToNBT().getCompoundOrEmpty("Regions");
        CompoundTag actualRegions = validated.writeToNBT().getCompoundOrEmpty("Regions");
        for (String name : candidate.getAreaPositions().keySet()) {
            var expected = candidate.getSubRegionContainer(name);
            var actual = validated.getSubRegionContainer(name);
            var size = expected.getSize();
            if (actual == null || !size.equals(actual.getSize()) || !candidate.getAreaSize(name).equals(validated.getAreaSize(name))) throw new IllegalStateException("区域尺寸加载验证失败：" + name);
            for (int y = 0; y < size.getY(); y++) for (int z = 0; z < size.getZ(); z++) for (int x = 0; x < size.getX(); x++) if (expected.get(x,y,z) != actual.get(x,y,z)) throw new IllegalStateException("方块加载验证失败：" + name);
            CompoundTag expectedTag = expectedRegions.getCompoundOrEmpty(name);
            CompoundTag actualTag = actualRegions.getCompoundOrEmpty(name);
            if (!expectedTag.equals(actualTag)) throw new IllegalStateException("NBT加载验证失败：" + name);
        }
        validated.getMetadata().setModifiedSinceSaved();
        return new Result(validated, Collections.unmodifiableMap(resultSettings));
    }

    public SchematicPlacement commit(Result result) {
        if (source.getSchematic() != original || originalGeneration != EditedPlacementCache.generation(original)) throw new IllegalStateException("原投影已被重新加载，未应用修改。");
        if (!DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(source)) throw new IllegalStateException("原放置已卸载，修改保留在工作间；请恢复原放置后重试。");
        if (!source.getOrigin().equals(origin) || source.getMirror() != mirror || source.getRotation() != rotation
                || source.isEnabled() != originalEnabled || source.isRenderingEnabled() != originalRendering
                || source.getAllSubRegionsPlacements().size() != baselineSettings.size()) throw new IllegalStateException("原放置的位置或设置已在外部改变，请恢复后重试；工作间修改已保留。");
        for (SubRegionPlacement sub : source.getAllSubRegionsPlacements()) if (!sub.toJson().equals(baselineSettings.get(sub.getName()))) throw new IllegalStateException("原子区域设置已在外部改变，请恢复后重试：" + sub.getName());
        Map<String, JsonObject> beforeSettings = new LinkedHashMap<>();
        source.getAllSubRegionsPlacements().forEach(sub -> beforeSettings.put(sub.getName(), sub.toJson().deepCopy()));
        Result before = new Result(source.getSchematic(), Map.copyOf(beforeSettings));
        Runnable restoreCache = EditedPlacementCache.undoFor(worldKey, source);
        replace(source, result);
        try { EditedPlacementCache.remember(worldKey, source, result); }
        catch (Throwable failure) {
            if (source.getSchematic() == result.schematic()) replace(source, before);
            restoreCache.run();
            throw failure;
        }
        committedBefore = before;
        committedResult = result;
        undoCache = restoreCache;
        return source;
    }

    /** Called only when foreground handoff failed and the local workshop is still current. */
    public void rollback() {
        if (committedResult == null) return;
        if (source.getSchematic() == committedResult.schematic()) replace(source, committedBefore);
        else if (source.getSchematic() != committedBefore.schematic()) throw new IllegalStateException("投影在回滚前发生外部修改。");
        undoCache.run();
        committedBefore = committedResult = null;
        undoCache = null;
    }

    public static void replace(SchematicPlacement placement, Result result) {
        var manager = DataManager.getSchematicPlacementManager();
        var access = (WorkshopPlacementAccessor) placement;
        var invoker = (WorkshopPlacementManagerInvoker) manager;
        LitematicaSchematic previous = placement.getSchematic();
        var previousMaterials = placement.getMaterialList();
        Map<String, SubRegionPlacement> before = new HashMap<>(access.simulatica$regions());
        Map<String, SubRegionPlacement> after = new HashMap<>();
        result.regions().forEach((name, json) -> {
            SubRegionPlacement sub = SubRegionPlacement.fromJson(json.deepCopy());
            if (sub == null || result.schematic().getAreaSize(name) == null) throw new IllegalStateException("无效的候选子区域：" + name);
            SubRegionPlacement old = before.get(name);
            after.put(name, old != null && old.toJson().equals(json) ? old : sub);
        });
        invoker.simulatica$beforeChange(placement);
        try {
            access.simulatica$schematic(result.schematic());
            access.simulatica$regionCount(result.schematic().getSubRegionCount());
            access.simulatica$regions().clear();
            access.simulatica$regions().putAll(after);
            access.simulatica$materials(null);
            if (placement.hasVerifier()) placement.getSchematicVerifier().reset();
            access.simulatica$modified(manager);
            SchematicHolder.getInstance().addSchematic(result.schematic(), true);
        } catch (Throwable failure) {
            invoker.simulatica$beforeChange(placement);
            access.simulatica$schematic(previous);
            access.simulatica$regionCount(previous.getSubRegionCount());
            access.simulatica$regions().clear();
            access.simulatica$regions().putAll(before);
            access.simulatica$materials(previousMaterials);
            access.simulatica$modified(manager);
            throw failure;
        }
    }

    private void captureBlocks(LitematicaSchematic candidate, Region region, ServerLevel level) {
        var container = candidate.getSubRegionContainer(region.name());
        Map<BlockPos, ?> blockEntities = candidate.getBlockEntityMapForRegion(region.name());
        blockEntities.clear();
        candidate.getEntityListForRegion(region.name()).clear();
        var blocks = candidate.getScheduledBlockTicksForRegion(region.name());
        var fluids = candidate.getScheduledFluidTicksForRegion(region.name());
        blocks.clear(); fluids.clear();
        BlockPos offset = region.minimumOffset();
        for (int y = 0; y < Math.abs(region.size().getY()); y++) for (int z = 0; z < Math.abs(region.size().getZ()); z++) for (int x = 0; x < Math.abs(region.size().getX()); x++) {
            BlockPos index = new BlockPos(x, y, z);
            BlockPos world = blockToWorld(region, index.offset(offset));
            boolean masked = isPlatformOnly(level, world) || originalVoids.getOrDefault(region.name(), Set.of()).contains(world) && !dirty.contains(world);
            BlockState state = masked ? Blocks.STRUCTURE_VOID.defaultBlockState() : stateFromWorld(region, level.getBlockState(world));
            container.set(x, y, z, state);
            BlockEntity be = level.getBlockEntity(world);
            if (!masked && state.hasBlockEntity() && be == null) throw new IllegalStateException("方块实体尚未就绪：" + world);
            if (be != null && !state.is(Blocks.STRUCTURE_VOID)) {
                CompoundTag tag = saveBlockEntity(be, level);
                tag.putInt("x", x); tag.putInt("y", y); tag.putInt("z", z);
                MasaNbt.putBlockEntity(blockEntities, index, tag);
            }
            if (!stateToWorld(region, state).equals(level.getBlockState(world)) && !masked) throw new IllegalStateException("方块变换验证失败：" + world);
        }
        List<ScheduledTick<Block>> blockTicks = new ArrayList<>();
        List<ScheduledTick<Fluid>> fluidTicks = new ArrayList<>();
        snapshotTicks(level, bounds(region), blockTicks, fluidTicks);
        for (var t : blockTicks) { BlockPos p = indexFromWorld(region, t.pos()); blocks.put(p, new ScheduledTick<>(t.type(), p, t.triggerTick(), t.priority(), t.subTickOrder())); }
        for (var t : fluidTicks) { BlockPos p = indexFromWorld(region, t.pos()); fluids.put(p, new ScheduledTick<>(t.type(), p, t.triggerTick(), t.priority(), t.subTickOrder())); }
    }

    private void putCell(ServerLevel level, BlockPos pos, Cell cell) {
        if (level.isOutsideBuildHeight(pos)) {
            if (!cell.state().isAir()) throw new IllegalStateException("无法复制方块，坐标超出工作间高度：" + pos + "（" + level.dimension().identifier() + "）");
            return;
        }
        BlockEntity be = null;
        if (cell.nbt() != null) {
            CompoundTag tag = cell.nbt().copy();
            tag.putInt("x", pos.getX()); tag.putInt("y", pos.getY()); tag.putInt("z", pos.getZ());
            be = loadBlockEntity(level, pos, cell.state(), tag);
        }
        forceChunk(level,pos);
        level.setBlock(pos, cell.state(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        if (!level.getBlockState(pos).equals(cell.state())) throw new IllegalStateException("无法复制方块，写入后的状态不匹配：" + pos + "（" + level.dimension().identifier() + "）");
        if (be != null) {
            level.setBlockEntity(be);
            if (level.getBlockEntity(pos) != be) throw new IllegalStateException("无法安装方块实体：" + pos);
        }
    }

    private boolean belongsToEnabled(BlockPos pos) { return regions.stream().anyMatch(r -> r.enabled() && bounds(r).contains(pos)); }
    private void requireLocal(ServerLevel level) { if (local != level) throw new IllegalArgumentException("不是当前工作间。"); }
    private void expand(BlockPos pos) { min = min == null ? pos : PositionUtils.getMinCorner(min, pos); max = max == null ? pos : PositionUtils.getMaxCorner(max, pos); }
    private Cuboid bounds(Region r) {
        BlockPos a = blockToWorld(r, BlockPos.ZERO);
        BlockPos b = blockToWorld(r, PositionUtils.getRelativeEndPositionFromAreaSize(r.size()));
        return new Cuboid(PositionUtils.getMinCorner(a, b), PositionUtils.getMaxCorner(a, b));
    }
    private BlockPos blockToWorld(Region r, BlockPos localPos) {
        BlockPos p = PositionUtils.getTransformedBlockPos(localPos, mirror, rotation);
        p = PositionUtils.getTransformedBlockPos(p, r.mirror(), r.rotation());
        return p.offset(PositionUtils.getTransformedBlockPos(r.pos(), mirror, rotation)).offset(origin);
    }
    private BlockPos blockFromWorld(Region r, BlockPos world) {
        BlockPos p = world.subtract(origin).subtract(PositionUtils.getTransformedBlockPos(r.pos(), mirror, rotation));
        p = PositionUtils.getReverseTransformedBlockPos(p, r.mirror(), r.rotation());
        return PositionUtils.getReverseTransformedBlockPos(p, mirror, rotation);
    }
    private BlockPos indexFromWorld(Region r, BlockPos world) { return blockFromWorld(r, world).subtract(r.minimumOffset()); }
    private Vec3 entityPosition(Region r, Vec3 pos, boolean inverse) {
        BlockPos offset = PositionUtils.getTransformedBlockPos(r.pos(), mirror, rotation).offset(origin);
        Vec3 p;
        if (inverse) {
            p = pos.subtract(Vec3.atLowerCornerOf(offset));
            p = reversePosition(p, r.mirror(), r.rotation());
            return reversePosition(p, mirror, rotation);
        }
        p = PositionUtils.getTransformedPosition(pos, mirror, rotation);
        p = PositionUtils.getTransformedPosition(p, r.mirror(), r.rotation());
        return p.add(Vec3.atLowerCornerOf(offset));
    }
    private static Vec3 reversePosition(Vec3 p, Mirror mirror, Rotation rotation) {
        p = PositionUtils.getTransformedPosition(p, Mirror.NONE, PositionUtils.getReverseRotation(rotation));
        return PositionUtils.getTransformedPosition(p, mirror, Rotation.NONE);
    }
    private Mirror subMirror(Region r) {
        if (r.mirror() != Mirror.NONE && (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)) return r.mirror() == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        return r.mirror();
    }
    private BlockState stateToWorld(Region r, BlockState state) { return state.mirror(mirror).mirror(subMirror(r)).rotate(rotation.getRotated(r.rotation())); }
    private BlockState stateFromWorld(Region r, BlockState state) { return state.rotate(PositionUtils.getReverseRotation(rotation.getRotated(r.rotation()))).mirror(subMirror(r)).mirror(mirror); }
    private <T> ScheduledTick<T> toWorldTick(ScheduledTick<T> tick, Region region) {
        return new ScheduledTick<>(tick.type(), blockToWorld(region, tick.pos().offset(region.minimumOffset())), tick.triggerTick(), tick.priority(), tick.subTickOrder());
    }

    /** Match the installed reader, including its unchanged Motion and translation-only home/leash. */
    private CompoundTag encodeEntityForNative(Entity sourceEntity, CompoundTag saved, Region r, ServerLevel level) {
        BlockPos offset = PositionUtils.getTransformedBlockPos(r.pos(), mirror, rotation).offset(origin);
        CompoundTag base = saved.copy();
        for (String key : List.of("home_pos", "leash")) base.read(key, BlockPos.CODEC).ifPresent(p -> base.store(key, BlockPos.CODEC, p.subtract(offset)));
        float yaw = sourceEntity.getYRot();
        List<Direction> directions = sourceEntity instanceof HangingEntity ? Arrays.asList(Direction.values()) : Collections.singletonList(null);
        // ponytail: vanilla's orientation group has at most 48 candidates; validate the native reader rather than duplicate every entity override.
        for (Direction direction : directions) for (int sign : new int[]{1,-1}) for (int turn = 0; turn < 4; turn++) {
            CompoundTag trial = base.copy();
            ListTag angles = new ListTag();
            angles.add(FloatTag.valueOf(sign * yaw + turn * 90));
            angles.add(FloatTag.valueOf(sourceEntity.getXRot()));
            trial.put("Rotation", angles);
            if (direction != null) {
                if (sourceEntity instanceof ItemFrame) trial.store("Facing", Direction.LEGACY_ID_CODEC, direction);
                else if (sourceEntity instanceof Painting && direction.getAxis().isHorizontal()) trial.store("facing", Direction.LEGACY_ID_CODEC_2D, direction);
                else continue;
            }
            Vec3 worldBase = sourceEntity.position();
            for (int attempt = 0; attempt < 3; attempt++) {
                Vec3 canonical = entityPosition(r, worldBase, true);
                putVector(trial, "Pos", canonical);
                if (sourceEntity instanceof BlockAttachedEntity) trial.store("block_pos", BlockPos.CODEC, BlockPos.containing(canonical));
                Entity probe = nativeEntity(trial, r, level);
                if (probe == null) break;
                if (sameEntityState(sourceEntity, probe)) return trial;
                if (Math.abs(net.minecraft.util.Mth.wrapDegrees(probe.getYRot() - yaw)) > .001 || Math.abs(probe.getXRot() - sourceEntity.getXRot()) > .001) break;
                worldBase = worldBase.add(sourceEntity.position().subtract(probe.position()));
            }
        }
        throw new IllegalStateException("此实体无法完整回写，请保留编辑并移除或调整它：" + sourceEntity.getType());
    }

    /** Same forward recipe as SchematicPlacingUtils.placeEntitiesToWorldWithinChunk, without registering a probe. */
    private Entity nativeEntity(CompoundTag canonical, Region r, ServerLevel level) {
        CompoundTag tag = canonical.copy();
        Vec3 world = entityPosition(r, readVector(tag, "Pos"), false);
        putVector(tag, "Pos", world);
        String id = tag.getStringOr("id", "");
        if (id.equals("minecraft:glow_item_frame") || id.equals("minecraft:item_frame") || id.equals("minecraft:leash_knot") || id.equals("minecraft:painting")) {
            tag.putInt("TileX", (int)world.x); tag.putInt("TileY", (int)world.y); tag.putInt("TileZ", (int)world.z);
            tag.store("block_pos", BlockPos.CODEC, new BlockPos((int)world.x, (int)world.y, (int)world.z));
        }
        BlockPos offset = PositionUtils.getTransformedBlockPos(r.pos(), mirror, rotation).offset(origin);
        for (String key : List.of("home_pos", "leash")) tag.read(key, BlockPos.CODEC).filter(p -> !p.equals(BlockPos.ZERO)).ifPresent(p -> tag.store(key, BlockPos.CODEC, p.offset(offset)));
        Entity entity = loadEntity(tag, level, true);
        float originalYaw = canonical.getListOrEmpty("Rotation").getFloatOr(0, 0);
        SchematicPlacingUtils.rotateEntity(entity, world.x, world.y, world.z, rotation.getRotated(r.rotation()), mirror, subMirror(r));
        if (entity instanceof LivingEntity living && living.isSleeping()) living.setSleepingPos(BlockPos.containing(world));
        if (entity instanceof Painting painting) {
            Direction right = painting.getDirection().getCounterClockWise();
            Vec3 adjusted = world;
            if (painting.getVariant().value().width() % 2 == 0 && right.getAxisDirection() == Direction.AxisDirection.POSITIVE) adjusted = adjusted.add(-right.getStepX(),0,-right.getStepZ());
            if (painting.getVariant().value().height() % 2 == 0) adjusted = adjusted.add(0,-1,0);
            entity.setPos(adjusted);
        }
        if (entity instanceof ItemFrame && entity.getYRot() != originalYaw && (entity.getXRot() == 90 || entity.getXRot() == -90)) entity.setYRot(originalYaw);
        positionPassengers(entity);
        return entity;
    }
    private static Entity loadEntity(CompoundTag tag, ServerLevel level, boolean ignoreChecks) {
        ProblemReporter.Collector problems = new ProblemReporter.Collector();
        var input = TagValueInput.create(problems, level.registryAccess(), tag);
        Entity entity = EntityType.loadEntityRecursive(input, level, new EntitySpawnRequest(EntitySpawnReason.LOAD, ignoreChecks), e -> e);
        if (entity == null || !problems.isEmpty()) throw new IllegalStateException("实体 NBT 解析失败：" + tag.getStringOr("id", "") + " " + problems.getReport());
        return entity;
    }
    private static BlockEntity loadBlockEntity(ServerLevel level, BlockPos pos, BlockState state, CompoundTag tag) {
        ProblemReporter.Collector problems = new ProblemReporter.Collector();
        var input = TagValueInput.create(problems, level.registryAccess(), tag);
        var type = input.read("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.byNameCodec()).orElse(null);
        if (type == null || !type.isValid(state)) throw new IllegalStateException("方块实体类型不匹配：" + pos + " " + problems.getReport());
        BlockEntity entity = type.create(pos, state);
        if (entity == null) throw new IllegalStateException("无法复制方块实体：" + pos);
        entity.loadWithComponents(input);
        if (!problems.isEmpty()) throw new IllegalStateException("方块实体 NBT 解析失败：" + pos + " " + problems.getReport());
        return entity;
    }
    private static void positionPassengers(Entity entity) {
        for (Entity passenger : entity.getPassengers()) {
            Vec3 position = entity.getPassengerRidingPosition(passenger);
            passenger.snapTo(position.x, position.y, position.z, passenger.getYRot(), passenger.getXRot());
            entity.positionRider(passenger);
            positionPassengers(passenger);
        }
    }
    private static boolean sameEntityState(Entity expected, Entity actual) {
        if (!expected.getUUID().equals(actual.getUUID()) || expected.position().distanceToSqr(actual.position()) > 1.0E-8
                || Math.abs(net.minecraft.util.Mth.wrapDegrees(expected.getYRot() - actual.getYRot())) > .001
                || Math.abs(expected.getXRot() - actual.getXRot()) > .001
                || expected.getDeltaMovement().distanceToSqr(actual.getDeltaMovement()) > 1.0E-8) return false;
        if (expected instanceof BlockAttachedEntity a && actual instanceof BlockAttachedEntity b && !a.getPos().equals(b.getPos())) return false;
        if (expected instanceof HangingEntity a && actual instanceof HangingEntity b && a.getDirection() != b.getDirection()) return false;
        CompoundTag expectedTag = saveEntity(expected), actualTag = saveEntity(actual);
        if (expectedTag == null || actualTag == null) return false;
        for (String key : List.of("home_pos", "leash", "sleeping_pos")) if (!Objects.equals(expectedTag.get(key), actualTag.get(key))) return false;
        return samePassengers(expected, actual);
    }
    private static boolean samePassengers(Entity expected, Entity actual) {
        if (expected.getPassengers().size() != actual.getPassengers().size()) return false;
        for (int i = 0; i < expected.getPassengers().size(); i++) {
            Entity a = expected.getPassengers().get(i), b = actual.getPassengers().get(i);
            if (!a.getUUID().equals(b.getUUID()) || a.position().distanceToSqr(b.position()) > 1.0E-8
                    || Math.abs(net.minecraft.util.Mth.wrapDegrees(a.getYRot() - b.getYRot())) > .001
                    || Math.abs(a.getXRot() - b.getXRot()) > .001 || a.getDeltaMovement().distanceToSqr(b.getDeltaMovement()) > 1.0E-8
                    || !samePassengers(a,b)) return false;
            if (a instanceof BlockAttachedEntity x && b instanceof BlockAttachedEntity y && !x.getPos().equals(y.getPos())) return false;
            if (a instanceof HangingEntity x && b instanceof HangingEntity y && x.getDirection() != y.getDirection()) return false;
        }
        return true;
    }
    private static CompoundTag saveEntity(Entity entity) {
        if (entity.isRemoved() || entity instanceof Player || entity instanceof EnderDragonPart || entity.isPassenger()) return null;
        ProblemReporter.Collector problems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(problems, entity.level().registryAccess());
        if (!entity.save(output)) throw new IllegalStateException("实体无法保存，请等待其消失或移除后重试：" + entity.getType());
        if (!problems.isEmpty()) throw new IllegalStateException("实体 NBT 编码失败：" + entity.getType() + " " + problems.getReport());
        return output.buildResult();
    }
    private static CompoundTag saveBlockEntity(BlockEntity entity, Level level) {
        ProblemReporter.Collector problems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(problems, level.registryAccess());
        entity.saveWithFullMetadata(output);
        if (!problems.isEmpty()) throw new IllegalStateException("方块实体 NBT 编码失败：" + entity.getBlockPos() + " " + problems.getReport());
        return output.buildResult();
    }
    private static void addSavedEntity(Entity entity, Set<UUID> ids, List<CompoundTag> list) {
        if (ids.contains(entity.getUUID())) return;
        CompoundTag tag = saveEntity(entity);
        if (tag != null) { collectIds(tag, ids); list.add(tag.copy()); }
    }
    private static UUID uuid(CompoundTag tag) {
        var ints = tag.getIntArray("UUID");
        return ints.isPresent() && ints.get().length == 4 ? net.minecraft.core.UUIDUtil.uuidFromIntArray(ints.get()) : null;
    }
    private static void collectIds(CompoundTag tag, Set<UUID> ids) {
        UUID uuid = uuid(tag);
        if (uuid != null && !ids.add(uuid)) throw new IllegalStateException("重复实体 UUID：" + uuid);
        for (Tag passenger : tag.getListOrEmpty("Passengers")) if (passenger instanceof CompoundTag child) collectIds(child, ids);
    }
    private static Vec3 readVector(CompoundTag tag, String key) {
        ListTag list = tag.getListOrEmpty(key);
        if (list.size() != 3) throw new IllegalStateException("无效实体坐标：" + key);
        return new Vec3(list.getDoubleOr(0, 0), list.getDoubleOr(1, 0), list.getDoubleOr(2, 0));
    }
    private static void putVector(CompoundTag tag, String key, Vec3 value) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(value.x)); list.add(DoubleTag.valueOf(value.y)); list.add(DoubleTag.valueOf(value.z));
        tag.put(key, list);
    }
    private static void snapshotTicks(ServerLevel level, Cuboid box, List<ScheduledTick<Block>> blocks, List<ScheduledTick<Fluid>> fluids) {
        for (int cx = box.min().getX() >> 4; cx <= box.max().getX() >> 4; cx++) for (int cz = box.min().getZ() >> 4; cz <= box.max().getZ() >> 4; cz++) {
            var chunk = level.getChunk(cx, cz);
            ((LevelChunkTicks<Block>) chunk.getBlockTicks()).getAll().filter(t -> box.contains(t.pos())).forEach(t -> blocks.add(relativeTime(t, level.getGameTime())));
            ((LevelChunkTicks<Fluid>) chunk.getFluidTicks()).getAll().filter(t -> box.contains(t.pos())).forEach(t -> fluids.add(relativeTime(t, level.getGameTime())));
        }
    }
    private static <T> ScheduledTick<T> relativeTime(ScheduledTick<T> t, long time) { return new ScheduledTick<>(t.type(), t.pos(), Math.max(0, t.triggerTick() - time), t.priority(), t.subTickOrder()); }
    private static void validateProjection(SchematicPlacement selected, fi.dy.masa.litematica.world.WorldSchematic projection) {
        if (!selected.isEnabled() || !selected.isRenderingEnabled()) throw new IllegalStateException("请先启用所选放置及其渲染，再进入工作间。");
        for (SubRegionPlacement sub : selected.getAllSubRegionsPlacements()) if (sub.isEnabled() && !sub.isRenderingEnabled()) throw new IllegalStateException("请先启用该子区域的渲染，再进入工作间：" + sub.getName());
        if (projection == null) throw new IllegalStateException("投影尚未加载，请稍后重试。");
        var selectedBoxes = selected.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values();
        for (Box box : selectedBoxes) {
            BlockPos lo = PositionUtils.getMinCorner(box.getPos1(), box.getPos2());
            BlockPos hi = PositionUtils.getMaxCorner(box.getPos1(), box.getPos2());
            for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) {
                if (!projection.hasChunk(cx, cz) || PlacementManagerDaemonHandler.INSTANCE.hasAnyRebuildTasksFor(cx, cz)) throw new IllegalStateException("投影正在加载或重建，请稍后重试。");
            }
            for (SchematicPlacement other : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
                if (other == selected) continue;
                for (Box otherBox : other.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
                    BlockPos a = PositionUtils.getMinCorner(otherBox.getPos1(), otherBox.getPos2());
                    BlockPos b = PositionUtils.getMaxCorner(otherBox.getPos1(), otherBox.getPos2());
                    if (lo.getX() <= b.getX() && hi.getX() >= a.getX() && lo.getY() <= b.getY() && hi.getY() >= a.getY() && lo.getZ() <= b.getZ() && hi.getZ() >= a.getZ()) throw new IllegalStateException("所选投影与其他放置重叠，请先移开或关闭其他放置：" + other.getName());
                }
            }
        }
    }
    private boolean hasContent(ServerLevel level, Cuboid box, List<Entity> entities, Collection<BlockPos> entityStarts) {
        if (entities.stream().anyMatch(e -> box.contains(BlockPos.containing(e.position())))) return true;
        if (entityStarts.stream().anyMatch(box::contains)) return true;
        for (BlockPos p : BlockPos.betweenClosed(box.min(), box.max())) if (!isPlatformOnly(level, p) && !level.getBlockState(p).isAir()) return true;
        return false;
    }
    private boolean isPlatformOnly(ServerLevel level, BlockPos pos) {
        if (!platform.contains(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || movingPlatform.containsKey(pos);
    }
    private static void updateMetadata(LitematicaSchematic schematic) {
        long volume = 0, blocks = 0;
        BlockPos min = null, max = null;
        for (String name : schematic.getAreaPositions().keySet()) {
            BlockPos p = schematic.getSubRegionPosition(name);
            BlockPos end = p.offset(PositionUtils.getRelativeEndPositionFromAreaSize(schematic.getAreaSize(name)));
            min = min == null ? PositionUtils.getMinCorner(p, end) : PositionUtils.getMinCorner(min, PositionUtils.getMinCorner(p, end));
            max = max == null ? PositionUtils.getMaxCorner(p, end) : PositionUtils.getMaxCorner(max, PositionUtils.getMaxCorner(p, end));
            var container = schematic.getSubRegionContainer(name);
            var size = container.getSize();
            volume += Math.multiplyExact(Math.multiplyExact((long) size.getX(), size.getY()), size.getZ());
            for (int y = 0; y < size.getY(); y++) for (int z = 0; z < size.getZ(); z++) for (int x = 0; x < size.getX(); x++) if (!container.get(x,y,z).isAir() && !container.get(x,y,z).is(Blocks.STRUCTURE_VOID)) blocks++;
        }
        schematic.getMetadata().setRegionCount(schematic.getSubRegionCount());
        schematic.getMetadata().setTotalVolume(Math.toIntExact(volume));
        schematic.getMetadata().setTotalBlocks(Math.toIntExact(blocks));
        if (min != null) schematic.getMetadata().setEnclosingSize(max.subtract(min).offset(1,1,1));
    }

    public record Cuboid(BlockPos min, BlockPos max) {
        public boolean contains(BlockPos p) { return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY() && p.getZ() >= min.getZ() && p.getZ() <= max.getZ(); }
        public long volume() { return Math.multiplyExact(Math.multiplyExact((long)max.getX()-min.getX()+1, (long)max.getY()-min.getY()+1), (long)max.getZ()-min.getZ()+1); }
    }
    private static long preflightOriginal(LitematicaSchematic schematic) {
        List<Cuboid> boxes = new ArrayList<>();
        for (String name : schematic.getAreaPositions().keySet()) {
            var container = schematic.getSubRegionContainer(name);
            if (container == null) throw new IllegalStateException("无效的原区域容器：" + name);
            var size = container.getSize();
            if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) throw new IllegalStateException("无效的原区域尺寸：" + name);
            boxes.add(new Cuboid(BlockPos.ZERO, new BlockPos(size.getX()-1,size.getY()-1,size.getZ()-1)));
        }
        return preflight(boxes,0);
    }
    public static long preflight(Collection<Cuboid> boxes, long baselineVolume) {
        try {
            long volume = baselineVolume;
            for (Cuboid box : boxes) {
                long size = box.volume();
                if (size <= 0 || size >= Integer.MAX_VALUE) throw new ArithmeticException("container format limit");
                volume = Math.addExact(volume, size);
            }
            Runtime runtime = Runtime.getRuntime();
            long remaining = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
            // ponytail: dense snapshots reserve 64 bytes/cell and one third of free heap; sparse chunk export if this ceiling matters.
            if (Math.multiplyExact(volume,64) > remaining / 3) throw new ArithmeticException("heap budget");
            return volume;
        } catch (ArithmeticException tooLarge) {
            throw new IllegalStateException("投影或新增范围过大，无法安全生成候选；请移除远距离新增内容后重试。", tooLarge);
        }
    }
    public static List<Cuboid> subtract(Cuboid a, Cuboid b) {
        BlockPos lo = PositionUtils.getMaxCorner(a.min(), b.min());
        BlockPos hi = PositionUtils.getMinCorner(a.max(), b.max());
        if (lo.getX() > hi.getX() || lo.getY() > hi.getY() || lo.getZ() > hi.getZ()) return List.of(a);
        List<Cuboid> result = new ArrayList<>();
        if (a.min().getX() < lo.getX()) result.add(new Cuboid(a.min(), new BlockPos(lo.getX()-1,a.max().getY(),a.max().getZ())));
        if (hi.getX() < a.max().getX()) result.add(new Cuboid(new BlockPos(hi.getX()+1,a.min().getY(),a.min().getZ()),a.max()));
        if (a.min().getY() < lo.getY()) result.add(new Cuboid(new BlockPos(lo.getX(),a.min().getY(),a.min().getZ()),new BlockPos(hi.getX(),lo.getY()-1,a.max().getZ())));
        if (hi.getY() < a.max().getY()) result.add(new Cuboid(new BlockPos(lo.getX(),hi.getY()+1,a.min().getZ()),new BlockPos(hi.getX(),a.max().getY(),a.max().getZ())));
        if (a.min().getZ() < lo.getZ()) result.add(new Cuboid(new BlockPos(lo.getX(),lo.getY(),a.min().getZ()),new BlockPos(hi.getX(),hi.getY(),lo.getZ()-1)));
        if (hi.getZ() < a.max().getZ()) result.add(new Cuboid(new BlockPos(lo.getX(),lo.getY(),hi.getZ()+1),new BlockPos(hi.getX(),hi.getY(),a.max().getZ())));
        return result;
    }

}
