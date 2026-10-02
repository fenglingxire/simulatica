package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import ml.pypals.simulatica.workshop.WorkshopMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class WorkshopResourceRecoveryMixin {
    @Inject(method = "abortResourcePackRecovery", at = @At("HEAD"), cancellable = true)
    private void simulatica$remoteRecoveryFailure(CallbackInfo ci) {
        if (!WorkshopSession.isActive()) return;
        Minecraft minecraft = (Minecraft) (Object) this;
        minecraft.gui.setOverlay(null);
        var source = (WorkshopMetadata.ReloadSource) minecraft.getDownloadedPackSource();
        source.simulatica$abortReload();
        var owner = source.simulatica$failedReloadOwner();
        if (owner != null && owner.current()) {
            owner.connection().disconnect(Component.translatable("resourcePack.load_fail"));
            WorkshopSession.notifyLocal("远端资源包加载失败；工作间仍在运行。");
        }
        source.simulatica$finishReload();
        ci.cancel();
    }

    @Inject(method = "abortResourcePackRecovery", at = @At("RETURN"))
    private void simulatica$finishRecovery(CallbackInfo ci) {
        ((WorkshopMetadata.ReloadSource) ((Minecraft) (Object) this).getDownloadedPackSource()).simulatica$finishReload();
    }
}
