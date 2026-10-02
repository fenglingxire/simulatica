package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopEdit;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class WorkshopPlatformInteractionMixin {
    @Inject(method = {"destroyBlock", "useItemOn", "useItem"}, at = @At("HEAD"))
    private void simulatica$begin(CallbackInfoReturnable<?> cir) { WorkshopEdit.beginPlayerMutation(); }
    @Inject(method = {"destroyBlock", "useItemOn", "useItem"}, at = @At("RETURN"))
    private void simulatica$end(CallbackInfoReturnable<?> cir) { WorkshopEdit.endPlayerMutation(); }
}
