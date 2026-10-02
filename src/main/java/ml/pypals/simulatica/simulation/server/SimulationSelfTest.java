package ml.pypals.simulatica.simulation.server;

import fi.dy.masa.litematica.world.SchematicEntityLookup;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import ml.pypals.simulatica.mixin.WorldSchematicAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import ml.pypals.simulatica.mixin.simulation.SimPistonMovingBlockEntityAccessor;
import ml.pypals.simulatica.render.ProjectionPistonRenderer;
import fi.dy.masa.litematica.config.Configs;
import java.lang.reflect.Proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - ChunkPos.asLong → ChunkPos.pack（26.2 record 化）
 */
/**
 * Check that the simulation server actually simulates.
 */
public final class SimulationSelfTest {


    //Somewhere super far...
    private static final BlockPos ORIGIN = new BlockPos(1_000_040, 70, 1_000_040);

    private SimulationSelfTest() {}

    public static List<String> run() {
        List<String> results = new ArrayList<>();
        SimulationServer server;
        try {
            server = SimulationServer.getOrCreate();
        } catch (Exception e) {
            results.add("FAIL boot: " + e);
            return results;
        }
        results.add("PASS boot: dimensions " + server.levelKeys().size());

        SimulationLevel level = server.levelFor(Level.OVERWORLD);
        List<BlockPos> changed = new ArrayList<>();
        Consumer<BlockPos> counter = changed::add;
        level.addBlockChangeListener(counter);
        try {
            forceLoad(server, level, results);
            gravity(server, level, results);
            redstone(server, level, results);
            results.add(pistonRendering());

            results.add(changed.isEmpty()
                    ? "FAIL notify: sendBlockUpdated never fired"
                    : "PASS notify: " + changed.size() + " block change(s) reported");
        } catch (Exception e) {
            results.add("FAIL " + e);
        } finally {
            level.removeBlockChangeListener(counter);
            releaseChunks(level);
        }
        return results;
    }

    /** Exercises the real renderer, including textured and blue-overlay vertex buffers. */
    public static String pistonRendering() {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) return "FAIL piston rendering: no schematic world";
        Minecraft mc = Minecraft.getInstance();
        int[] geometry = {0};
        int[] order = {0};
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                SubmitNodeCollector.class.getClassLoader(), new Class<?>[]{SubmitNodeCollector.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("order")) {
                        order[0] = (int) args[0];
                        return proxy;
                    }
                    if (method.getName().equals("submitCustomGeometry")) {
                        RenderType type = (RenderType) args[1];
                        int expected = type == net.minecraft.client.renderer.rendertype.RenderTypes.translucentMovingBlock()
                                ? 0 : type.primitiveTopology() == com.mojang.blaze3d.PrimitiveTopology.QUADS ? 1 : 2;
                        if (order[0] != expected) throw new AssertionError("piston overlay rendered before its model");
                        order[0] = 0;
                        if (type.outputTarget() != net.minecraft.client.renderer.rendertype.RenderTypes.translucentMovingBlock().outputTarget()) {
                            throw new AssertionError("piston model and overlay must composite together");
                        }
                        try (ByteBufferBuilder memory = new ByteBufferBuilder(4096)) {
                            BufferBuilder buffer = new BufferBuilder(memory, type.primitiveTopology(), type.format());
                            ((SubmitNodeCollector.CustomGeometryRenderer) args[2]).render(
                                    ((PoseStack) args[0]).last().copy(), buffer);
                            try (var mesh = buffer.buildOrThrow()) {
                                if (mesh.drawState().vertexCount() == 0) throw new AssertionError("empty piston mesh");
                            }
                        }
                        geometry[0]++;
                    }
                    return null;
                });
        int cases = 0;
        for (Direction direction : Direction.values()) {
            for (boolean extending : new boolean[]{true, false}) {
                for (var carried : new Block[]{
                        Blocks.STONE, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.PISTON}) {
                    BlockState moving = Blocks.MOVING_PISTON.defaultBlockState()
                            .setValue(MovingPistonBlock.FACING, direction);
                    boolean source = carried == Blocks.PISTON;
                    BlockState payload = (source && extending ? Blocks.PISTON_HEAD : carried).defaultBlockState();
                    if (payload.hasProperty(BlockStateProperties.FACING)) {
                        payload = payload.setValue(BlockStateProperties.FACING, direction);
                    }
                    PistonMovingBlockEntity piston = new PistonMovingBlockEntity(
                            mc.player.blockPosition().above(), moving, payload, direction, extending, source);
                    piston.setLevel(projection);
                    ((SimPistonMovingBlockEntityAccessor) piston).sim$setProgressO(0);
                    ((SimPistonMovingBlockEntityAccessor) piston).sim$setProgress(0.5F);
                    var extracted = mc.getBlockEntityRenderDispatcher().tryExtractRenderState(piston, 0.5F, null, false);
                    if (!(extracted instanceof ProjectionPistonRenderer.State state) || !state.projected || state.block == null) {
                        throw new AssertionError("missing projected piston model: " + direction + "/" + extending + "/" + carried);
                    }
                    if (Math.abs(state.xOffset - piston.getXOff(0.5F)) > 0.0001F
                            || Math.abs(state.yOffset - piston.getYOff(0.5F)) > 0.0001F
                            || Math.abs(state.zOffset - piston.getZOff(0.5F)) > 0.0001F) {
                        throw new AssertionError("piston interpolation changed");
                    }
                    if (source && !extending && state.base == null) throw new AssertionError("missing retracting base");
                    state.blockColor = state.baseColor = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_MISSING.getColor();
                    mc.getBlockEntityRenderDispatcher().submit(state, new PoseStack(), collector, null);
                    cases++;
                }
            }
        }
        if (geometry[0] == 0) throw new AssertionError("no projected piston geometry submitted");
        return "PASS piston rendering: " + cases + " cases, " + geometry[0] + " nonempty meshes";
    }

    private static void releaseChunks(SimulationLevel level) {
        int cx = ORIGIN.getX() >> 4;
        int cz = ORIGIN.getZ() >> 4;
        for (int dx = -SimulationServer.TICKING_MARGIN_CHUNKS; dx <= SimulationServer.TICKING_MARGIN_CHUNKS; dx++) {
            for (int dz = -SimulationServer.TICKING_MARGIN_CHUNKS; dz <= SimulationServer.TICKING_MARGIN_CHUNKS; dz++) {
                level.setChunkForced(cx + dx, cz + dz, false);
            }
        }
    }

    private static void forceLoad(SimulationServer server, SimulationLevel level, List<String> results) {
        int cx = ORIGIN.getX() >> 4;
        int cz = ORIGIN.getZ() >> 4;

        for (int dx = -SimulationServer.TICKING_MARGIN_CHUNKS; dx <= SimulationServer.TICKING_MARGIN_CHUNKS; dx++) {
            for (int dz = -SimulationServer.TICKING_MARGIN_CHUNKS; dz <= SimulationServer.TICKING_MARGIN_CHUNKS; dz++) {
                level.setChunkForced(cx + dx, cz + dz, true);
            }
        }

        long key = ChunkPos.pack(cx, cz);
        int ticks = 0;
        while (ticks < 200 && !(level.areEntitiesLoaded(key) && level.getChunkSource().isPositionTicking(key))) {
            server.tickSimulation();
            ticks++;
        }

        boolean ticking = level.getChunkSource().isPositionTicking(key);
        boolean empty = level.getBlockState(ORIGIN).isAir();
        results.add(ticking && empty
                ? "PASS chunk: [" + cx + ", " + cz + "] ticking after " + ticks + " tick(s), void"
                : "FAIL chunk: ticking=" + ticking + " entitiesLoaded=" + level.areEntitiesLoaded(key)
                        + " air=" + empty + " after " + ticks + " tick(s)");
    }


    private static void gravity(SimulationServer server, SimulationLevel level, List<String> results) {
        BlockPos floor = ORIGIN.below(6);
        BlockPos spawn = ORIGIN;
        level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(spawn, Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);

        long before = level.getGameTime();
        boolean scheduledOnPlace = level.getBlockTicks().hasScheduledTick(spawn, Blocks.SAND);
        boolean becameEntity = false;
        boolean moved = false;
        boolean rendered = false;
        double firstY = Double.NaN;

        for (int i = 0; i < 40 && !level.getBlockState(floor.above()).is(Blocks.SAND); i++) {
            server.tickSimulation();

            Entity falling = findFallingBlock(level);
            if (falling != null) {
                becameEntity = true;
                if (Double.isNaN(firstY)) {
                    firstY = falling.getY();
                } else if (Math.abs(falling.getY() - firstY) > 1.0E-4) {
                    moved = true;
                }
                rendered |= isInProjection(falling);
            }
        }

        boolean landed = level.getBlockState(floor.above()).is(Blocks.SAND);
        if (landed) {
            results.add("PASS gravity: sand fell " + (spawn.getY() - floor.getY() - 1)
                    + " blocks and landed, moved=" + moved + " rendered=" + rendered);
        } else {
            long chunkKey = ChunkPos.pack(spawn);
            results.add("FAIL gravity: scheduledOnPlace=" + scheduledOnPlace
                    + " becameEntity=" + becameEntity
                    + " moved=" + moved
                    + " rendered=" + rendered
                    + " stillAtSpawn=" + level.getBlockState(spawn).is(Blocks.SAND)
                    + " gameTime " + before + "->" + level.getGameTime()
                    + " scheduled=" + level.getBlockTicks().hasScheduledTick(spawn, Blocks.SAND)
                    + " entitiesLoaded=" + level.areEntitiesLoaded(chunkKey)
                    + " positionTicking=" + level.getChunkSource().isPositionTicking(chunkKey)
                    + " blockTickRange=" + level.shouldTickBlocksAt(chunkKey));
        }

        level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(floor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void redstone(SimulationServer server, SimulationLevel level, List<String> results) {
        BlockPos lamp = ORIGIN.east(4);
        BlockPos power = lamp.east();
        level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        server.tickSimulation();

        BlockState state = level.getBlockState(lamp);
        boolean lit = state.is(Blocks.REDSTONE_LAMP) && state.getValue(RedstoneLampBlock.LIT);
        results.add(lit ? "PASS redstone: lamp lit by neighbour update"
                : "FAIL redstone: lamp state " + state);

        level.setBlock(power, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lamp, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    @Nullable
    private static Entity findFallingBlock(SimulationLevel level) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof FallingBlockEntity && !entity.isRemoved()) {
                return entity;
            }
        }
        return null;
    }

    private static boolean isInProjection(Entity entity) {
        WorldSchematic projection = SchematicWorldHandler.getSchematicWorld();
        if (projection == null) {
            return false;
        }

        SchematicEntityLookup<Entity> lookup = ((WorldSchematicAccessor) projection).sim$getEntityLookup();
        return lookup != null && lookup.contains(entity.getUUID());
    }
}
