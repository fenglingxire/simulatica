package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Background light/data updates retain their caches without invalidating the foreground renderer. */
@Mixin(LevelExtractor.class)
public class WorkshopLevelExtractorMixin {
    @Inject(method = {"setSectionDirty(IIIZ)V", "setSectionRangeDirty(IIIIII)V", "setBlocksDirty(IIIIII)V",
            "setSectionDirtyWithNeighbors(III)V", "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V"},
            at = @At("HEAD"), cancellable = true)
    private void workshop$backgroundDirty(CallbackInfo ci) {
        if (WorkshopSession.isWorldScope()) ci.cancel();
    }
}
