package ml.pypals.simulatica.mixin.simulation;

import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(PistonMovingBlockEntity.class)
public interface SimPistonMovingBlockEntityAccessor {

    @Accessor("progressO")
    float sim$getProgressO();

    @Accessor("progressO")
    void sim$setProgressO(float progress);

    @Accessor("progress")
    float sim$getProgress();

    @Accessor("progress")
    void sim$setProgress(float progress);
}
