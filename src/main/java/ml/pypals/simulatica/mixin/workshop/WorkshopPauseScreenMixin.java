package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class WorkshopPauseScreenMixin extends Screen {
    protected WorkshopPauseScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void workshop$returnButton(CallbackInfo ci) {
        if (!WorkshopManager.isActive()) return;
        int buttonWidth = Math.min(204, width - 16);
        addRenderableWidget(Button.builder(Component.translatable("simulatica.workshop.return"),
                button -> WorkshopManager.requestReturn()).bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
    }
}
