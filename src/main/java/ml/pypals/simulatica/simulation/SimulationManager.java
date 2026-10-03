package ml.pypals.simulatica.simulation;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.EntityUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.config.SimulaticaConfigs;
import ml.pypals.simulatica.carpet.BotManager;
import ml.pypals.simulatica.simulation.server.LeftoverStore;
import ml.pypals.simulatica.simulation.server.ProjectionBridge;
import ml.pypals.simulatica.simulation.server.SimulationServer;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import ml.pypals.simulatica.simulation.server.SimulationRegion;
import ml.pypals.simulatica.simulation.server.SimulationClock;
import ml.pypals.simulatica.counter.HopperCounter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 模拟停止后生物留存：leftovers 列表 + 区块重建后重发 + 再次启动时由新桥接管（原实体不动，只交接 UUID）
 * - stopSimulation 调用 LeftoverStore 存盘；attach 传入 preserved UUID 防重复复制
 * - 新增掉落物吸收 absorbItems()（含经验球与箭矢，含创造模式射出的 CREATIVE_ONLY 箭矢）与越界实体清理 purgeEscapedEntities()
 */
public class SimulationManager {
    // Stopped worlds keep frozen leftover entities until restart or disconnect.
    private final Map<SchematicPlacement, SimulationLevel> levels = new LinkedHashMap<>();

    /** Ticks a changed placement must hold still before it is even considered, so a drag does not thrash. */
    private static final int SETTLE_TICKS = 5;

    /** After this long the projection is used as-is. Never simulating is worse than simulating early. */
    private static final int MAX_WAIT_TICKS = 100;

    private static final SimulationManager INSTANCE = new SimulationManager();
    private final Map<SchematicPlacement, Simulation> active = new LinkedHashMap<>();

    private boolean itemAbsorption;
    private final Map<SchematicPlacement, Boolean> itemAbsorptionOverrides = new LinkedHashMap<>();
    /** World settings chosen before the placement's simulation world exists; applied when it is created. */
    private final Map<SchematicPlacement, int[]> pendingWorldSettings = new LinkedHashMap<>();

    public enum WorldSetting { TIME, WEATHER, DIFFICULTY }
    private static final List<net.minecraft.resources.ResourceKey<net.minecraft.world.clock.ClockTimeMarker>> TIME_MARKERS = List.of(
            net.minecraft.world.clock.ClockTimeMarkers.DAY, net.minecraft.world.clock.ClockTimeMarkers.NOON,
            net.minecraft.world.clock.ClockTimeMarkers.NIGHT, net.minecraft.world.clock.ClockTimeMarkers.MIDNIGHT);
    @Nullable private SchematicPlacement lastTpsPlacement;

    /**
     * Entities left behind when a simulation stops. They keep their last simulated state and
     * stay visible in the projection until the region is simulated again or the world closes.
     */
    private final List<LeftoverEntities> leftovers = new ArrayList<>();

    private record LeftoverEntities(SimulationLevel level, String label, AABB bounds, List<Entity> entities) {
    }

    private SimulationManager() {}

    public static SimulationManager getInstance() { return INSTANCE; }

    /**
     * The item-absorption toggle: when on, simulated item entities touching the player vanish
     * from the simulation without entering the inventory.
     */
    public boolean setItemAbsorption(@Nullable Boolean enabled) {
        this.itemAbsorption = enabled != null ? enabled : !this.itemAbsorption;
        itemAbsorptionOverrides.clear();
        return this.itemAbsorption;
    }

    public boolean isItemAbsorption() {
        return this.itemAbsorption;
    }

    public boolean isItemAbsorption(SchematicPlacement placement) {
        return itemAbsorptionOverrides.getOrDefault(placement, itemAbsorption);
    }

    public void setItemAbsorption(SchematicPlacement placement, boolean enabled) {
        itemAbsorptionOverrides.put(placement, enabled);
    }

    /**
     * Discards every simulated entity that ended up outside its region's bounds.
     *
     * <p>The boundary walls make escapes physically impossible through movement, but teleports
     * (chorus fruit, endermen) and entities left over from before the walls existed can still
     * sit outside, invisible in the projection and falling through the void. Returns the number
     * removed.</p>
     */
    public int purgeEscapedEntities() {
        return purgeEscapedEntities(null);
    }

    public int purgeEscapedEntities(@Nullable SchematicPlacement placement) {
        int removed = 0;
        var simulation = placement == null ? null : getSimulations(placement);
        Collection<ProjectionBridge> bridges = placement == null ? getAllSimulations()
                : simulation == null ? List.of() : simulation.values();
        Map<SimulationLevel, java.util.Set<java.util.UUID>> visited = new java.util.HashMap<>();
        for (ProjectionBridge bridge : bridges) {
            for (Entity entity : bridge.entities()) {
                if (entity instanceof EnderDragonPart
                        || !visited.computeIfAbsent(bridge.level(), key -> new java.util.HashSet<>()).add(entity.getUUID())) {
                    continue;
                }
                boolean inside = bridges.stream().anyMatch(other -> other.level() == bridge.level()
                        && other.region().simBounds().contains(entity.getBoundingBox().getCenter()));
                if (!inside) {
                    entity.discard();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Ticks between "cannot move there" notices, so holding an arrow key does not spam. */
    private static final int BLOCKED_NOTICE_TICKS = 40;

    private record RegionBox(BlockPos min, BlockPos max) {

        boolean overlaps(RegionBox other) {
            return this.min.getX() <= other.max.getX() && this.max.getX() >= other.min.getX()
                    && this.min.getY() <= other.max.getY() && this.max.getY() >= other.min.getY()
                    && this.min.getZ() <= other.max.getZ() && this.max.getZ() >= other.min.getZ();
        }
    }

    private record SubRegionTransform(BlockPos position, Rotation rotation, Mirror mirror, boolean enabled) {
    }

    /** Placement and sub-region transforms at the last accepted simulation footprint. */
    private record PlacementTransform(BlockPos origin, Rotation rotation, Mirror mirror, boolean enabled,
                                      Map<String, SubRegionTransform> regions) {

        static PlacementTransform of(SchematicPlacement placement) {
            Map<String, SubRegionTransform> regions = new LinkedHashMap<>();
            for (SubRegionPlacement region : placement.getAllSubRegionsPlacements()) {
                regions.put(region.getName(), new SubRegionTransform(region.getPos().immutable(),
                        region.getRotation(), region.getMirror(), region.isEnabled()));
            }
            return new PlacementTransform(placement.getOrigin().immutable(), placement.getRotation(),
                    placement.getMirror(), placement.isEnabled(), Map.copyOf(regions));
        }

        boolean sameOrientationAndRegions(PlacementTransform other) {
            return rotation == other.rotation && mirror == other.mirror && enabled == other.enabled
                    && regions.equals(other.regions);
        }

        void restore(SchematicPlacement placement) {
            // A locked placement cannot have moved, and the setters only touch the feedback
            // consumer on that path, which Litematica itself calls with null.
            if (placement.isLocked()) {
                return;
            }
            placement.setOrigin(this.origin, InfoUtils.INFO_MESSAGE_CONSUMER);
            placement.setRotation(this.rotation, null);
            placement.setMirror(this.mirror, null);
            placement.setEnabled(this.enabled);
            regions.forEach((name, transform) -> {
                SubRegionPlacement region = placement.getRelativeSubRegionPlacement(name);
                if (region == null) return;
                placement.setSubRegionRotation(name, transform.rotation(), null);
                placement.setSubRegionMirror(name, transform.mirror(), null);
                // moveSubRegionTo takes a world position, not a relative offset.
                BlockPos relative = PositionUtils.getTransformedBlockPos(transform.position(), mirror, rotation);
                placement.moveSubRegionTo(name, origin.offset(relative), InfoUtils.INFO_MESSAGE_CONSUMER);
                placement.setSubRegionsEnabledState(transform.enabled(), List.of(region), null);
            });
        }
    }

    private static final class Simulation {
        final Map<String, ProjectionBridge> bridges = new LinkedHashMap<>();
        Map<String, RegionBox> boxes;
        PlacementTransform transform;
        boolean pending = true;
        int settle;
        int waited;
        int blockedNotice;
        boolean pushAfterWait;
        @Nullable String waitReason;

        Simulation(Map<String, RegionBox> boxes, PlacementTransform transform) {
            this.boxes = boxes;
            this.transform = transform;
        }
    }

    public void tick() {
        SimulationServer server = SimulationServer.getRunning();
        if (server == null) {
            return;
        }

        followPlacements(server);
        levels.forEach((placement, level) -> {
            if (!placement.getName().equals(level.projectionName())) {
                level.setProjectionName(placement.getName());
                level.clock.setTarget(TpsSettings.get(placement));
                Simulation simulation = active.get(placement);
                if (simulation != null) simulation.bridges.forEach((name, bridge) -> bridge.setLabel(placement.getName() + "/" + name));
            }
        });
        server.tickSimulation();
        absorbItems();
    }

    /**
     * Removes simulated drops that touch the player, the way vanilla pickup would collect them --
     * except nothing reaches the inventory and no experience is granted, so a simulation never
     * leaks into the real save. Items with an active pickup delay are left alone so machines can
     * still take them.
     *
     * <p>Experience orbs and arrows are drops too, and being unable to clear them left orbs and
     * spent arrows piling up on the boundary floor. Both are removed like items: their value and
     * their stack belong to the simulation, not to the player. Arrows still shaking from a fresh
     * shot are skipped, matching vanilla {@code playerTouch}.</p>
     */
    private void absorbItems() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        AABB reach = client.player.getBoundingBox().inflate(1.0, 0.5, 1.0);
        boolean pickedItem = false;
        boolean pickedExperience = false;

        // Queried on the simulation levels rather than through a bridge's tracked set: that set is
        // clipped to the region box, so a drop resting on the boundary fell outside it and was
        // never absorbed.
        for (var entry : levels.entrySet()) {
            if (!active.containsKey(entry.getKey()) || !isItemAbsorption(entry.getKey())) continue;
            for (Entity entity : entry.getValue().getEntitiesOfClass(Entity.class, reach,
                    candidate -> !candidate.isRemoved() && !(candidate instanceof Player))) {
                if (entity instanceof ItemEntity item) {
                    if (item.hasPickUpDelay()) {
                        continue;
                    }
                    item.discard();
                    pickedItem = true;
                } else if (entity instanceof ExperienceOrb orb) {
                    orb.discard();
                    pickedExperience = true;
                } else if (entity instanceof AbstractArrow arrow) {
                    // 26.2 exposes the pickup rule as a public field; getPickupItem() is protected.
                    // Arrows the player shot in creative count too: creative sets CREATIVE_ONLY,
                    // and a filter for ALLOWED alone left every player-shot arrow stuck in the
                    // machine forever. Their item still goes to nobody, as with the other drops.
                    if (arrow.shakeTime > 0) {
                        continue;
                    }
                    if (arrow.pickup != AbstractArrow.Pickup.ALLOWED
                            && arrow.pickup != AbstractArrow.Pickup.CREATIVE_ONLY) {
                        continue;
                    }
                    arrow.discard();
                    pickedItem = true;
                }
            }
        }

        if (pickedItem) {
            float pitch = (client.level.getRandom().nextFloat() - client.level.getRandom().nextFloat()) * 0.7F + 1.0F;
            client.level.playLocalSound(client.player.getX(), client.player.getY(), client.player.getZ(),
                    SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, pitch * 2.0F, false);
        }
        if (pickedExperience) {
            float pitch = (client.level.getRandom().nextFloat() - client.level.getRandom().nextFloat()) * 0.7F + 1.0F;
            client.level.playLocalSound(client.player.getX(), client.player.getY(), client.player.getZ(),
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.1F, pitch * 2.0F, false);
        }
    }

    private void followPlacements(SimulationServer server) {
        // Drop placements that were deleted in Litematica's UI and reloaded under the same name.
        // Litematica creates a fresh SchematicPlacement object on reload, so the old one is no
        // longer in its list -- but its bridge still occupies the region here, and the new
        // placement's attach then fails with "Region overlaps the already-simulated ...". Detach
        // the orphan before the new placement is attached below.
        List<SchematicPlacement> loaded = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
        Iterator<Map.Entry<SchematicPlacement, Simulation>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SchematicPlacement, Simulation> entry = iterator.next();
            if (!loaded.contains(entry.getKey())) {
                BotManager.removeAll(entry.getKey());
                detachSimulation(entry.getValue(), server);
                iterator.remove();
            }
        }

        for (Map.Entry<SchematicPlacement, Simulation> entry : active.entrySet()) {
            SchematicPlacement placement = entry.getKey();
            Simulation simulation = entry.getValue();
            Map<String, RegionBox> current = boxesOf(placement);
            PlacementTransform transform = PlacementTransform.of(placement);

            if (simulation.blockedNotice > 0) {
                simulation.blockedNotice--;
            }

            if (!current.equals(simulation.boxes) || !transform.equals(simulation.transform)) {
                if (overlapsAnother(placement, current)) {
                    simulation.transform.restore(placement);
                    noticeBlocked(placement, simulation);

                    repushNeighbours(placement, simulation.boxes, current);
                    if (!simulation.bridges.isEmpty()) {
                        simulation.pending = true;
                        simulation.pushAfterWait = true;
                        simulation.settle = SETTLE_TICKS;
                        simulation.waited = 0;
                    }
                    continue;
                }

                BlockPos delta = simulation.transform.sameOrientationAndRegions(transform)
                        ? pureTranslation(simulation.boxes, current) : null;
                if (delta != null && !simulation.bridges.isEmpty()) {
                    // Carry the running machine across rather than starting it over.
                    Map<ProjectionBridge, SimulationRegion> moves = new LinkedHashMap<>();
                    simulation.bridges.forEach((name, bridge) -> {
                        RegionBox box = current.get(name);
                        if (box != null) {
                            moves.put(bridge, new SimulationRegion(bridge.region().dimension(), box.min(), box.max(), 0, 0));
                        }
                    });
                    server.moveRegions(moves);
                    simulation.pushAfterWait = true;
                } else {
                    BotManager.removeAll(placement);
                    SimulationLevel level = levels.get(placement);
                    if (level != null) discardEntities(level);
                    simulation.bridges.values().forEach(server::detach);
                    simulation.bridges.values().forEach(ProjectionBridge::discardScheduledUpdates);
                    simulation.bridges.clear();
                    simulation.pushAfterWait = false;
                    // An earlier rebuild may have been overwritten by the old simulation,
                    // or captured an intermediate transform while multiple setters ran.
                    DataManager.getSchematicPlacementManager().markChunksForRebuild(placement);
                }

                repushNeighbours(placement, simulation.boxes, current);
                simulation.boxes = current;
                simulation.transform = transform;
                simulation.settle = SETTLE_TICKS;
                simulation.waited = 0;
                simulation.pending = true;
            } else if (simulation.pending) {
                if (simulation.settle > 0) {
                    simulation.settle--;
                    continue;
                }

                simulation.waitReason = projectionBlocker(current);
                if (simulation.waitReason != null && ++simulation.waited < MAX_WAIT_TICKS) {
                    continue;
                }
                if (simulation.waitReason != null) {
                    Simulatica.LOGGER.warn("[Simulatica] Simulating '{}' anyway after waiting {} ticks: {}",
                            placement.getName(), simulation.waited, simulation.waitReason);
                }

                if (simulation.pushAfterWait) {
                    // Litematica re-placed the schematic at the new position; put the simulation's
                    // own state back over the top of it.
                    simulation.bridges.values().forEach(ProjectionBridge::pushToProjection);
                    simulation.pushAfterWait = false;
                } else {
                    attach(server, placement, simulation);
                }

                simulation.pending = false;
                simulation.waited = 0;
                simulation.waitReason = null;
            }
        }
    }

    /**
     * Whether a placement's new footprint would share space with another running simulation.
     * The offset every sub region moved by, or null if this was not a plain move.
     *
     * <p>Rotating, mirroring or resizing changes what the contents should be, not just where they
     * are, so those still go through a rebuild from the schematic.</p>
     */
    /**
     * Makes every other simulation sharing a chunk with a moved placement write itself out again.
     *
     * <p>Litematica rebuilds a chunk from the schematics whenever a placement over it moves, which
     * throws away what any other simulation had written there. Two machines that meet inside one
     * chunk are the visible case: move one away and the other's half of that chunk reverts.</p>
     */
    private void repushNeighbours(SchematicPlacement moved, Map<String, RegionBox> before,
                                  Map<String, RegionBox> after) {
        LongSet touched = new LongOpenHashSet();
        collectChunks(before, touched);
        collectChunks(after, touched);

        for (Map.Entry<SchematicPlacement, Simulation> entry : active.entrySet()) {
            if (entry.getKey() == moved) continue;

            Simulation other = entry.getValue();
            if (other.bridges.isEmpty() || !sharesChunk(other.boxes, touched)) continue;

            other.pending = true;
            other.pushAfterWait = true;
            other.settle = SETTLE_TICKS;
            other.waited = 0;
        }
    }

    private static void collectChunks(Map<String, RegionBox> boxes, LongSet into) {
        for (RegionBox box : boxes.values()) {
            for (int cx = box.min().getX() >> 4; cx <= box.max().getX() >> 4; cx++) {
                for (int cz = box.min().getZ() >> 4; cz <= box.max().getZ() >> 4; cz++) {
                    into.add(ChunkPos.pack(cx, cz));
                }
            }
        }
    }

    private static boolean sharesChunk(Map<String, RegionBox> boxes, LongSet chunks) {
        for (RegionBox box : boxes.values()) {
            for (int cx = box.min().getX() >> 4; cx <= box.max().getX() >> 4; cx++) {
                for (int cz = box.min().getZ() >> 4; cz <= box.max().getZ() >> 4; cz++) {
                    if (chunks.contains(ChunkPos.pack(cx, cz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos pureTranslation(Map<String, RegionBox> before, Map<String, RegionBox> after) {
        if (!before.keySet().equals(after.keySet()) || before.isEmpty()) {
            return null;
        }

        BlockPos delta = null;
        for (Map.Entry<String, RegionBox> entry : before.entrySet()) {
            RegionBox from = entry.getValue();
            RegionBox to = after.get(entry.getKey());

            BlockPos size = from.max().subtract(from.min());
            if (!size.equals(to.max().subtract(to.min()))) {
                return null;
            }

            BlockPos moved = to.min().subtract(from.min());
            if (delta == null) {
                delta = moved;
            } else if (!delta.equals(moved)) {
                return null;
            }
        }
        return !delta.equals(BlockPos.ZERO) ? delta : null;
    }

    private boolean overlapsAnother(SchematicPlacement moving, Map<String, RegionBox> boxes) {
        for (Map.Entry<SchematicPlacement, Simulation> entry : active.entrySet()) {
            if (entry.getKey() == moving) continue;

            for (RegionBox other : entry.getValue().boxes.values()) {
                for (RegionBox box : boxes.values()) {
                    if (box.overlaps(other)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void noticeBlocked(SchematicPlacement placement, Simulation simulation) {
        if (simulation.blockedNotice > 0 || Minecraft.getInstance().player == null) {
            return;
        }
        simulation.blockedNotice = BLOCKED_NOTICE_TICKS;
        Minecraft.getInstance().player.sendOverlayMessage(
                Component.literal("'" + placement.getName() + "' cannot overlap another running simulation"));
    }

    @Nullable
    private static String projectionBlocker(Map<String, RegionBox> boxes) {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return "no projection world";
        }

        for (RegionBox box : boxes.values()) {
            for (int cx = box.min().getX() >> 4; cx <= box.max().getX() >> 4; cx++) {
                for (int cz = box.min().getZ() >> 4; cz <= box.max().getZ() >> 4; cz++) {
                    if (!projection.hasChunk(cx, cz)) {
                        return "projection chunk [" + cx + ", " + cz + "] not loaded";
                    }
                    // Rebuild tasks only. The unload, deferred and "other" queues can hold entries
                    // that are not what a re-placed schematic is waiting on.
                    if (PlacementManagerDaemonHandler.INSTANCE.hasAnyRebuildTasksFor(cx, cz)) {
                        return "projection chunk [" + cx + ", " + cz + "] still rebuilding";
                    }
                }
            }
        }
        return null;
    }

    public List<String> describePending() {
        List<String> lines = new ArrayList<>();
        active.forEach((placement, simulation) -> {
            if (simulation.pending) {
                lines.add(placement.getName() + " -- "
                        + (simulation.waitReason != null ? simulation.waitReason : "attaching")
                        + " (" + simulation.waited + " tick(s))");
            }
        });
        return lines;
    }

    private void attach(SimulationServer server, SchematicPlacement placement, Simulation simulation) {
        var level = levels.computeIfAbsent(placement, key -> {
            var created = server.createProjectionLevel(Minecraft.getInstance().level.dimension(), key.getName());
            created.clock.setTarget(TpsSettings.get(key));
            int[] pending = pendingWorldSettings.remove(key);
            if (pending != null) {
                for (WorldSetting setting : WorldSetting.values()) {
                    if (pending[setting.ordinal()] >= 0) applyWorldSetting(created, setting, pending[setting.ordinal()]);
                }
            }
            return created;
        });
        level.clock.suspend();
        for (Map.Entry<String, RegionBox> entry : simulation.boxes.entrySet()) {
            RegionBox box = entry.getValue();
            try {
                assert Minecraft.getInstance().level != null;
                simulation.bridges.put(entry.getKey(), server.attach(
                        level,
                        box.min(), box.max(),
                        placement.getName() + "/" + entry.getKey(),
                        preserveLeftoversIntersecting(level, box),
                        LeftoverStore.prepareKey(placement, entry.getKey())));
            } catch (Exception e) {
                Simulatica.LOGGER.error("[Simulatica] Failed to simulate region '{}': {}",
                        entry.getKey(), e.getMessage(), e);
            }
        }
    }

    private static Map<String, RegionBox> boxesOf(SchematicPlacement placement) {
        Map<String, RegionBox> result = new LinkedHashMap<>();
        if (!placement.isEnabled()) return result;
        for (Map.Entry<String, Box> entry :
                placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).entrySet()) {
            Box box = entry.getValue();
            if (box.getPos1() == null || box.getPos2() == null) continue;
            result.put(entry.getKey(), new RegionBox(minCorner(box), maxCorner(box)));
        }
        return result;
    }

    public record Target(SchematicPlacement placement, String regionName, @Nullable ProjectionBridge bridge) {
    }

    @Nullable
    public Target findTarget(BlockPos pos) {
        Target fallback = null;
        for (SchematicPlacementManager.PlacementPart part :
                DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(pos)) {
            if (!part.getBox().contains(pos)) continue;

            SchematicPlacement placement = part.getPlacement();
            String regionName = part.getSubRegionName();
            Simulation simulation = active.get(placement);
            ProjectionBridge bridge = simulation != null ? simulation.bridges.get(regionName) : null;

            if (bridge != null) return new Target(placement, regionName, bridge);
            if (fallback == null) fallback = new Target(placement, regionName, null);
        }
        return fallback;
    }


    public void republishEntities() {
        SimulationServer server = SimulationServer.getRunning();
        if (server != null) {
            server.republishEntities();
        }

        // Litematica drops a chunk's entities on every rebuild; leftovers have no bridge left
        // to hand them back, so the manager does it for them.
        if (!this.leftovers.isEmpty()) {
            WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
            if (projection != null) {
                for (LeftoverEntities leftover : this.leftovers) {
                    leftover.entities().forEach(projection::addFreshEntity);
                }
            }
        }
    }

    public boolean isSimulatedChunk(int chunkX, int chunkZ) {
        SimulationServer server = SimulationServer.getRunning();
        return server != null && server.isSimulatedChunk(chunkX, chunkZ);
    }

    public void startSimulation(SchematicPlacement placement) {
        if (active.containsKey(placement)) {
            Simulatica.LOGGER.warn("[Simulatica] Simulation already running for placement '{}'",
                    placement.getName());
            return;
        }
        if (placement.getSchematic() == null) {
            Simulatica.LOGGER.warn("[Simulatica] Placement '{}' has no schematic loaded, nothing to simulate",
                    placement.getName());
            return;
        }
        if (Minecraft.getInstance().level == null) {
            return;
        }

        SimulationServer server;
        try {
            server = SimulationServer.getOrCreate();
        } catch (Exception e) {
            Simulatica.LOGGER.error("[Simulatica] Could not start the simulation server", e);
            return;
        }

        // Attaching is left to the tick: the projection may not be built yet, and the readiness
        // check that handles a moved placement handles a far-away one just as well.
        active.put(placement, new Simulation(boxesOf(placement), PlacementTransform.of(placement)));
        ml.pypals.simulatica.workshop.EditedPlacementCache.simulationStateChanged(placement, true);
    }

    private boolean closingWorld;

    public void stopSimulation(SchematicPlacement placement) {
        if (!closingWorld) ml.pypals.simulatica.workshop.EditedPlacementCache.simulationStateChanged(placement, false);
        Simulation simulation = active.remove(placement);
        if (placement == lastTpsPlacement) lastTpsPlacement = null;
        if (simulation == null) {
            Simulatica.LOGGER.warn("[Simulatica] No active simulation for placement '{}'",
                    placement.getName());
            return;
        }

        // Fake players are simulation-owned and must not linger in the scratch world once the
        // placement stops simulating.
        BotManager.removeAll(placement);
        if (levels.containsKey(placement)) levels.get(placement).clock.suspend();

        SimulationServer server = SimulationServer.getRunning();
        if (server != null) {
            detachSimulation(simulation, server);
        }
        Simulatica.LOGGER.info("[Simulatica] Stopped all simulations for placement '{}'",
                placement.getName());
    }

    /** Replaces edited simulation contents without saving the superseded entities as leftovers. */
    public void discardSimulationState(SchematicPlacement placement) {
        Simulation simulation = active.remove(placement);
        SimulationLevel level = levels.remove(placement);
        BotManager.removeAll(placement);
        if (level != null) {
            discardEntities(level);
            level.clock.suspend();
        }
        SimulationServer server = SimulationServer.getRunning();
        if (simulation != null && server != null) simulation.bridges.values().forEach(server::detach);
    }

    private void discardEntities(SimulationLevel level) {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        List<Entity> entities = new ArrayList<>();
        level.getAllEntities().forEach(entities::add);
        for (Entity entity : entities) {
            if (ml.pypals.simulatica.simulation.server.SimulationViewer.isViewer(entity)) continue;
            if (projection != null) ProjectionBridge.unpublishEntity(projection, entity);
            entity.discard();
        }
        leftovers.removeIf(leftover -> leftover.level() == level);
    }

    /**
     * Detaches every bridge of a simulation, keeping its live entities as leftovers the way
     * {@link #stopSimulation} does. Shared by the explicit stop path and the orphan-cleanup path
     * in {@link #followPlacements}.
     */
    private void detachSimulation(Simulation simulation, SimulationServer server) {
        java.util.Set<java.util.UUID> stored = new java.util.HashSet<>();
        for (ProjectionBridge bridge : simulation.bridges.values()) {
            List<Entity> kept = new ArrayList<>();
            for (Entity entity : bridge.entities()) {
                // Players (fake bots) are reclaimed separately and never stored as leftovers:
                // they cannot round-trip through the generic entity NBT loader.
                if (!entity.isRemoved() && !(entity instanceof EnderDragonPart)
                        && !(entity instanceof Player) && stored.add(entity.getUUID())) {
                    kept.add(entity);
                }
            }
            if (!kept.isEmpty()) {
                LeftoverStore.save(bridge.storageKey(), kept, bridge.level());
                this.leftovers.add(new LeftoverEntities(bridge.level(), bridge.label(), bridge.region().simBounds(), kept));
            }
            server.detach(bridge);
        }
    }

    /**
     * Drops every leftover without keeping it (used when the world/connection closes).
     *
     * <p>Note {@link #stopAll} runs first on disconnect, and it has already written every
     * leftover to the store -- the entities themselves only live in the scratch world,
     * which is deleted on shutdown.</p>
     */
    public void clearLeftovers() {
        for (LeftoverEntities leftover : this.leftovers) {
            leftover.entities().forEach(Entity::discard);
        }
        this.leftovers.clear();
        this.levels.clear();
        this.itemAbsorptionOverrides.clear();
        this.pendingWorldSettings.clear();
        this.lastTpsPlacement = null;
        HopperCounter.clearAll();
    }

    /**
     * Hands leftovers intersecting a region that is about to be attached again over to the
     * new bridge.
     *
     * <p>The entities themselves are untouched: they were never discarded when their bridge
     * detached, so the new bridge's tracking simply picks them up again (same coordinates,
     * zero offset). Only the bookkeeping entry goes away. Their UUIDs are returned so the
     * attach can keep the projection's copies of them from being duplicated into the
     * simulation -- the live originals are the newer state.</p>
     */
    private java.util.Set<java.util.UUID> preserveLeftoversIntersecting(SimulationLevel level, RegionBox box) {
        java.util.Set<java.util.UUID> preserved = new java.util.HashSet<>();
        if (this.leftovers.isEmpty()) {
            return preserved;
        }
        this.leftovers.removeIf(leftover -> {
            if (leftover.level() != level || !intersects(leftover.bounds(), box)) {
                return false;
            }
            for (Entity entity : leftover.entities()) {
                if (!entity.isRemoved()) {
                    preserved.add(entity.getUUID());
                }
            }
            return true;
        });
        return preserved;
    }

    private static boolean intersects(AABB bounds, RegionBox box) {
        return bounds.minX <= box.max().getX() + 1 && bounds.maxX >= box.min().getX()
                && bounds.minZ <= box.max().getZ() + 1 && bounds.maxZ >= box.min().getZ()
                && bounds.minY <= box.max().getY() + 1 && bounds.maxY >= box.min().getY();
    }

    public void stopAll() {
        for (SchematicPlacement placement : new ArrayList<>(active.keySet())) {
            stopSimulation(placement);
        }
    }

    public boolean isSimulating(SchematicPlacement placement) {
        return active.containsKey(placement);
    }

    /** Connection cleanup preserves the user's desired state for cached edited placements. */
    public void stopAllForWorldChange() {
        boolean previous = closingWorld;
        closingWorld = true;
        try { stopAll(); }
        finally { closingWorld = previous; }
    }

    @Nullable
    public SimulationLevel projectionLevel(SchematicPlacement placement) {
        return levels.get(placement);
    }

    /**
     * Time period, weather or difficulty index of the placement's simulation world. A stopped
     * simulation keeps its world, so this works without running; before the world exists it
     * reports the value queued for creation, or -1 for the default.
     */
    public int worldSetting(SchematicPlacement placement, WorldSetting setting) {
        SimulationLevel level = levels.get(placement);
        if (level == null) {
            int[] pending = pendingWorldSettings.get(placement);
            return pending == null ? -1 : pending[setting.ordinal()];
        }
        return switch (setting) {
            case TIME -> Math.floorMod(level.getOverworldClockTime(), 24000) / 6000;
            case WEATHER -> level.getWeatherData().isThundering() ? 2 : level.getWeatherData().isRaining() ? 1 : 0;
            case DIFFICULTY -> level.getDifficulty().ordinal();
        };
    }

    public void setWorldSetting(SchematicPlacement placement, WorldSetting setting, int index) {
        SimulationLevel level = levels.get(placement);
        if (level != null) {
            applyWorldSetting(level, setting, index);
            return;
        }
        int[] pending = pendingWorldSettings.computeIfAbsent(placement, key -> new int[]{-1, -1, -1});
        pending[setting.ordinal()] = index;
    }

    private static void applyWorldSetting(SimulationLevel level, WorldSetting setting, int index) {
        switch (setting) {
            case TIME -> level.setSimulationTime(TIME_MARKERS.get(index));
            case WEATHER -> level.setSimulationWeather(index >= 1, index == 2);
            case DIFFICULTY -> level.setSimulationDifficulty(net.minecraft.world.Difficulty.values()[index]);
        }
    }

    public void setTps(SchematicPlacement placement, int tps) throws java.io.IOException {
        TpsSettings.set(placement, tps);
        if (levels.containsKey(placement)) levels.get(placement).clock.setTarget(tps);
    }

    public String describeTps(SchematicPlacement placement) {
        return placement.getName() + ": 目标 " + TpsSettings.get(placement) + " TPS";
    }

    public List<String> describeRates() {
        return active.keySet().stream().map(this::describeTps).toList();
    }

    public List<String> describeTpsHud() {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) return List.of();
        List<SchematicPlacement> running = active.entrySet().stream()
                .filter(entry -> levels.containsKey(entry.getKey()) && !entry.getValue().bridges.isEmpty())
                .map(Map.Entry::getKey).toList();
        if (!running.contains(lastTpsPlacement)) lastTpsPlacement = null;
        if (SimulaticaConfigs.TPS_MULTILINE.getBooleanValue()) {
            return running.stream().map(placement -> formatTpsHud(levels.get(placement).clock, placement.getName())).toList();
        }

        Entity camera = EntityUtils.getCameraEntity();
        if (camera != null) {
            Vec3 eye = camera.getEyePosition();
            Vec3 end = eye.add(camera.getViewVector(1.0F).scale(Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0));
            double nearest = Double.MAX_VALUE;
            for (SchematicPlacement placement : running) {
                for (ProjectionBridge bridge : active.get(placement).bridges.values()) {
                    var region = bridge.region();
                    AABB bounds = AABB.encapsulatingFullBlocks(region.worldMin(), region.worldMax());
                    var hit = bounds.contains(eye) ? java.util.Optional.of(eye) : bounds.clip(eye, end);
                    if (hit.isPresent() && eye.distanceToSqr(hit.get()) < nearest) {
                        nearest = eye.distanceToSqr(hit.get());
                        lastTpsPlacement = placement;
                    }
                }
            }
        }

        SchematicPlacement focus = lastTpsPlacement;
        if (focus == null) {
            SchematicPlacement selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
            if (running.contains(selected)) focus = selected;
        }
        List<SchematicPlacement> shown = focus != null ? List.of(focus) : running;
        return shown.stream().map(placement -> formatTpsHud(levels.get(placement).clock, placement.getName())).toList();
    }

    static String formatTpsHud(SimulationClock clock, String name) {
        String rate = ChatFormatting.AQUA + "Simulatica TPS: " + ChatFormatting.WHITE
                + String.format(Locale.ROOT, "%.1f", clock.actual()) + ChatFormatting.GRAY + "/" + clock.target();
        var font = Minecraft.getInstance().font;
        String label = font.width(name) <= 120 ? name : font.plainSubstrByWidth(name, 120 - font.width("…")) + "…";
        return (name.isBlank() ? rate : rate + " | " + label) + ChatFormatting.RESET;
    }

    public SimulationLevel commandLevel() {
        SchematicPlacement selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        if (active.containsKey(selected) && levels.containsKey(selected)) return levels.get(selected);
        if (active.size() == 1) {
            var level = levels.get(active.keySet().iterator().next());
            if (level != null) return level;
        }
        throw new IllegalStateException("请先选择一个已启动的投影。");
    }

    @Nullable
    public Map<String, ProjectionBridge> getSimulations(SchematicPlacement placement) {
        Simulation simulation = active.get(placement);
        return simulation != null ? simulation.bridges : null;
    }

    public Collection<ProjectionBridge> getAllSimulations() {
        List<ProjectionBridge> all = new ArrayList<>();
        for (Simulation simulation : active.values()) {
            all.addAll(simulation.bridges.values());
        }
        return Collections.unmodifiableList(all);
    }

    /** Placements attached but still waiting for their projection to be rebuilt. */
    public int getPendingCount() {
        return (int) active.values().stream().filter(simulation -> simulation.pending).count();
    }

    public int getActiveCount() {
        return active.values().stream().mapToInt(simulation -> simulation.bridges.size()).sum();
    }

    private static BlockPos minCorner(Box box) {
        assert box.getPos1() != null;
        assert box.getPos2() != null;
        return new BlockPos(
                Math.min(box.getPos1().getX(), box.getPos2().getX()),
                Math.min(box.getPos1().getY(), box.getPos2().getY()),
                Math.min(box.getPos1().getZ(), box.getPos2().getZ()));
    }

    private static BlockPos maxCorner(Box box) {
        assert box.getPos1() != null;
        assert box.getPos2() != null;
        return new BlockPos(
                Math.max(box.getPos1().getX(), box.getPos2().getX()),
                Math.max(box.getPos1().getY(), box.getPos2().getY()),
                Math.max(box.getPos1().getZ(), box.getPos2().getZ()));
    }
}
