package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class WorkshopMinecraftMixin {
    @Inject(method = "exitWorldAndClose", at = @At("HEAD"))
    private void workshop$close(CallbackInfo ci) { WorkshopSession.shutdown(); }
    @Inject(method = "setLevel", at = @At("HEAD"), cancellable = true)
    private void workshop$backgroundLevel(ClientLevel level, CallbackInfo ci) {
        if (!WorkshopSession.isRemoteScope()) return;
        ((Minecraft) (Object) this).level = level;
        WorkshopSession.remote().setLevel(level);
        ci.cancel();
    }
    @Inject(method = "clearClientLevel", at = @At("HEAD"), cancellable = true)
    private void workshop$configuration(Screen screen, CallbackInfo ci) {
        if (!WorkshopSession.isRemoteScope()) return;
        Minecraft mc = (Minecraft) (Object) this;
        var play = mc.getConnection();
        if (play != null) play.clearLevel();
        mc.player = null; mc.level = null; mc.gameMode = null;
        WorkshopSession.remote().clearLevel();
        WorkshopSession.notifyLocal("远端服务器正在重新配置");
        ci.cancel();
    }
    @Inject(method = "setCameraEntity", at = @At("HEAD"), cancellable = true)
    private void workshop$camera(Entity camera, CallbackInfo ci) {
        if (WorkshopSession.isRemoteScope()) { WorkshopSession.remote().setCamera(camera); ci.cancel(); }
    }
    @Inject(method = "setScreenAndShow", at = @At("HEAD"), cancellable = true)
    private void workshop$loading(Screen screen, CallbackInfo ci) {
        if (WorkshopSession.isRemoteScope()) { WorkshopSession.queueRemoteScreen(screen); ci.cancel(); }
    }
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"), cancellable = true)
    private void workshop$disconnect(Screen screen, boolean keepPacks, boolean stopSound, CallbackInfo ci) {
        if (WorkshopSession.isRemoteScope()) ci.cancel();
        else if (WorkshopSession.isActive() && !WorkshopSession.current().isReturning()) {
            if (!((Minecraft) (Object) this).isRunning()) { WorkshopSession.shutdown(); return; }
            ml.pypals.simulatica.workshop.WorkshopManager.requestReturn();
            ci.cancel();
        }
    }
}
