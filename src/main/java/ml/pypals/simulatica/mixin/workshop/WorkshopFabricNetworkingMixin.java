package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ml.pypals.simulatica.workshop.WorkshopSession;
import net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl;
import net.fabricmc.fabric.impl.networking.client.ClientConfigurationNetworkAddon;
import net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon;
import net.minecraft.network.Connection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.fabricmc.fabric.mixin.networking.client.accessor.ClientCommonPacketListenerImplAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.Collections;

@Mixin(ClientNetworkingImpl.class)
public class WorkshopFabricNetworkingMixin {
    @Shadow private static ClientConfigurationNetworkAddon currentConfigurationAddon;
    @Shadow private static ClientPlayNetworkAddon currentPlayAddon;
    @Unique private static final Map<Connection, ClientConfigurationNetworkAddon> simulatica$config = Collections.synchronizedMap(new WeakHashMap<>());
    @Unique private static final Map<Connection, ClientPlayNetworkAddon> simulatica$play = Collections.synchronizedMap(new WeakHashMap<>());
    @Unique private static final Object simulatica$globalAddons = new Object();

    @Unique private static Connection simulatica$owner() {
        var remote = WorkshopSession.remote();
        return WorkshopSession.isRemoteScope() && remote != null ? WorkshopSession.sourceConnection() : null;
    }

    @Inject(method = "getClientConfigurationAddon", at = @At("HEAD"), cancellable = true)
    private static void simulatica$getConfiguration(CallbackInfoReturnable<ClientConfigurationNetworkAddon> cir) {
        var owner = simulatica$owner();
        if (owner != null) cir.setReturnValue(simulatica$config.get(owner));
    }

    @Inject(method = "getLoginConnection", at = @At("HEAD"), cancellable = true)
    private static void simulatica$getLogin(CallbackInfoReturnable<Connection> cir) {
        var owner = simulatica$owner();
        if (owner != null) cir.setReturnValue(owner);
    }

    @Inject(method = "getClientPlayAddon", at = @At("HEAD"), cancellable = true)
    private static void simulatica$getPlay(CallbackInfoReturnable<ClientPlayNetworkAddon> cir) {
        var owner = simulatica$owner();
        if (owner != null) {
            var listener = owner.getPacketListener();
            cir.setReturnValue(listener instanceof net.minecraft.client.multiplayer.ClientPacketListener play
                    ? ClientNetworkingImpl.getAddon(play) : simulatica$play.get(owner));
        }
    }

    @Inject(method = "setClientConfigurationAddon", at = @At("HEAD"), cancellable = true)
    private static void simulatica$setConfiguration(ClientConfigurationNetworkAddon addon, CallbackInfo ci) {
        var owner = simulatica$owner();
        if (owner != null) {
            if (addon == null) simulatica$config.remove(owner); else simulatica$config.put(owner, addon);
            ci.cancel();
        }
    }

    @Inject(method = "setClientPlayAddon", at = @At("HEAD"), cancellable = true)
    private static void simulatica$setPlay(ClientPlayNetworkAddon addon, CallbackInfo ci) {
        var owner = simulatica$owner();
        if (owner != null) {
            if (addon == null) simulatica$play.remove(owner); else simulatica$play.put(owner, addon);
            ci.cancel();
        }
    }

    @WrapMethod(method = "setClientPlayAddon")
    private static void simulatica$serializePlaySetter(ClientPlayNetworkAddon addon, Operation<Void> original) {
        synchronized (simulatica$globalAddons) { original.call(addon); }
    }

    @WrapMethod(method = "setClientConfigurationAddon")
    private static void simulatica$serializeConfigurationSetter(ClientConfigurationNetworkAddon addon, Operation<Void> original) {
        synchronized (simulatica$globalAddons) { original.call(addon); }
    }

    @WrapOperation(method = "lambda$clientInit$0", at = @At(value = "FIELD", target = "Lnet/fabricmc/fabric/impl/networking/client/ClientNetworkingImpl;currentPlayAddon:Lnet/fabricmc/fabric/impl/networking/client/ClientPlayNetworkAddon;"))
    private static void simulatica$disconnectPlay(ClientPlayNetworkAddon addon, Operation<Void> original, ClientPacketListener listener, Minecraft minecraft) {
        var source = WorkshopSession.sourceConnection();
        if (source == null) source = listener.getConnection();
        simulatica$play.remove(source);
        simulatica$config.remove(source);
        if (WorkshopSession.retired(source)) return;
        synchronized (simulatica$globalAddons) {
            if (currentPlayAddon != null && ((WorkshopFabricAddonAccessor) (Object) currentPlayAddon).simulatica$connection() == source) {
                original.call(addon);
            }
        }
    }

    @WrapOperation(method = "lambda$clientInit$1", at = @At(value = "FIELD", target = "Lnet/fabricmc/fabric/impl/networking/client/ClientNetworkingImpl;currentConfigurationAddon:Lnet/fabricmc/fabric/impl/networking/client/ClientConfigurationNetworkAddon;"))
    private static void simulatica$disconnectConfiguration(ClientConfigurationNetworkAddon addon,
            Operation<Void> original, ClientConfigurationPacketListenerImpl listener, Minecraft minecraft) {
        var source = WorkshopSession.sourceConnection();
        if (source == null) source = ((ClientCommonPacketListenerImplAccessor) listener).getConnection();
        simulatica$config.remove(source);
        simulatica$play.remove(source);
        if (WorkshopSession.retired(source)) return;
        synchronized (simulatica$globalAddons) {
            if (currentConfigurationAddon != null && ((WorkshopFabricAddonAccessor) (Object) currentConfigurationAddon).simulatica$connection() == source) {
                original.call(addon);
            }
        }
    }
}
