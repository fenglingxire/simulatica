package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopMetadata;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(HolderSet.Named.class)
public class WorkshopNamedHolderSetMixin<T> {
    @Inject(method = "contents", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void simulatica$contents(CallbackInfoReturnable<List<Holder<T>>> cir) {
        var remote = WorkshopMetadata.tag((HolderSet.Named<T>) (Object) this);
        if (remote != null) cir.setReturnValue(remote.stream().toList());
    }

    @Inject(method = "canSerializeIn", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void simulatica$serialize(HolderOwner<T> context, CallbackInfoReturnable<Boolean> cir) {
        var named = (HolderSet.Named<T>) (Object) this;
        if (context instanceof Registry<?> registry
                && registry instanceof WorkshopMetadata.ComponentRegistry clone && clone.simulatica$staticClone()
                && registry.key().equals(named.key().registry())
                && ((Registry<T>) registry).get(named.key()).isPresent()) {
            cir.setReturnValue(true);
            return;
        }
        var remote = WorkshopMetadata.tag((HolderSet.Named<T>) (Object) this);
        HolderOwner<T> owner = context;
        if (context instanceof Registry<?> registry) {
            var remoteRegistry = WorkshopMetadata.registry((Registry<T>) registry);
            if (remoteRegistry != null) owner = remoteRegistry;
        }
        if (remote != null || owner != context) {
            var tag = remote != null ? remote : (HolderSet.Named<T>) (Object) this;
            cir.setReturnValue(tag.canSerializeIn(owner));
        }
    }
}
