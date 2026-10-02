package ml.pypals.simulatica.mixin.workshop;
import ml.pypals.simulatica.workshop.WorkshopEdit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerLevel.class)
public abstract class WorkshopEntityTrackingMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void simulatica$added(Entity entity, CallbackInfoReturnable<Boolean> cir) { WorkshopEdit.onEntityMoved((ServerLevel)(Object)this, entity); }
    @Inject(method = "tickNonPassenger", at = @At("TAIL"))
    private void simulatica$moved(Entity entity, CallbackInfo ci) { WorkshopEdit.onEntityMoved((ServerLevel)(Object)this, entity); }
}
