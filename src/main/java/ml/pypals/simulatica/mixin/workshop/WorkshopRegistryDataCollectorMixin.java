package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopMetadata;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RegistryDataCollector.class)
public class WorkshopRegistryDataCollectorMixin {
    @Inject(method = "collectGameRegistries", at = @At("RETURN"))
    private void simulatica$refreshComponentLookups(CallbackInfoReturnable<RegistryAccess.Frozen> cir) {
        WorkshopMetadata.invalidateComponentLookups();
    }
}
