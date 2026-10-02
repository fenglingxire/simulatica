package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon$ContextImpl")
public abstract class WorkshopFabricContextMixin {
    @Shadow public abstract PacketSender responseSender();

    @Inject(method = "player", at = @At("HEAD"), cancellable = true)
    private void simulatica$player(CallbackInfoReturnable<LocalPlayer> cir) {
        var remote = WorkshopSession.remote();
        var owner = ((WorkshopFabricAddonAccessor) responseSender()).simulatica$connection();
        if (remote != null && remote.owns(owner)) cir.setReturnValue(remote.player());
    }
}
