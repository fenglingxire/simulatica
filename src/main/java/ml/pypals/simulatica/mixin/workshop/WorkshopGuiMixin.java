package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class WorkshopGuiMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void workshop$screen(Screen screen, CallbackInfo ci) {
        if (WorkshopSession.isRemoteScope()) { WorkshopSession.queueRemoteScreen(screen); ci.cancel(); }
    }
}
