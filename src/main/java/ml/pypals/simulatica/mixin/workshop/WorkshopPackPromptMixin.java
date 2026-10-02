package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.fabricmc.fabric.mixin.networking.client.accessor.ClientCommonPacketListenerImplAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.resources.server.DownloadedPackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.net.URL;
import java.util.List;
import java.util.UUID;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl$PackConfirmScreen")
public class WorkshopPackPromptMixin {
    @WrapMethod(method = "lambda$new$0")
    private static void simulatica$discardOldPrompt(Minecraft minecraft, Screen parent, ClientCommonPacketListenerImpl listener,
            boolean required, List<?> requests, boolean accepted, Operation<Void> original) {
        var connection = ((ClientCommonPacketListenerImplAccessor) listener).getConnection();
        var remote = WorkshopSession.remote();
        if (remote != null && remote.owns(connection) && !remote.active(connection)) {
            minecraft.gui.setScreen(parent);
            return;
        }
        original.call(minecraft, parent, listener, required, requests, accepted);
    }

    @WrapOperation(method = "lambda$new$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/server/DownloadedPackSource;pushPack(Ljava/util/UUID;Ljava/net/URL;Ljava/lang/String;)V"))
    private static void simulatica$ownedPromptPack(DownloadedPackSource source, UUID id, URL url, String hash,
            Operation<Void> original, @Local(argsOnly = true) ClientCommonPacketListenerImpl listener) {
        var connection = ((ClientCommonPacketListenerImplAccessor) listener).getConnection();
        WorkshopSession.enterMetadataScope(connection);
        try { original.call(source, id, url, hash); }
        finally { WorkshopSession.leaveMetadataScope(); }
    }
}
