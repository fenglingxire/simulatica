package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientConfigurationPacketListenerImpl.class)
public abstract class WorkshopConfigurationMetadataMixin extends ClientCommonPacketListenerImpl {
    protected WorkshopConfigurationMetadataMixin(Minecraft minecraft, Connection connection,
            net.minecraft.client.multiplayer.CommonListenerCookie cookie) { super(minecraft, connection, cookie); }

    @WrapOperation(method = "handleConfigurationFinished", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getSingleplayerServer()Lnet/minecraft/client/server/IntegratedServer;"))
    private IntegratedServer simulatica$connectionOwnedServer(Minecraft minecraft, Operation<IntegratedServer> original) {
        var remote = WorkshopSession.remote();
        return remote != null && remote.owns(this.connection) ? null : original.call(minecraft);
    }
}
