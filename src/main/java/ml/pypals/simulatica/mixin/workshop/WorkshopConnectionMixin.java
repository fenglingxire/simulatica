package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.network.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class WorkshopConnectionMixin {
    @Inject(method = "setupInboundProtocol", at = @At("RETURN"))
    private void workshop$listener(ProtocolInfo<?> protocol, PacketListener listener, CallbackInfo ci) {
        WorkshopSession.rememberListener((Connection) (Object) this, listener);
        if (WorkshopSession.remote() != null) WorkshopSession.remote().listenerChanged((Connection) (Object) this, listener);
    }
}
