package ml.pypals.simulatica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import ml.pypals.simulatica.mixin.simulation.ServerLevelBlockEventsAccessor;
import ml.pypals.simulatica.mixin.workshop.WorkshopDataManagerAccessor;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.*;
import ml.pypals.simulatica.workshop.WorkshopManager;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs only via placement.init.gradle, against generated fixtures on a loopback server. */
public final class PlacementRegression implements ClientModInitializer {
    private static final Path OUTPUT = Path.of(System.getProperty("simulatica.regression.output"));
    private final List<String> evidence = new ArrayList<>();
    private final long started = System.currentTimeMillis();
    private final SimulationManager manager = SimulationManager.getInstance();
    private int stage, wait, orientation;
    private SchematicPlacement multi, directional;
    private ProjectionBridge previous;
    private BlockPos savedOrigin;
    private final Rotation[] rotations = {Rotation.CLOCKWISE_180, Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.COUNTERCLOCKWISE_90};

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void passed(String message) { evidence.add(message); System.out.println("REGRESSION PASS: " + message); }

    private void connect(Minecraft mc) {
        String address = "127.0.0.1:" + System.getProperty("simulatica.regression.port");
        ConnectScreen.startConnecting(mc.gui.screen(), mc, ServerAddress.parseString(address),
                new ServerData("Placement regression", address, ServerData.Type.OTHER), false, null);
    }

    private static CompoundTag vector(int x, int y, int z) {
        CompoundTag tag = new CompoundTag(); tag.putInt("x", x); tag.putInt("y", y); tag.putInt("z", z); return tag;
    }

    private SchematicPlacement fixture(String name, boolean multiple, BlockPos origin) throws Exception {
        CompoundTag root = new CompoundTag(), metadata = new CompoundTag(), regions = new CompoundTag();
        root.putInt("Version", 7); root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", LitematicaSchematic.MINECRAFT_DATA_VERSION);
        metadata.putString("Name", name); metadata.putString("Author", "Simulatica regression");
        metadata.putInt("RegionCount", multiple ? 2 : 1); metadata.putInt("TotalBlocks", multiple ? 2 : 1);
        metadata.putInt("TotalVolume", multiple ? 2 : 1); metadata.put("EnclosingSize", vector(multiple ? 3 : 1, 1, 1));
        root.put("Metadata", metadata);
        for (int i = 0; i < (multiple ? 2 : 1); i++) {
            CompoundTag region = new CompoundTag(); region.put("Position", vector(i * 2, 0, 0)); region.put("Size", vector(1, 1, 1));
            ListTag palette = new ListTag(); CompoundTag air = new CompoundTag(); air.putString("Name", "minecraft:air"); palette.add(air);
            CompoundTag block = new CompoundTag(); block.putString("Name", "minecraft:dropper");
            CompoundTag properties = new CompoundTag(); properties.putString("facing", "north"); properties.putString("triggered", "false");
            block.put("Properties", properties); palette.add(block); region.put("BlockStatePalette", palette);
            region.putLongArray("BlockStates", new long[]{1}); region.put("Entities", new ListTag()); region.put("TileEntities", new ListTag());
            regions.put("Region" + i, region);
        }
        root.put("Regions", regions);
        Path file = OUTPUT.resolve(name + ".litematic"); NbtIo.writeCompressed(root, file);
        var schematic = LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString());
        require(schematic != null, "Fixture load failed");
        var placement = SchematicPlacement.createFor(schematic, origin, name, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }

    private boolean ready(SchematicPlacement placement, int count) {
        var bridges = manager.getSimulations(placement);
        return bridges != null && bridges.size() == count && manager.getPendingCount() == 0;
    }

    private void migrate(boolean reverse) {
        var bridges = new ArrayList<>(manager.getSimulations(multi).values());
        // Make the first destination land on the second source, regardless of map ordering.
        if (reverse) java.util.Collections.reverse(bridges);
        var first = bridges.get(0); var second = bridges.get(1); var level = first.level();
        level.tickRateManager().setFrozen(true);
        BlockPos from = first.region().worldMin(), other = second.region().worldMin();
        BlockPos delta = other.subtract(from);
        level.setBlock(from, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        level.setBlock(other, Blocks.CHEST.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        var chest = (ChestBlockEntity) level.getBlockEntity(other); require(chest != null, "Missing source chest");
        chest.setItem(0, new ItemStack(Items.EMERALD, 17));
        ArmorStand stand = new ArmorStand(level, from.getX() + .5, from.getY(), from.getZ() + .5);
        stand.setNoGravity(true); level.addFreshEntity(stand);
        double x = stand.getX(), y = stand.getY(), z = stand.getZ();
        long future = level.getGameTime() + 100000;
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.DIAMOND_BLOCK, from, future, TickPriority.NORMAL, 101));
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.CHEST, other, future + 1, TickPriority.HIGH, 102));
        level.getFluidTicks().schedule(new ScheduledTick<>(Fluids.WATER, from, future, TickPriority.NORMAL, 103));
        var events = ((ServerLevelBlockEventsAccessor) level).simulatica$blockEvents();
        events.add(new BlockEventData(from, Blocks.DIAMOND_BLOCK, 8, 71));
        multi.setOrigin(multi.getOrigin().offset(delta), message -> {});
        manager.tick();
        require(level.getBlockState(from.offset(delta)).is(Blocks.DIAMOND_BLOCK), "First source was overwritten");
        require(level.getBlockState(other.offset(delta)).is(Blocks.CHEST), "Second source was overwritten");
        var movedChest = (ChestBlockEntity) level.getBlockEntity(other.offset(delta));
        require(movedChest != null && movedChest.getItem(0).is(Items.EMERALD) && movedChest.getItem(0).getCount() == 17, "Chest inventory lost");
        require(level.getBlockState(from).isAir(), "Vacated source retained blocks");
        require(stand.getX() == x + delta.getX() && stand.getY() == y + delta.getY() && stand.getZ() == z + delta.getZ(), "Entity moved more than once");
        require(level.getBlockTicks().hasScheduledTick(from.offset(delta), Blocks.DIAMOND_BLOCK), "First scheduled tick lost");
        require(level.getBlockTicks().hasScheduledTick(other.offset(delta), Blocks.CHEST), "Second scheduled tick lost");
        require(!level.getBlockTicks().hasScheduledTick(other.offset(delta).offset(delta), Blocks.DIAMOND_BLOCK), "Scheduled tick moved twice");
        require(level.getFluidTicks().hasScheduledTick(from.offset(delta), Fluids.WATER), "Fluid tick lost");
        require(events.contains(new BlockEventData(from.offset(delta), Blocks.DIAMOND_BLOCK, 8, 71)), "Block event lost or moved twice");
        stand.discard();
        passed("Cross-region migration " + delta + ": blocks, chest NBT, entity, block/fluid ticks and events");
    }

    private void changeOrientation() {
        previous = manager.getSimulations(directional).get("Region0");
        var level = manager.projectionLevel(directional);
        level.tickRateManager().setFrozen(true);
        ArmorStand oldEntity = new ArmorStand(level, directional.getOrigin().getX() + .5, 100, directional.getOrigin().getZ() + .5);
        oldEntity.setNoGravity(true); level.addFreshEntity(oldEntity);
        if (previous != null) level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.DIAMOND_BLOCK,
                previous.region().worldMin(), level.getGameTime() + 100000, TickPriority.NORMAL, 104));
        if (orientation < rotations.length) directional.setRotation(rotations[orientation], null);
        else if (orientation == 4) directional.setMirror(Mirror.LEFT_RIGHT, null);
        else if (orientation == 5) directional.setSubRegionRotation("Region0", Rotation.CLOCKWISE_180, null);
        else if (orientation == 6) directional.setSubRegionMirror("Region0", Mirror.FRONT_BACK, null);
        else if (orientation == 7) {
            directional.setRotation(Rotation.CLOCKWISE_180, null);
            directional.setOrigin(directional.getOrigin().offset(2, 0, 0), message -> {});
        } else if (orientation == 8) directional.moveSubRegionTo("Region0", directional.getOrigin().offset(1, 0, 0), message -> {});
        else if (orientation == 9 || orientation == 10) directional.toggleSubRegionEnabled("Region0", null);
        else directional.setEnabled(orientation == 12);
        manager.tick();
        require(manager.getSimulations(directional).isEmpty(), "Orientation was missed or treated as translation: " + orientation);
        require(oldEntity.isRemoved(), "Old entity survived a schematic rebuild");
        if (previous != null) require(!level.getBlockTicks().hasScheduledTick(previous.region().worldMin(), Blocks.DIAMOND_BLOCK), "Old scheduled tick survived a schematic rebuild");
    }

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                if (stage == 99) return;
                require(System.currentTimeMillis() - started < 420000, "Timeout at stage " + stage);
                require(mc.gameDirectory.toPath().toAbsolutePath().normalize().equals(Path.of("D:/Games/Minecraft/.minecraft/versions/26.2-test").toAbsolutePath().normalize()), "Wrong game directory");
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
                if (stage == 0) {
                    if (!(mc.gui.screen() instanceof TitleScreen)) return;
                    Path serverLog = Path.of(System.getProperty("simulatica.regression.serverLog"));
                    if (!Files.exists(serverLog) || !Files.readString(serverLog).contains("Done (")) return;
                    connect(mc); stage = 1; return;
                }
                if (stage == 1) {
                    if (mc.level == null || mc.player == null || mc.gui.screen() != null) return;
                    require(!mc.hasSingleplayerServer(), "Expected genuine multiplayer connection");
                    BlockPos base = new BlockPos(mc.player.blockPosition().getX(), 100, mc.player.blockPosition().getZ());
                    multi = fixture("regression-multi", true, base);
                    directional = fixture("regression-facing", false, base.offset(20, 0, 0));
                    stage = 2; return;
                }
                if (stage == 2) {
                    var world = SchematicWorldHandler.getSchematicWorld();
                    if (world == null || world.getBlockState(multi.getOrigin()).isAir() || world.getBlockState(directional.getOrigin()).isAir()) return;
                    manager.startSimulation(multi); manager.startSimulation(directional); stage = 3; return;
                }
                if (stage == 3) {
                    if (!ready(multi, 2) || !ready(directional, 1)) return;
                    migrate(false); stage = 4; return;
                }
                if (stage == 4) {
                    if (!ready(multi, 2)) return;
                    migrate(true); stage = 5; return;
                }
                if (stage == 5) {
                    if (!ready(multi, 2)) return;
                    changeOrientation(); stage = 6; return;
                }
                if (stage == 6) {
                    int count = orientation == 9 || orientation == 11 ? 0 : 1;
                    if (!ready(directional, count)) return;
                    if (count == 0) {
                        passed("Disabled placement/region has no bridges: case " + orientation);
                        orientation++; changeOrientation(); return;
                    }
                    var bridge = manager.getSimulations(directional).get("Region0");
                    require(bridge != previous, "Bridge was reused after orientation change");
                    BlockState expected = Blocks.DROPPER.defaultBlockState().setValue(DropperBlock.FACING, Direction.NORTH)
                            .mirror(directional.getMirror()).rotate(directional.getRotation())
                            .mirror(directional.getRelativeSubRegionPlacement("Region0").getMirror())
                            .rotate(directional.getRelativeSubRegionPlacement("Region0").getRotation());
                    require(bridge.level().getBlockState(bridge.region().worldMin()).getValue(DropperBlock.FACING) == expected.getValue(DropperBlock.FACING), "Wrong simulated direction at case " + orientation);
                    passed("Orientation rebuild case " + orientation + ": " + expected.getValue(DropperBlock.FACING));
                    if (++orientation < 13) { changeOrientation(); return; }
                    manager.stopAll();
                    require(WorkshopDataManagerAccessor.simulatica$canSave(), "Save disabled before workshop");
                    mc.gui.setScreen(null); WorkshopManager.enter(directional); stage = 7; return;
                }
                if (stage == 7) {
                    if (!WorkshopSession.isActive() || !mc.hasSingleplayerServer() || mc.player == null || mc.gui.screen() != null) return;
                    require(!WorkshopDataManagerAccessor.simulatica$canSave(), "Workshop should suspend source saves");
                    WorkshopManager.finish(false); stage = 8; wait = 0; return;
                }
                if (stage == 8) {
                    if (WorkshopSession.isActive() || mc.level == null || mc.player == null || ++wait < 10) return;
                    require(!mc.hasSingleplayerServer(), "Did not return to remote server");
                    require(WorkshopDataManagerAccessor.simulatica$canSave(), "Save permission not restored after discard");
                    savedOrigin = directional.getOrigin().offset(3, 0, 0); directional.setOrigin(savedOrigin, message -> {});
                    passed("Workshop discard restores source save permission");
                    mc.disconnect(new TitleScreen(), false); stage = 9; return;
                }
                if (stage == 9) {
                    if (!(mc.gui.screen() instanceof TitleScreen)) return;
                    connect(mc); stage = 10; return;
                }
                if (stage == 10) {
                    if (mc.level == null || mc.player == null || mc.gui.screen() != null) return;
                    directional = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                            .filter(p -> p.getName().equals("regression-facing")).findFirst().orElseThrow();
                    require(directional.getOrigin().equals(savedOrigin), "Position rolled back on reconnect");
                    passed("Position edited after workshop survives disconnect and reconnect");
                    // Startup failure must use the same restore path, before detaching the world.
                    WorkshopSession.open(mc.level.dimension(), savedOrigin, savedOrigin, savedOrigin,
                            level -> { throw new IllegalStateException("Injected regression startup failure"); });
                    require(!WorkshopSession.isActive(), "Failed workshop left session active");
                    require(WorkshopDataManagerAccessor.simulatica$canSave(), "Startup failure left saves disabled");
                    passed("Workshop startup failure restores save permission");
                    WorkshopDataManagerAccessor.simulatica$canSave(false);
                    WorkshopSession.open(mc.level.dimension(), savedOrigin, savedOrigin, savedOrigin,
                            level -> { throw new IllegalStateException("Injected regression disabled-save failure"); });
                    require(!WorkshopDataManagerAccessor.simulatica$canSave(), "Restore overwrote a previously disabled save state");
                    WorkshopDataManagerAccessor.simulatica$canSave(true);
                    passed("Previously disabled save permission is preserved");
                    WorkshopManager.enter(directional); stage = 11; return;
                }
                if (stage == 11) {
                    if (!WorkshopSession.isActive() || !mc.hasSingleplayerServer() || mc.player == null || mc.gui.screen() != null) return;
                    WorkshopManager.finish(true); stage = 12; wait = 0; return;
                }
                if (stage == 12) {
                    if (WorkshopSession.isActive() || mc.level == null || mc.player == null || ++wait < 10) return;
                    require(WorkshopDataManagerAccessor.simulatica$canSave(), "Apply return left saves disabled");
                    require(!WorkshopManager.isActive(), "Apply return did not finish the transaction");
                    passed("Workshop apply restores save permission");
                    WorkshopManager.enter(directional); stage = 13; return;
                }
                if (stage == 13) {
                    if (!WorkshopSession.isActive() || !mc.hasSingleplayerServer() || mc.player == null || mc.gui.screen() != null) return;
                    WorkshopSession.shutdown();
                    require(!WorkshopSession.isActive(), "Shutdown left a workshop session active");
                    require(WorkshopDataManagerAccessor.simulatica$canSave(), "Shutdown did not restore source saves");
                    passed("Workshop client shutdown restores source save permission");
                    stage = 99;
                    Files.writeString(OUTPUT.resolve("result.txt"), "PASS: Minecraft 26.2 / Fabric 0.19.5 / Litematica 0.28.8 / malilib 0.29.6\n" + String.join("\n", evidence));
                    mc.stop();
                }
            } catch (Throwable error) {
                error.printStackTrace(); stage = 99;
                try { Files.writeString(OUTPUT.resolve("result.txt"), "FAIL: " + error + "\n" + String.join("\n", evidence)); }
                catch (Exception ignored) {}
                mc.stop();
            }
        });
    }
}
