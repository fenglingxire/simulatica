package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConfigurationPacketListenerImpl.class)
public class WorkshopConfigurationDisconnectMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void workshop$retired(DisconnectionDetails details, CallbackInfo ci) {
        if (WorkshopSession.retired(((WorkshopSession.ConnectionOwner) this).workshop$connection())) ci.cancel();
    }
}
