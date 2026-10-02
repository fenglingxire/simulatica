package ml.pypals.simulatica.simulation.server;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import ml.pypals.simulatica.counter.HopperCounter;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.TpsSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.phys.AABB;

import java.nio.file.*;
import java.util.Locale;

/** Actual 200 TPS scheduling, actual source schematic, one real minute of production. */
public final class TpsSmokeTest implements ClientModInitializer {
    private static final Path SOURCE = Path.of(System.getProperty("simulatica.test.schematic",
            "D:/Games/Minecraft/.minecraft/versions/26.2-Fabric 0.19.3/schematics/jiqi/无粘抗卸载骨粉机-方块替换.litematic"));
    private static final BlockPos ORIGIN = new BlockPos(8, 70, 8);
    private static final BlockPos LAMP = ORIGIN.offset(11, 12, 14);
    private static final String NAME = "TPS 骨粉测试";
    private SchematicPlacement placement;
    private SimulationLevel level;
    private HopperCounter output;
    private boolean opened;
    private int stage, wait, changes, mossChanges;
    private long sceneStarted;
    private long started, startTicks, halfOutput = -1, halfChanges;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try { tick(mc); } catch (Throwable failure) {
                failure.printStackTrace();
                finish(mc, "FAIL " + failure);
            }
        });
    }

    private void tick(Minecraft mc) throws Exception {
        if (!opened && mc.gui.screen() instanceof TitleScreen) {
            opened = true;
            sceneStarted = System.nanoTime();
            mc.options.pauseOnLostFocus = false;
            clockChecks();
            mc.createWorldOpenFlows().createFreshLevel("tps-scene-" + System.currentTimeMillis(),
                    new LevelSettings("TPS scene test", GameType.CREATIVE,
                            LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(1, false, false), VoidDimensions::create, mc.gui.screen());
        }
        if (mc.level == null || mc.player == null) return;
        if (stage < 5 && System.nanoTime() - sceneStarted > 180_000_000_000L)
            throw new AssertionError("Scene timed out at stage " + stage);
        if (++wait > 4000 && stage < 3) throw new AssertionError("Scene did not load");
        var placements = DataManager.getSchematicPlacementManager();
        var projection = SchematicWorldHandler.getSchematicWorld();
        var manager = SimulationManager.getInstance();
        if (stage == 0 && wait > 40 && projection != null) {
            LitematicaSchematic schematic = LitematicaSchematic.createFromFile(SOURCE.getParent(), SOURCE.getFileName().toString());
            check(schematic != null, "Source schematic could not be read");
            placement = SchematicPlacement.createFor(schematic, ORIGIN, NAME, true, true);
            placements.addSchematicPlacement(placement, false);
            mc.player.getAbilities().flying = true;
            mc.player.setPos(35, 88, 40);
            mc.player.setYRot(140); mc.player.setXRot(20);
            mc.gui.setScreen(null);
            stage = 1; wait = 0;
        } else if (stage == 1 && wait > 60 && projection.getBlockState(LAMP).is(Blocks.REDSTONE_LAMP)) {
            // Enlarge only the test boundary to let the original downward output dropper eject.
            var area = new AreaSelection();
            area.setName("bone-output-boundary");
            area.createNewSubRegionBox(ORIGIN.offset(0, -3, 0), "machine");
            area.getSelectedSubRegionBox().setPos2(ORIGIN.offset(16, 20, 15));
            for (int cx = ORIGIN.getX() >> 4; cx <= (ORIGIN.getX() + 16) >> 4; cx++)
                for (int cz = ORIGIN.getZ() >> 4; cz <= (ORIGIN.getZ() + 15) >> 4; cz++)
                    if (!projection.hasChunk(cx, cz)) projection.getChunkSource().loadChunk(cx, cz);
            var padded = LitematicaSchematic.createFromWorld(projection, area,
                    new LitematicaSchematic.SchematicSaveInfo(false, true), "tps-test", message -> {});
            placements.removeSchematicPlacement(placement);
            placement = SchematicPlacement.createFor(padded, area.getEffectiveOrigin(), NAME, true, true);
            placements.addSchematicPlacement(placement, false);
            placements.setSelectedSchematicPlacement(placement);
            // Exercise quoted names through the registered client command.
            mc.player.connection.sendCommand("simulatica tps \"" + NAME + "\" 200");
            check(TpsSettings.get(NAME) == 200, "TPS command failed");
            TpsSettings.reload();
            check(TpsSettings.get(NAME) == 200, "TPS setting did not persist");
            stage = 2; wait = 0;
        } else if (stage == 2 && wait > 60 && !manager.isSimulating(placement)) {
            check(projection.getBlockState(LAMP).is(Blocks.REDSTONE_LAMP), "Padded projection lost the machine");
            manager.startSimulation(placement);
        } else if (stage == 2 && manager.getSimulations(placement) != null
                && !manager.getSimulations(placement).isEmpty()) {
            level = manager.getSimulations(placement).values().iterator().next().level();
            check(level.getBlockState(LAMP).is(Blocks.REDSTONE_LAMP), "Simulated machine was not copied");
            check(level.projectionName().equals(NAME), "Placement did not get its own world");
            check(level.clock.target() == 200, "Target TPS mismatch");
            level.getRandom().setSeed(1);
            BlockPos lever = null;
            for (BlockPos pos : BlockPos.betweenClosed(ORIGIN, ORIGIN.offset(16,20,15))) {
                if (level.getBlockState(pos).is(Blocks.LEVER)) { lever = pos.immutable(); break; }
            }
            check(lever != null, "Startup lever was not found");
            var leverState = level.getBlockState(lever);
            if (!leverState.getValue(LeverBlock.POWERED)) ((LeverBlock) Blocks.LEVER).pull(leverState, level, lever, null);
            check(level.getBlockState(lever).getValue(LeverBlock.POWERED), "Startup lever did not activate");
            check(level.getBlockState(LAMP).getValue(BlockStateProperties.LIT), "Startup lamp did not light");
            level.addBlockChangeListener(pos -> {
                changes++;
                if (level.getBlockState(pos).is(Blocks.MOSS_BLOCK)) mossChanges++;
            });
            output = HopperCounter.getCounter(level, DyeColor.WHITE);
            stage = 3;
            System.out.println("SIMULATICA_TPS_SCENE activated lever=" + lever + " lamp=" + LAMP + " output=" + ORIGIN.offset(6,0,1));
        } else if (stage == 3) {
            collectOutput();
            if (level.clock.ticks() >= 1200) {
                output.reset(); changes = mossChanges = 0;
                started = System.nanoTime(); startTicks = level.clock.ticks();
                stage = 4;
                System.out.println("SIMULATICA_TPS_SCENE measurement started: target=200, real seconds=60");
            }
        } else if (stage == 4) {
            collectOutput();
            long elapsed = System.nanoTime() - started;
            if (elapsed >= 30_000_000_000L && halfOutput < 0) {
                halfOutput = output.count(Items.BONE_MEAL); halfChanges = changes;
                System.out.println("SIMULATICA_TPS_SCENE halfway: ticks=" + (level.clock.ticks()-startTicks) + " bone=" + halfOutput);
            }
            if (elapsed >= 60_000_000_000L) {
                long ticks = level.clock.ticks() - startTicks;
                long bone = output.count(Items.BONE_MEAL);
                double seconds = elapsed / 1_000_000_000.0;
                check(halfOutput > 0 && bone > halfOutput, "No sustained bone meal output: " + bone);
                check(changes > halfChanges && halfChanges > 0, "Machine stopped cycling");
                check(mossChanges > 0, "No moss production was observed");
                check(level.getBlockState(LAMP).getValue(BlockStateProperties.LIT), "Lamp switched off");
                check(output.perHour(bone) > 0, "Simulation-time rate did not advance");
                mc.player.connection.sendCommand("simulatica tps \"" + NAME + "\"");
                net.minecraft.client.Screenshot.grab(mc, false);
                String result = String.format(Locale.ROOT,
                        "PASS target=200 TPS; real=%.3fs; executed=%d ticks; simulated=%.2fs; actual=%.2f TPS; bone=%d; bone/h=%.1f; changes=%d; moss=%d; clock=real; performance=%s",
                        seconds, ticks, ticks/20.0, ticks/seconds, bone, output.perHour(bone), changes, mossChanges,
                        ticks >= 11400 ? "target reached within 5%" : "hardware/budget limited");
                manager.stopSimulation(placement);
                long stopped = level.clock.ticks();
                SimulationServer.getRunning().tickSimulation();
                check(level.clock.ticks() == stopped, "Stopped world kept advancing");
                finish(mc, result);
            }
        }
    }

    private void collectOutput() {
        AABB box = new AABB(ORIGIN.getX()+4, ORIGIN.getY()-3, ORIGIN.getZ(),
                ORIGIN.getX()+9, ORIGIN.getY()+1, ORIGIN.getZ()+4);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box,
                entity -> entity.getItem().is(Items.BONE_MEAL))) {
            output.add(item.getItem());
            item.discard();
        }
    }

    private static void clockChecks() {
        SimulationClock clock = new SimulationClock();
        clock.setTarget(200); clock.accrue(1_000_000_000L); clock.accrue(1_050_000_000L);
        int due = 0;
        while (clock.due()) { clock.advanced(); due++; }
        check(due == 10, "200 TPS did not schedule ten ticks per 50ms");
        SimulationClock independent = new SimulationClock();
        independent.setTarget(200); independent.accrue(1_000_000_000L); independent.accrue(1_050_000_000L);
        independent.advanced();
        check(independent.ticks() == 1 && clock.ticks() == 10, "Clocks shared simulated time");
        clock.suspend(); clock.accrue(60_000_000_000L);
        check(!clock.due() && clock.ticks() == 10, "Pause accumulated debt or erased ticks");
        clock.setTarget(20); clock.accrue(61_000_000_000L); clock.accrue(61_050_000_000L);
        check(clock.due(), "Changing TPS lost scheduling");
        clock.advanced(); check(clock.ticks() == 11, "Changing TPS reset the simulated time");
        try { clock.setTarget(1001); throw new AssertionError("Out-of-range TPS accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void finish(Minecraft mc, String result) {
        stage = 5;
        System.out.println("SIMULATICA_TPS_SCENE " + result);
        try { Files.writeString(Path.of("tps-scene-result.txt"), result); } catch (Exception e) { e.printStackTrace(); }
        mc.stop();
    }
}
