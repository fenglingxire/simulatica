package ml.pypals.simulatica.simulation;

import ml.pypals.simulatica.simulation.server.ProjectionBridge;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import ml.pypals.simulatica.simulation.server.SimulationViewer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Optional;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 抽出模拟实体射线追踪，供攻击/交互与中键选取（刷怪蛋）共用
 * - 新增区域底部虚拟地面命中 traceFloor()，让模拟区域最底层可以放置方块
 * - 新增 resolveLevel()，返回玩家视线正瞄准的模拟世界（供投射物生成落点判定）
 */
/**
 * Ray tracing against simulated entities, shared by every entry point that aims at them.
 *
 * <p>Simulated mobs live in the simulation server level, not in the client level, so they never
 * show up in {@code Minecraft.hitResult} and vanilla picking cannot see them. Everything that
 * needs "the mob under the crosshair" goes through here.</p>
 */
public final class SimulationRaycast {

    /** Reach used for simulated interaction, in blocks. */
    public static final double REACH = 10.0;

    private SimulationRaycast() {
    }

    /** A simulated entity under the crosshair, with the exact point that was hit. */
    public record Hit(Entity entity, Vec3 location) {
    }

    @Nullable
    public static Hit traceEntity(Minecraft mc, double maxDistance) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) return null;
        if (mc.player == null) {
            return null;
        }

        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1.0F).scale(REACH));
        AABB swept = new AABB(eye, end).inflate(1.0);

        Entity closest = null;
        Vec3 closestLocation = null;
        double closestDistance = Math.min(maxDistance, REACH);

        // Queried on the simulation levels rather than through a bridge's tracked set: that set is
        // limited to the region box, so a mob nudged towards (or past) the boundary drops out of
        // it and the next swing silently falls through to the block-breaking branch.
        for (ProjectionBridge bridge : SimulationManager.getInstance().getAllSimulations()) {
            for (Entity entity : bridge.level().getEntitiesOfClass(Entity.class, swept,
                    candidate -> !candidate.isRemoved() && candidate.isPickable()
                            && !SimulationViewer.isViewer(candidate))) {
                AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
                Optional<Vec3> clip = box.clip(eye, end);
                if (clip.isEmpty()) {
                    continue;
                }

                double distance = eye.distanceTo(clip.get());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closest = entity;
                    closestLocation = clip.get();
                }
            }
        }
        return closest != null ? new Hit(closest, closestLocation) : null;
    }

    /**
     * A synthetic hit on the floor of a simulated region, so blocks can be placed down there.
     *
     * <p>The boundary floor is only a collision shape injected into {@code getEntityCollisions}:
     * mobs land on it, but there is no block for a ray trace to hit, so the bottom layer of a
     * region had no face to aim at and placing a block on it was impossible -- the vanilla
     * placement path is cancelled inside simulated regions, so nothing else picked it up either.
     * The trace points at the region's bottom layer itself, which is air in the simulation:
     * 26.2's {@code BlockPlaceContext} places <em>into</em> the clicked position when that state
     * can be replaced, so this lands the block exactly on the bottom layer. (Pointing one below
     * -- the pre-26.2 "block plus face" convention -- made the block land a layer BELOW the
     * region, where no bridge picks the change up: never rendered, never breakable.)</p>
     *
     * <p>Only floors are synthesised, never walls or ceilings: those already have schematic
     * blocks to aim at wherever the region is enclosed.</p>
     */
    @Nullable
    public static BlockHitResult traceFloor(Minecraft mc) {
        if (mc.player == null) {
            return null;
        }

        Vec3 eye = mc.player.getEyePosition();
        Vec3 view = mc.player.getViewVector(1.0F);
        if (view.y >= -1.0E-5) {
            return null;
        }

        BlockHitResult best = null;
        double bestDistance = REACH;

        // Region boxes are in simulation coordinates, which equal world coordinates: every region
        // is created with a zero offset (SimulationServer). Same assumption getEntityCollisions makes.
        for (ProjectionBridge bridge : SimulationManager.getInstance().getAllSimulations()) {
            AABB bounds = bridge.region().simBounds();
            double t = (bounds.minY - eye.y) / view.y;
            if (t < 0.0 || t > REACH) {
                continue;
            }

            Vec3 at = eye.add(view.scale(t));
            if (at.x < bounds.minX || at.x > bounds.maxX || at.z < bounds.minZ || at.z > bounds.maxZ) {
                continue;
            }

            double distance = eye.distanceTo(at);
            if (distance >= bestDistance) {
                continue;
            }
            bestDistance = distance;
            BlockPos floorPos = new BlockPos(Mth.floor(at.x), Mth.floor(bounds.minY), Mth.floor(at.z));
            best = new BlockHitResult(at, Direction.UP, floorPos, false);
        }
        return best;
    }

    /**
     * The simulation level the player is currently aiming into.
     *
     * <p>Walks the view ray against every region box and returns the level of the first region
     * crossed. When no region is crossed but exactly one simulation is running, that one is
     * returned as a fallback, so projectiles thrown from just outside a region still land inside
     * it. Returns {@code null} when there is nothing to aim at.</p>
     */
    @Nullable
    public static SimulationLevel resolveLevel(Minecraft mc) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) return null;
        if (mc.player == null) {
            return null;
        }

        Collection<ProjectionBridge> bridges = SimulationManager.getInstance().getAllSimulations();
        if (bridges.isEmpty()) {
            return null;
        }
        SimulationLevel fallback = bridges.size() == 1 ? bridges.iterator().next().level() : null;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1.0F).scale(REACH));

        SimulationLevel best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ProjectionBridge bridge : bridges) {
            Optional<Vec3> clip = bridge.region().simBounds().clip(eye, end);
            if (clip.isEmpty()) {
                continue;
            }
            double distance = eye.distanceTo(clip.get());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = bridge.level();
            }
        }
        return best != null ? best : fallback;
    }
}
