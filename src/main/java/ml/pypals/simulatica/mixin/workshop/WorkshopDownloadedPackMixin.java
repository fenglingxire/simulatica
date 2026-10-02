package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import ml.pypals.simulatica.workshop.WorkshopMetadata;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayDeque;

@Mixin(DownloadedPackSource.class)
public class WorkshopDownloadedPackMixin implements WorkshopMetadata.ReloadSource {
    @Shadow private PackReloadConfig.Callbacks pendingReload;
    @Shadow @Final private Minecraft minecraft;
    @Shadow private void startReload(PackReloadConfig.Callbacks callbacks) { throw new AssertionError(); }
    @Shadow public void onRecoveryFailure() { throw new AssertionError(); }
    @Unique private final ArrayDeque<PackReloadConfig.Callbacks> simulatica$queuedReloads = new ArrayDeque<>();
    @Unique private boolean simulatica$reloadBusy;
    @Unique private WorkshopMetadata.PackOwner simulatica$failedOwner;

    @WrapMethod(method = "startReload")
    private void simulatica$serializeReload(PackReloadConfig.Callbacks callbacks, Operation<Void> original) {
        ((WorkshopMetadata.OwnedReload) callbacks).simulatica$reloadOwner();
        if (simulatica$reloadBusy) {
            simulatica$queuedReloads.addLast(callbacks);
            return;
        }
        simulatica$reloadBusy = true;
        simulatica$failedOwner = null;
        original.call(callbacks);
    }

    @WrapMethod(method = "onReloadSuccess")
    private void simulatica$completeReload(Operation<Void> original) {
        try { original.call(); }
        finally { simulatica$finishReload(); }
    }

    @Inject(method = "onRecoveryFailure", at = @At("HEAD"))
    private void simulatica$failedReload(CallbackInfo ci) {
        simulatica$failedOwner = pendingReload == null ? null
                : ((WorkshopMetadata.OwnedReload) pendingReload).simulatica$reloadOwner();
    }

    @Override public WorkshopMetadata.PackOwner simulatica$failedReloadOwner() { return simulatica$failedOwner; }

    @Override public void simulatica$abortReload() {
        // Normal recovery failure already cleared this; direct aborts still need to retire their callback.
        if (pendingReload != null) onRecoveryFailure();
    }

    @Override public void simulatica$finishReload() {
        if (!simulatica$reloadBusy) return;
        simulatica$reloadBusy = false;
        simulatica$failedOwner = null;
        if (!simulatica$queuedReloads.isEmpty()) {
            var next = simulatica$queuedReloads.removeFirst();
            // The old LoadingOverlay callback must return before another batch replaces pendingReload.
            WorkshopSession.scheduleLocal(() -> startReload(next));
        }
    }

    @Override public void simulatica$adoptReloadOwner(WorkshopMetadata.PackOwner owner) {
        if (pendingReload != null) ((WorkshopMetadata.OwnedReload) pendingReload).simulatica$adoptOwner(owner);
        simulatica$queuedReloads.forEach(callback -> ((WorkshopMetadata.OwnedReload) callback).simulatica$adoptOwner(owner));
    }
    @Inject(method = {"configureForLocalWorld", "cleanupAfterDisconnect"}, at = @At("HEAD"), cancellable = true)
    private void simulatica$preserveRemotePacks(CallbackInfo ci) {
        if (WorkshopSession.isActive() && !WorkshopSession.isRemoteScope()) ci.cancel();
    }
}
