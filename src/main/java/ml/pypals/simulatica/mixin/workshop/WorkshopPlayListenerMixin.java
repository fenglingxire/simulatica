package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.*;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Mixin(ClientPacketListener.class)
public class WorkshopPlayListenerMixin {
    @WrapOperation(method = "handlePlayerChat", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/ChatListener;handlePlayerChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/network/chat/ChatType$Bound;)V"))
    private void workshop$chat(ChatListener chat, PlayerChatMessage message, GameProfile sender, ChatType.Bound type, Operation<Void> original) {
        if (WorkshopSession.isWorldScope()) {
            if (message.signature() != null) ((ClientPacketListener) (Object) this).markMessageAsProcessed(message.signature(), false);
        } else original.call(chat, message, sender, type);
    }
    @WrapOperation(method = "handlePlayerChat", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/ChatListener;handleChatMessageError(Ljava/util/UUID;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/network/chat/ChatType$Bound;)V"))
    private void workshop$invalidChat(ChatListener chat, UUID sender, MessageSignature signature, ChatType.Bound type, Operation<Void> original) {
        if (WorkshopSession.isWorldScope()) {
            if (signature != null) ((ClientPacketListener) (Object) this).markMessageAsProcessed(signature, false);
        } else original.call(chat, sender, signature, type);
    }
    @WrapOperation(method = "handleConfigurationStart", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/ChatListener;flushQueue()V"))
    private void workshop$localChatQueue(ChatListener chat, Operation<Void> original) {
        if (!WorkshopSession.isWorldScope()) original.call(chat);
    }
    @Inject(method = {"handleSystemChat", "handleDisguisedChat", "handleDeleteChat"}, at = @At("HEAD"), cancellable = true)
    private void workshop$hideChat(CallbackInfo ci) {
        if (WorkshopSession.isWorldScope()) ci.cancel();
    }
    @Inject(method = "handleRespawn", at = @At("RETURN"))
    private void workshop$respawnReminder(CallbackInfo ci) {
        if (WorkshopSession.isWorldScope()) {
            WorkshopSession.remote().recordScreen(null);
            WorkshopSession.notifyLocal("远端角色已重生或切换维度");
        }
    }
}
