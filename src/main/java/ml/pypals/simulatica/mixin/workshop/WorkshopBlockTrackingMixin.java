package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopEdit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class WorkshopBlockTrackingMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void simulatica$track(BlockPos pos, BlockState state, int flags,
                                  CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) {
            WorkshopEdit.onBlockChanged(level, pos, cir.getReturnValue(), state);
        }
    }
}
