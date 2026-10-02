package ml.pypals.simulatica.mixin.workshop;
import ml.pypals.simulatica.workshop.WorkshopEdit;
import net.minecraft.world.level.block.SpreadingSnowyBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SpreadingSnowyBlock.class)
public abstract class WorkshopNaturalGrassMixin {
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void simulatica$begin(CallbackInfo ci) { WorkshopEdit.beginGrassTick(); }
    @Inject(method = "randomTick", at = @At("RETURN"))
    private void simulatica$end(CallbackInfo ci) { WorkshopEdit.endGrassTick(); }
}
