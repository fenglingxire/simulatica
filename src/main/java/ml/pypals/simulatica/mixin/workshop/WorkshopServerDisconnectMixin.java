package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import ml.pypals.simulatica.workshop.WorkshopServer;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerCommonPacketListenerImpl.class)
public class WorkshopServerDisconnectMixin {
    @WrapWithCondition(method = "onDisconnect", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;halt(Z)V"))
    private boolean workshop$retainEdits(MinecraftServer server, boolean wait) {
        var session = WorkshopSession.current();
        // Only the edit transaction may stop its server; a dropped memory connection must retain the edits.
        return !(server instanceof WorkshopServer) || session == null || session.server() != server || session.isReturning();
    }
}
