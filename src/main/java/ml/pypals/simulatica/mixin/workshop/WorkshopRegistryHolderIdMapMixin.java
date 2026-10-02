package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopMetadata;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.core.Registry$1")
public class WorkshopRegistryHolderIdMapMixin {
    @Shadow @Final private Registry<?> this$0;

    @Inject(method = "byId(I)Lnet/minecraft/core/Holder;", at = @At("RETURN"), cancellable = true)
    private void simulatica$canonicalWireHolder(int id, CallbackInfoReturnable<Holder<?>> cir) {
        if (!(this$0 instanceof WorkshopMetadata.ComponentRegistry registry) || !registry.simulatica$staticClone()) return;
        Holder<?> original = cir.getReturnValue();
        Holder<?> canonical = WorkshopMetadata.canonicalWireHolder(original);
        if (canonical != original) cir.setReturnValue(canonical);
    }
}
