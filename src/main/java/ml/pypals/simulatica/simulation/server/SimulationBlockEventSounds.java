package ml.pypals.simulatica.simulation.server;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 方块事件声音显式映射（活塞伸缩、音符盒按乐器与音高、箱/末影箱/潜影盒/木桶开合盖）：这些声音原走区块追踪，viewer 收不到。
 */
/**
 * Sounds for block events, played directly on the real client.
 *
 * <p>Block events (pistons, note blocks, chest lids) are normally delivered to clients through
 * chunk tracking, which the simulation viewer never registers for. Forwarding the packet to the
 * real client would not help either: the client resolves the event against the real block at
 * that position, which is air where the projection is. The mapping below mirrors what vanilla
 * block event handlers play.</p>
 */
final class SimulationBlockEventSounds {

    private SimulationBlockEventSounds() {
    }

    static void play(ClientLevel client, SimulationLevel level, BlockState state, BlockPos pos, int eventId, int eventParam) {
        Block block = state.getBlock();

        if (block instanceof PistonBaseBlock) {
            boolean extending = (eventId & 1) == 0;
            if (!level.allowSound(extending ? SoundEvents.PISTON_EXTEND : SoundEvents.PISTON_CONTRACT)) return;
            client.playLocalSound(pos, extending ? SoundEvents.PISTON_EXTEND : SoundEvents.PISTON_CONTRACT,
                    SoundSource.BLOCKS, 0.5F, client.getRandom().nextFloat() * 0.25F + 0.6F, false);
            return;
        }

        if (block instanceof NoteBlock) {
            Holder<SoundEvent> sound = state.getValue(NoteBlock.INSTRUMENT).getSoundEvent();
            if (!level.allowSound(sound.value())) return;
            client.playSeededSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    sound, SoundSource.RECORDS, 3.0F, NoteBlock.getPitchFromNote(eventId), 0L);
            return;
        }

        if (eventId == 1) {
            SoundEvent sound = lidSound(block, eventParam > 0);
            if (sound != null && level.allowSound(sound)) {
                client.playLocalSound(pos, sound, SoundSource.BLOCKS, 0.5F,
                        client.getRandom().nextFloat() * 0.1F + 0.9F, false);
            }
        }
    }

    private static SoundEvent lidSound(Block block, boolean opening) {
        if (block instanceof ChestBlock) {
            return opening ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE;
        }
        if (block instanceof EnderChestBlock) {
            return opening ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.ENDER_CHEST_CLOSE;
        }
        if (block instanceof ShulkerBoxBlock) {
            return opening ? SoundEvents.SHULKER_BOX_OPEN : SoundEvents.SHULKER_BOX_CLOSE;
        }
        if (block instanceof BarrelBlock) {
            return opening ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE;
        }
        return null;
    }
}
