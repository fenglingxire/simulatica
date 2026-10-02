package ml.pypals.simulatica.simulation.server;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 碰撞式边界：在 getEntityCollisions 注入四面隐形墙（上延 10 格）与底部地面，顶部另加封顶盖，仅实体移动可见；相邻区域相接面自动开洞（矩形减法）。
 * - 墙高与封顶：风弹等强击退会把生物抛到投影顶上方，此前墙的注入条件用区域包围盒做相交测试，生物一旦高于区域顶就完全脱离墙的碰撞范围，可从墙上方漂出；现封顶 + 相交测试改用延伸到顶盖的密封盒。
 */
/**
 * Collision-only walls, floor and ceiling around a simulated region.
 *
 * <p>The shapes are injected into {@code Level.getEntityCollisions}, so they stop anything that
 * moves (mobs, items, projectiles) but are invisible to everything else: block placement, ray
 * tracing and -- most importantly -- mob pathfinding never sees them. A mob's AI keeps believing
 * there is nothing special about the boundary, it simply cannot physically cross it. The floor
 * sits at the region's bottom face so anything falling inside lands on it, while the AI (which
 * paths over real blocks only) never seeks it out. A slab caps the region {@link #WALL_TOP_EXTRA}
 * blocks above its top, so even the strongest knockback cannot lob anything over the walls.</p>
 *
 * <p>Where two regions touch, the shared face is left open so their contents can interact the way
 * the projection shows.</p>
 */
final class SimulationBoundary {

    private static final double THICKNESS = 0.25;

    /** How far the walls rise above the region before the ceiling slab seals the box. */
    private static final double WALL_TOP_EXTRA = 10.0;

    private SimulationBoundary() {
    }

    /** Y of the ceiling slab's underside. */
    static double ceilingY(AABB bounds) {
        return bounds.maxY + WALL_TOP_EXTRA;
    }

    /**
     * The volume the boundary actually seals: the region plus the wall band up to the ceiling.
     *
     * <p>Callers must run their "is this entity even near the boundary" test against THIS box,
     * not the raw region bounds -- an entity knocked above the region top is still inside the
     * sealed volume, and testing against the raw bounds would silently switch its collisions
     * off right where the escape happened.</p>
     */
    static AABB sealedBounds(AABB bounds) {
        return new AABB(bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, ceilingY(bounds) + THICKNESS, bounds.maxZ);
    }

    static void collect(List<ProjectionBridge> bridges, ProjectionBridge self, AABB bounds,
                        AABB query, List<VoxelShape> out) {
        double t = THICKNESS;

        // Floor: top surface exactly at the region's bottom face.
        addHorizontal(bridges, self, bounds, query, out, true);

        addWall(bridges, self, bounds, query, out, Face.WEST);
        addWall(bridges, self, bounds, query, out, Face.EAST);
        addWall(bridges, self, bounds, query, out, Face.NORTH);
        addWall(bridges, self, bounds, query, out, Face.SOUTH);

        // Ceiling: seals the top so knockback cannot clear the walls.
        addHorizontal(bridges, self, bounds, query, out, false);
    }

    /** Floors and caps inside another region in this placement must not divide a machine. */
    private static void addHorizontal(List<ProjectionBridge> bridges, ProjectionBridge self, AABB b,
                                      AABB query, List<VoxelShape> out, boolean floor) {
        double plane = floor ? b.minY : ceilingY(b);
        List<double[]> rects = new ArrayList<>();
        rects.add(new double[]{b.minX - THICKNESS, b.minZ - THICKNESS, b.maxX + THICKNESS, b.maxZ + THICKNESS});
        for (ProjectionBridge other : bridges) {
            if (other == self || other.level() != self.level()) continue;
            AABB o = other.region().simBounds();
            boolean covers = floor ? o.minY < plane && o.maxY >= plane
                    : o.minY <= plane && ceilingY(o) > plane;
            if (covers) rects = subtractAll(rects, o.minX, o.minZ, o.maxX, o.maxZ);
        }
        for (double[] rect : rects) addShape(out, query, rect[0], floor ? plane - THICKNESS : plane,
                rect[1], rect[2], floor ? plane : plane + THICKNESS, rect[3]);
    }

    private enum Face {
        WEST, EAST, NORTH, SOUTH
    }

    /**
     * One wall as one or more slabs. The wall is a rectangle in a 2D plane (u along the face,
     * v vertical); every neighboring region touching that face carves an opening out of it.
     */
    private static void addWall(List<ProjectionBridge> bridges, ProjectionBridge self, AABB b,
                                AABB query, List<VoxelShape> out, Face face) {
        double t = THICKNESS;
        double top = b.maxY + WALL_TOP_EXTRA;

        // Rect in (u, v): u runs along the face (z for west/east, x for north/south), v is y.
        double u0 = (face == Face.WEST || face == Face.EAST ? b.minZ : b.minX) - t;
        double u1 = (face == Face.WEST || face == Face.EAST ? b.maxZ : b.maxX) + t;
        double v0 = b.minY - t;
        double v1 = top;

        List<double[]> rects = new ArrayList<>();
        rects.add(new double[]{u0, v0, u1, v1});

        for (ProjectionBridge other : bridges) {
            if (other == self || other.level() != self.level() || rects.isEmpty()) {
                continue;
            }
            AABB o = other.region().simBounds();
            if (!touches(o, b, face)) {
                continue;
            }
            // The neighbor's footprint on the shared plane becomes a hole in the wall.
            double hu0 = face == Face.WEST || face == Face.EAST ? o.minZ : o.minX;
            double hu1 = face == Face.WEST || face == Face.EAST ? o.maxZ : o.maxX;
            rects = subtractAll(rects, hu0, o.minY, hu1, o.maxY);
        }

        for (double[] rect : rects) {
            double x0, x1, z0, z1;
            switch (face) {
                case WEST -> { x0 = b.minX - t; x1 = b.minX; z0 = rect[0]; z1 = rect[2]; }
                case EAST -> { x0 = b.maxX; x1 = b.maxX + t; z0 = rect[0]; z1 = rect[2]; }
                case NORTH -> { x0 = rect[0]; x1 = rect[2]; z0 = b.minZ - t; z1 = b.minZ; }
                default -> { x0 = rect[0]; x1 = rect[2]; z0 = b.maxZ; z1 = b.maxZ + t; }
            }
            addShape(out, query, x0, rect[1], z0, x1, rect[3], z1);
        }
    }

    /** Whether region {@code o} shares the given face plane of region {@code b}. */
    private static boolean touches(AABB o, AABB b, Face face) {
        return switch (face) {
            case WEST -> o.maxX == b.minX && overlaps(o.minZ, o.maxZ, b.minZ, b.maxZ)
                    && overlaps(o.minY, o.maxY, b.minY, b.maxY);
            case EAST -> o.minX == b.maxX && overlaps(o.minZ, o.maxZ, b.minZ, b.maxZ)
                    && overlaps(o.minY, o.maxY, b.minY, b.maxY);
            case NORTH -> o.maxZ == b.minZ && overlaps(o.minX, o.maxX, b.minX, b.maxX)
                    && overlaps(o.minY, o.maxY, b.minY, b.maxY);
            case SOUTH -> o.minZ == b.maxZ && overlaps(o.minX, o.maxX, b.minX, b.maxX)
                    && overlaps(o.minY, o.maxY, b.minY, b.maxY);
        };
    }

    private static boolean overlaps(double a0, double a1, double b0, double b1) {
        return a0 < b1 && b0 < a1;
    }

    /** Subtracts the hole rect from every rect, splitting each into up to four pieces. */
    private static List<double[]> subtractAll(List<double[]> rects, double hu0, double hv0,
                                              double hu1, double hv1) {
        List<double[]> result = new ArrayList<>();
        for (double[] r : rects) {
            double u0 = Math.max(r[0], hu0);
            double v0 = Math.max(r[1], hv0);
            double u1 = Math.min(r[2], hu1);
            double v1 = Math.min(r[3], hv1);
            if (u0 >= u1 || v0 >= v1) {
                result.add(r);
                continue;
            }
            if (r[1] < v0) result.add(new double[]{r[0], r[1], r[2], v0});
            if (v1 < r[3]) result.add(new double[]{r[0], v1, r[2], r[3]});
            if (r[0] < u0) result.add(new double[]{r[0], v0, u0, v1});
            if (u1 < r[2]) result.add(new double[]{u1, v0, r[2], v1});
        }
        return result;
    }

    private static void addShape(List<VoxelShape> out, AABB query, double x0, double y0, double z0,
                                 double x1, double y1, double z1) {
        if (x1 - x0 < 1.0E-7 || y1 - y0 < 1.0E-7 || z1 - z0 < 1.0E-7) {
            return;
        }
        AABB box = new AABB(x0, y0, z0, x1, y1, z1);
        if (box.intersects(query)) {
            out.add(Shapes.create(box));
        }
    }
}
