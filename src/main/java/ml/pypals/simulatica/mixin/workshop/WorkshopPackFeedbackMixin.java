package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import ml.pypals.simulatica.workshop.WorkshopMetadata;
import net.minecraft.client.resources.server.PackLoadFeedback;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.server.packs.DownloadQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.UUID;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

@Mixin(ServerPackManager.class)
public class WorkshopPackFeedbackMixin {
    @Shadow private void registerForUpdate() { throw new AssertionError(); }

    @WrapMethod(method = "onDownload")
    private void simulatica$currentDownloadBatch(Collection<?> data, DownloadQueue.BatchResult result, Operation<Void> original) {
        var current = data.stream().filter(WorkshopMetadata::currentPack).toList();
        if (current.isEmpty()) {
            registerForUpdate();
            return;
        }
        if (current.size() != data.size()) {
            var ids = current.stream().map(pack -> ((WorkshopMetadata.OwnedPack) pack).simulatica$packId()).collect(Collectors.toSet());
            var downloaded = result.downloaded().entrySet().stream().filter(entry -> ids.contains(entry.getKey()))
                    .collect(Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
            var failed = result.failed().stream().filter(ids::contains).collect(Collectors.toSet());
            result = new DownloadQueue.BatchResult(downloaded, failed);
        }
        original.call(current, result);
    }

    @WrapOperation(method = "onDownload", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<?> simulatica$currentPacks(List<?> packs, Operation<Iterator<?>> original) {
        return packs.stream().filter(WorkshopMetadata::currentPack).iterator();
    }
    @WrapOperation(method = {"acceptPack", "onDownload", "lambda$cleanupRemovedPacks$0"}, at = @At(value = "FIELD", target = "Lnet/minecraft/client/resources/server/ServerPackManager$ServerPackData;id:Ljava/util/UUID;"))
    private UUID simulatica$packOwner(@Coerce Object pack, Operation<UUID> original) {
        return WorkshopMetadata.packId(pack, original.call(pack));
    }

    @WrapOperation(method = {"acceptPack", "onDownload"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/server/PackLoadFeedback;reportUpdate(Ljava/util/UUID;Lnet/minecraft/client/resources/server/PackLoadFeedback$Update;)V"))
    private void simulatica$update(PackLoadFeedback feedback, UUID id, PackLoadFeedback.Update update, Operation<Void> original) {
        var owner = WorkshopMetadata.takePackOwner();
        if (owner == null || owner.current()) original.call(feedback, id, update);
    }

    @WrapOperation(method = "lambda$cleanupRemovedPacks$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/server/PackLoadFeedback;reportFinalResult(Ljava/util/UUID;Lnet/minecraft/client/resources/server/PackLoadFeedback$FinalResult;)V"))
    private void simulatica$final(PackLoadFeedback feedback, UUID id, PackLoadFeedback.FinalResult result, Operation<Void> original) {
        var owner = WorkshopMetadata.takePackOwner();
        if (owner == null || owner.current()) original.call(feedback, id, result);
    }
}
