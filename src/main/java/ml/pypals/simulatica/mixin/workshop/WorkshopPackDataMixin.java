package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Mixin(targets = "net.minecraft.client.resources.server.ServerPackManager$ServerPackData")
public class WorkshopPackDataMixin implements WorkshopMetadata.OwnedPack {
    @Shadow @Final private UUID id;
    @Unique private WorkshopMetadata.PackOwner simulatica$owner;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void simulatica$captureOwner(CallbackInfo ci) {
        simulatica$owner = WorkshopMetadata.capturePackOwner();
    }

    @Override public WorkshopMetadata.PackOwner simulatica$packOwner() { return simulatica$owner; }
    @Override public UUID simulatica$packId() { return id; }
    @Override public void simulatica$packOwner(WorkshopMetadata.PackOwner owner) { simulatica$owner = owner; }
}
