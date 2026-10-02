package ml.pypals.simulatica.mixin.workshop;
import ml.pypals.simulatica.workshop.WorkshopEdit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;
@Mixin(PistonBaseBlock.class)
public abstract class WorkshopPlatformPistonMixin {
    @Unique private static final ThreadLocal<Map<BlockPos,BlockState>> simulatica$moved = new ThreadLocal<>();
    @Inject(method = "moveBlocks", at = @At("HEAD"))
    private void simulatica$before(Level level, BlockPos pos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> cir) {
        simulatica$moved.remove();
        if (!(level instanceof ServerLevel server) || !WorkshopEdit.isTracked(server)) return;
        PistonStructureResolver resolver = new PistonStructureResolver(level,pos,direction,extending);
        if (!resolver.resolve()) return;
        Map<BlockPos,BlockState> moves = new HashMap<>();
        for (BlockPos source : resolver.getToPush()) if (WorkshopEdit.isPlatformSource(server,source)) moves.put(source.relative(resolver.getPushDirection()),level.getBlockState(source));
        simulatica$moved.set(moves);
    }
    @Inject(method = "moveBlocks", at = @At("RETURN"))
    private void simulatica$after(Level level, BlockPos pos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> cir) {
        Map<BlockPos,BlockState> moves = simulatica$moved.get();simulatica$moved.remove();
        if (cir.getReturnValue() && moves != null && level instanceof ServerLevel server) WorkshopEdit.platformMoved(server,moves);
    }
}
