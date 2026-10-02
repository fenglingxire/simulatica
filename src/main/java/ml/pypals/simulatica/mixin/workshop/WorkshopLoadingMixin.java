package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk")
public class WorkshopLoadingMixin {
    @Shadow @Final private LocalPlayer player;
    @Shadow @Final private ClientLevel level;
    @Shadow @Final private long timeoutAfter;
    @Inject(method = "isReady", at = @At("HEAD"), cancellable = true)
    private void workshop$headlessReady(CallbackInfoReturnable<Boolean> cir) {
        if (!WorkshopSession.isWorldScope()) return;
        var pos = player.blockPosition();
        cir.setReturnValue(net.minecraft.util.Util.getMillis() > timeoutAfter || level.isOutsideBuildHeight(pos.getY()) || !player.isAlive() || player.isSpectator()
                || level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4));
    }
}
