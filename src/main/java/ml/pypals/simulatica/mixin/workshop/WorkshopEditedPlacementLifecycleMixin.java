package ml.pypals.simulatica.mixin.workshop;

import fi.dy.masa.litematica.event.WorldLoadListener;
import ml.pypals.simulatica.workshop.EditedPlacementCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldLoadListener.class, remap = false)
public abstract class WorkshopEditedPlacementLifecycleMixin {
    @Inject(method = "onWorldLoadPre", at = @At("HEAD"))
    private void simulatica$remember(ClientLevel before, ClientLevel after, Minecraft mc, CallbackInfo ci) {
        if (before != null) EditedPlacementCache.beforeWorldChange();
    }
    @Inject(method = "onWorldLoadPost", at = @At("RETURN"))
    private void simulatica$restore(ClientLevel before, ClientLevel after, Minecraft mc, CallbackInfo ci) {
        if (after != null) EditedPlacementCache.restore();
    }
}
