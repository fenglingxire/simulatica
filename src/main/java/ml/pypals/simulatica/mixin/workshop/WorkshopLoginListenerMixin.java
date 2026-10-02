package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientHandshakePacketListenerImpl.class)
public class WorkshopLoginListenerMixin {
    @Shadow @Final private Connection connection;
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void workshop$failure(DisconnectionDetails details, CallbackInfo ci) {
        if (WorkshopSession.retired(connection)) { ci.cancel(); return; }
        if (WorkshopSession.remote() != null && WorkshopSession.remote().owns(connection)) {
            WorkshopSession.remote().disconnect(connection, details,
                    new DisconnectedScreen(new TitleScreen(), Component.translatable("connect.failed"), details));
            ci.cancel();
        }
    }
}
