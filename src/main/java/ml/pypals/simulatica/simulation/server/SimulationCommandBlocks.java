package ml.pypals.simulatica.simulation.server;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;


/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - Minecraft.setScreen / screen → gui.setScreen / gui.screen()
 */
public final class SimulationCommandBlocks {

    @Nullable
    private static SimulationLevel level;
    @Nullable
    private static BlockPos pos;
    @Nullable
    private static Screen screen;

    private SimulationCommandBlocks() {}

    public static boolean open(CommandBlockEntity blockEntity) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) return false;
        if (!(blockEntity.getLevel() instanceof SimulationLevel simulated)) {
            return false;
        }

        level = simulated;
        pos = blockEntity.getBlockPos().immutable();
        screen = new CommandBlockEditScreen(blockEntity);
        Minecraft.getInstance().gui.setScreen(screen);
        return true;
    }

    public static boolean apply(ServerboundSetCommandBlockPacket packet) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) return false;
        if (level == null || pos == null || !pos.equals(packet.getPos())
                || Minecraft.getInstance().gui.screen() != screen) {
            return false;
        }

        SimulationLevel simulated = level;
        clear();

        if (!(simulated.getBlockEntity(packet.getPos()) instanceof CommandBlockEntity blockEntity)) {
            return true;
        }

        BaseCommandBlock command = blockEntity.getCommandBlock();
        CommandBlockEntity.Mode previous = blockEntity.getMode();
        BlockState state = simulated.getBlockState(packet.getPos());
        Direction facing = state.getValue(CommandBlock.FACING);

        BlockState replacement = switch (packet.getMode()) {
            case SEQUENCE -> Blocks.CHAIN_COMMAND_BLOCK.defaultBlockState();
            case AUTO -> Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState();
            default -> Blocks.COMMAND_BLOCK.defaultBlockState();
        };
        replacement = replacement.setValue(CommandBlock.FACING, facing)
                .setValue(CommandBlock.CONDITIONAL, packet.isConditional());

        if (replacement != state) {
            simulated.setBlock(packet.getPos(), replacement, 2);
            blockEntity.setBlockState(replacement);
            simulated.getChunkAt(packet.getPos()).setBlockEntity(blockEntity);
        }

        command.setCommand(packet.getCommand());
        command.setTrackOutput(packet.isTrackOutput());
        if (!packet.isTrackOutput()) {
            command.setLastOutput(null);
        }

        blockEntity.setAutomatic(packet.isAutomatic());
        if (previous != packet.getMode()) {
            blockEntity.onModeSwitch();
        }
        if (simulated.isCommandBlockEnabled()) {
            command.onUpdated(simulated);
        }
        return true;
    }

    public static void clear() {
        level = null;
        pos = null;
        screen = null;
    }
}
