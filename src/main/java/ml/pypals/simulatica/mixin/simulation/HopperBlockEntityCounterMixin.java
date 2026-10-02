package ml.pypals.simulatica.mixin.simulation;

import ml.pypals.simulatica.counter.HopperCounter;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * [SIMULATICA-新增] 羊毛漏斗计数器（与 Carpet 的 hopperCounters 一致）：漏斗朝向（FACING）方向铺
 * 羊毛方块（wool，Blocks.WOOL）时，弹出（ejectItems）的物品按羊毛颜色累计计数并被清空，用于测量
 * 机器/农场的物品产出速率。仅在模拟世界（SimulationLevel）生效，不影响真实世界。
 */
@Mixin(value = HopperBlockEntity.class, priority = 999)
public abstract class HopperBlockEntityCounterMixin extends RandomizableContainerBlockEntity {

    private static final Map<Block, DyeColor> WOOL_BLOCK_TO_DYE = buildWoolMap();

    protected HopperBlockEntityCounterMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private static Map<Block, DyeColor> buildWoolMap() {
        Map<Block, DyeColor> map = new HashMap<>();
        for (DyeColor color : DyeColor.values()) {
            map.put(Blocks.WOOL.pick(color), color);
        }
        return map;
    }

    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void simulatica$onEjectItems(Level level, BlockPos pos, HopperBlockEntity hopper,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof SimulationLevel simulationLevel)) {
            return;
        }
        Direction facing = level.getBlockState(pos).getValue(HopperBlock.FACING);
        BlockState target = level.getBlockState(pos.relative(facing));
        DyeColor color = WOOL_BLOCK_TO_DYE.get(target.getBlock());
        if (color == null) {
            return;
        }

        HopperCounter counter = HopperCounter.getCounter(simulationLevel, color);
        boolean counted = false;
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack stack = hopper.getItem(i);
            if (!stack.isEmpty()) {
                counter.add(stack);
                hopper.setItem(i, ItemStack.EMPTY);
                counted = true;
            }
        }
        if (counted) {
            cir.setReturnValue(false);
        }
    }
}
