package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopMetadata;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientRegistryLayer.class)
public class WorkshopRegistryLayerMixin {
    @WrapOperation(method = "createRegistryAccess", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/ClientRegistryLayer;STATIC_ACCESS:Lnet/minecraft/core/RegistryAccess$Frozen;"))
    private static RegistryAccess.Frozen simulatica$remoteStaticAccess(Operation<RegistryAccess.Frozen> original) {
        return WorkshopMetadata.staticAccess(original.call());
    }
}
