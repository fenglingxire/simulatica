package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopMetadata;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.stream.Collectors;

@Mixin(Holder.Reference.class)
public abstract class WorkshopHolderMixin<T> {
    @SuppressWarnings("unchecked")
    private Holder.Reference<T> simulatica$remote() {
        return WorkshopMetadata.holder((Holder.Reference<T>) (Object) this);
    }

    @Inject(method = "components", at = @At("HEAD"), cancellable = true)
    private void simulatica$components(CallbackInfoReturnable<DataComponentMap> cir) {
        var remote = simulatica$remote();
        if (remote != null) cir.setReturnValue(remote.components());
    }

    @Inject(method = "bindComponents", at = @At("HEAD"), cancellable = true)
    private void simulatica$bindComponents(DataComponentMap components, CallbackInfo ci) {
        var remote = simulatica$remote();
        if (remote != null) {
            remote.bindComponents(components);
            ci.cancel();
        }
    }

    @Inject(method = "areComponentsBound", at = @At("HEAD"), cancellable = true)
    private void simulatica$componentsBound(CallbackInfoReturnable<Boolean> cir) {
        var remote = simulatica$remote();
        if (remote != null) cir.setReturnValue(remote.areComponentsBound());
    }

    @Inject(method = "boundTags", at = @At("HEAD"), cancellable = true)
    private void simulatica$tags(CallbackInfoReturnable<Set<TagKey<T>>> cir) {
        var remote = simulatica$remote();
        if (remote != null) cir.setReturnValue(remote.tags().collect(Collectors.toUnmodifiableSet()));
    }

    @Inject(method = "canSerializeIn", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void simulatica$serialize(HolderOwner<T> context, CallbackInfoReturnable<Boolean> cir) {
        var reference = (Holder.Reference<T>) (Object) this;
        if (context instanceof Registry<?> registry
                && registry instanceof WorkshopMetadata.ComponentRegistry clone && clone.simulatica$staticClone()
                && reference.isBound() && registry.key().equals(reference.key().registryKey())) {
            var target = ((Registry<T>) registry).get(reference.key()).orElse(null);
            if (target != null && target.isBound() && target.value() == reference.value()) {
                cir.setReturnValue(true);
                return;
            }
        }
        var remote = simulatica$remote();
        HolderOwner<T> owner = context;
        if (context instanceof Registry<?> registry) {
            var remoteRegistry = WorkshopMetadata.registry((Registry<T>) registry);
            if (remoteRegistry != null) owner = remoteRegistry;
        }
        if (remote != null || owner != context) {
            var holder = remote != null ? remote : (Holder.Reference<T>) (Object) this;
            cir.setReturnValue(holder.canSerializeIn(owner));
        }
    }
}
