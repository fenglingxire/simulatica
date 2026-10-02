package ml.pypals.simulatica.mixin.workshop;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ml.pypals.simulatica.workshop.WorkshopMetadata;
import net.minecraft.client.resources.server.PackLoadFeedback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;

@Mixin(targets = "net.minecraft.client.resources.server.ServerPackManager$1")
public class WorkshopPackReloadFeedbackMixin implements WorkshopMetadata.OwnedReload {
    @Shadow @Final private List<Object> val$packsToLoad;
    @Shadow @Final private List<Object> val$packsToUnload;
    @Unique private Set<WorkshopMetadata.PackOwner> simulatica$owners;
    @Unique private WorkshopMetadata.PackOwner simulatica$owner;

    @Unique private void simulatica$captureOwners() {
        if (simulatica$owners != null) return;
        simulatica$owners = new HashSet<>();
        for (var packs : List.of(val$packsToLoad, val$packsToUnload)) {
            for (var pack : packs) {
                var owner = ((WorkshopMetadata.OwnedPack) pack).simulatica$packOwner();
                simulatica$owners.add(owner);
                if (simulatica$owner == null) simulatica$owner = owner;
            }
        }
    }

    @Override public WorkshopMetadata.PackOwner simulatica$reloadOwner() {
        simulatica$captureOwners();
        return simulatica$owner;
    }

    @Override public boolean simulatica$ownsPack(Object pack) {
        simulatica$captureOwners();
        return simulatica$owners.contains(((WorkshopMetadata.OwnedPack) pack).simulatica$packOwner());
    }

    @Override public void simulatica$adoptOwner(WorkshopMetadata.PackOwner owner) {
        simulatica$owner = owner;
        simulatica$owners = Set.of(owner);
    }

    @WrapOperation(method = "onFailure", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<?> simulatica$batchFailurePacks(List<?> packs, Operation<Iterator<?>> original) {
        return packs.stream().filter(this::simulatica$ownsPack).iterator();
    }
    @WrapOperation(method = "onSuccess", at = @At(value = "FIELD", target = "Lnet/minecraft/client/resources/server/ServerPackManager$ServerPackData;id:Ljava/util/UUID;"))
    private UUID simulatica$owner(@Coerce Object pack, Operation<UUID> original) {
        return WorkshopMetadata.packId(pack, original.call(pack));
    }

    @WrapOperation(method = "onSuccess", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/server/PackLoadFeedback;reportFinalResult(Ljava/util/UUID;Lnet/minecraft/client/resources/server/PackLoadFeedback$FinalResult;)V"))
    private void simulatica$final(PackLoadFeedback feedback, UUID id, PackLoadFeedback.FinalResult result, Operation<Void> original) {
        var owner = WorkshopMetadata.takePackOwner();
        if (owner == null || owner.current()) original.call(feedback, id, result);
    }
}
