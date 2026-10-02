package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class WorkshopClientLevelMixin {
    @Inject(method = {"sendBlockUpdated", "setBlocksDirty", "setSectionDirtyWithNeighbors", "setSectionRangeDirty",
            "playSeededSound", "playLocalSound", "playPlayerSound", "doAddParticle", "createFireworks"},
            at = @At("HEAD"), cancellable = true)
    private void workshop$headless(CallbackInfo ci) {
        if (WorkshopSession.isWorldScope()) ci.cancel();
    }
}
