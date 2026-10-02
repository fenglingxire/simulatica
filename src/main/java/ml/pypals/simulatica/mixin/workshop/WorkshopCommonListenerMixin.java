package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class WorkshopCommonListenerMixin implements WorkshopSession.ConnectionOwner {
    @Shadow @Final protected Connection connection;
    @Shadow @Final protected Map<Identifier, byte[]> serverCookies;
    @Shadow @Final protected Map<UUID, PlayerInfo> seenPlayers;
    @Shadow protected boolean seenInsecureChatWarning;
    @Shadow protected abstract Screen createDisconnectScreen(DisconnectionDetails details);
    @Override public Connection workshop$connection() { return connection; }

    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void workshop$disconnect(DisconnectionDetails details, CallbackInfo ci) {
        if (WorkshopSession.retired(connection)) { ci.cancel(); return; }
        if (WorkshopSession.remote() != null && WorkshopSession.remote().owns(connection)) {
            WorkshopSession.remote().disconnect(connection, details, createDisconnectScreen(details));
            ci.cancel();
        }
    }
    @Inject(method = "handleTransfer", at = @At("HEAD"), cancellable = true)
    private void workshop$transfer(ClientboundTransferPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread() || WorkshopSession.remote() == null || !WorkshopSession.remote().owns(connection)) return;
        WorkshopSession.remote().transfer(packet.host(), packet.port(), new TransferState(serverCookies, seenPlayers, seenInsecureChatWarning));
        ci.cancel();
    }
}
