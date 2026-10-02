package ml.pypals.simulatica.simulation.server;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.core.*;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.data.DataManager;
import java.nio.file.*;
public class PistonAnimationSmokeTest implements ClientModInitializer {
    public static int submissions, movingHeads, movingBlocks;
    private boolean opened, initialized;
    private int ticks, stage;
    private ProjectionBridge bridge;
    private final BlockPos pos = new BlockPos(8,70,8);
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!opened && mc.gui.screen() instanceof TitleScreen && ++ticks > 10) {
                opened = true;
                mc.createWorldOpenFlows().createFreshLevel("piston-scene-" + System.currentTimeMillis(),
                    new LevelSettings("Piston scene test", GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(1, false, false), VoidDimensions::create, mc.gui.screen());
            }
            if (mc.level == null || mc.player == null || ++ticks < 40) return;
            try {
                if (!initialized) {
                    initialized = true;
                    var projection = SchematicWorldHandler.getSchematicWorld();
                    projection.getChunkSource().loadChunk(0,0);
                    projection.setBlock(pos, Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST), 2);
                    projection.setBlock(pos.east(), Blocks.STONE.defaultBlockState(), 2);
                    // Stationary reference for comparing the moving model's projection style.
                    projection.setBlock(pos.north(2), Blocks.STONE.defaultBlockState(), 2);
                    var area = new AreaSelection();
                    area.setName("piston-scene");
                    area.createNewSubRegionBox(pos.offset(-2,-1,-2), "test");
                    area.getSelectedSubRegionBox().setPos2(pos.offset(4,3,2));
                    var schematic = LitematicaSchematic.createFromWorld(projection, area, new LitematicaSchematic.SchematicSaveInfo(false,true), "smoke", s -> {});
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, area.getEffectiveOrigin(), "piston-scene", true,true), false);
                    mc.player.getAbilities().flying = true;
                    mc.player.setPos(8,72,15);
                    mc.player.setYRot(180); mc.player.setXRot(20);
                    mc.gui.setScreen(null);
                }
                stage++;
                if (stage == 60) {
                    bridge = SimulationServer.getOrCreate().attach(Level.OVERWORLD,pos.offset(-2,-1,-2),pos.offset(4,3,2),"piston-scene",new java.util.HashSet<>());
                }
                if (bridge != null && stage % 20 == 0) {
                    bridge.level().setBlock(pos.west(), stage % 40 == 20 ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
                if (stage >= 80 && stage <= 85) {
                    net.minecraft.client.Screenshot.grab(mc,false);
                }
                if (stage == 150) {
                    String result = (movingHeads > 0 && movingBlocks > 0 ? "PASS" : "FAIL") + " piston scene: submissions="+submissions+", moving heads="+movingHeads+", pushed blocks="+movingBlocks;
                    if (!result.startsWith("PASS")) throw new AssertionError(result);
                    result += " | " + SimulationSelfTest.pistonRendering();
                    System.out.println("SIMULATICA_PISTON_SCENE "+result);
                    Files.writeString(Path.of("piston-scene-result.txt"),result);
                    mc.stop();
                }
            } catch(Throwable failure) {
                failure.printStackTrace();
                try { Files.writeString(Path.of("piston-scene-result.txt"), "FAIL piston scene: " + failure); } catch (Exception ignored) {}
                mc.stop();
            }
        });
    }
}
