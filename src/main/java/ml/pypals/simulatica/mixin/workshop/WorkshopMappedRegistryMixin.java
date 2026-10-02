package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopMetadata;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentLookup;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.stream.Stream;

@Mixin(MappedRegistry.class)
public abstract class WorkshopMappedRegistryMixin<T> implements WorkshopMetadata.ComponentRegistry {
    @Shadow private DataComponentLookup<T> componentLookup;
    @Unique private boolean simulatica$staticClone;

    @Override public boolean simulatica$staticClone() { return simulatica$staticClone; }
    @Override public void simulatica$staticClone(boolean clone) { simulatica$staticClone = clone; }

    @Override public void simulatica$invalidateComponents() {
        @SuppressWarnings("unchecked") var registry = (Registry<T>) (Object) this;
        componentLookup = new DataComponentLookup<>(() -> registry.listElements().map(holder -> (Holder<T>) holder).iterator());
    }
    @SuppressWarnings("unchecked")
    private Registry<T> simulatica$remote() {
        return WorkshopMetadata.registry((Registry<T>) (Object) this);
    }

    @Inject(method = "get(Lnet/minecraft/tags/TagKey;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private void simulatica$getTag(TagKey<T> tag, CallbackInfoReturnable<Optional<HolderSet.Named<T>>> cir) {
        var remote = simulatica$remote();
        if (remote != null) cir.setReturnValue(remote.get(tag));
    }

    @Inject(method = "getTags", at = @At("HEAD"), cancellable = true)
    private void simulatica$listTags(CallbackInfoReturnable<Stream<HolderSet.Named<T>>> cir) {
        var remote = simulatica$remote();
        if (remote != null) cir.setReturnValue(remote.getTags());
    }

    @Inject(method = "prepareTagReload", at = @At("HEAD"), cancellable = true)
    private void simulatica$prepareTags(TagLoader.LoadResult<T> tags, CallbackInfoReturnable<Registry.PendingTags<T>> cir) {
        var remote = simulatica$remote();
        if (remote != null) cir.setReturnValue(remote.prepareTagReload(WorkshopMetadata.retargetTags(remote, tags)));
    }

    @Inject(method = "prepareTagReload", at = @At("RETURN"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void simulatica$mirrorForegroundTags(TagLoader.LoadResult<T> tags, CallbackInfoReturnable<Registry.PendingTags<T>> cir) {
        cir.setReturnValue(WorkshopMetadata.mirrorForegroundTags((Registry<T>) (Object) this, tags, cir.getReturnValue()));
    }

    @Inject(method = "componentLookup", at = @At("HEAD"), cancellable = true)
    private void simulatica$lookupComponents(CallbackInfoReturnable<DataComponentLookup<T>> cir) {
        var remote = simulatica$remote();
        if (remote != null) cir.setReturnValue(remote.componentLookup());
    }
}
