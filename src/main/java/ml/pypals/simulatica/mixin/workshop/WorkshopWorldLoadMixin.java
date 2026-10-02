package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.malilib.event.WorldLoadHandler;
import ml.pypals.simulatica.workshop.WorkshopSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldLoadHandler.class)
public class WorkshopWorldLoadMixin {
    @Inject(method = {"onWorldLoadImmutable", "onWorldLoadPre", "onWorldLoadPost"}, at = @At("HEAD"), cancellable = true)
    private void workshop$preserveProjection(CallbackInfo ci) {
        if (WorkshopSession.isActive()) ci.cancel();
    }
}
