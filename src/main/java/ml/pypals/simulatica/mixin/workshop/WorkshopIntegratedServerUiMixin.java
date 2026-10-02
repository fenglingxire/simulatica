package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopServer;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
public class WorkshopIntegratedServerUiMixin {
    @Inject(method = "updatePermissionAndChatAbilities", at = @At("HEAD"), cancellable = true)
    private void workshop$permissionUi(LocalPlayer player, CallbackInfo ci) {
        if (!((Object) this instanceof WorkshopServer server)) return;
        Minecraft mc = Minecraft.getInstance();
        WorkshopSession session = WorkshopSession.current();
        if (!ownsPlayer(mc, session, server, player)) { ci.cancel(); return; }
        if (!mc.isSameThread()) {
            mc.execute(() -> {
                if (WorkshopSession.current() != session || !ownsPlayer(mc, session, server, player)) return;
                player.setPermissions(server.getProfilePermissions(player.nameAndId()));
                player.refreshChatAbilities();
            });
            ci.cancel();
        }
    }

    private static boolean ownsPlayer(Minecraft mc, WorkshopSession session, WorkshopServer server, LocalPlayer player) {
        return session != null && session.server() == server && !server.isShutdown()
                && mc.getSingleplayerServer() == server && mc.player == player
                && player.connection.getConnection().isMemoryConnection();
    }
}
