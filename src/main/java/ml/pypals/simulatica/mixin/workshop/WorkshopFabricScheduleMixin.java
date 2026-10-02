package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "net.fabricmc.fabric.impl.networking.client.ClientCommonNetworkAddon")
public class WorkshopFabricScheduleMixin {
    @ModifyArg(method = "schedule", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;execute(Ljava/lang/Runnable;)V"))
    private Runnable simulatica$ownedTask(Runnable task) {
        var owner = ((WorkshopFabricAddonAccessor) this).simulatica$connection();
        return () -> WorkshopSession.runFor(owner, task);
    }
}
