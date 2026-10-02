package ml.pypals.simulatica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.world.WorldSchematic;
import ml.pypals.simulatica.render.ProjectionPistonRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonHeadRenderer.class)
public abstract class PistonHeadRendererMixin {
    @Inject(method = "createRenderState()Lnet/minecraft/client/renderer/blockentity/state/PistonHeadRenderState;",
            at = @At("RETURN"), cancellable = true)
    private void simulatica$createState(CallbackInfoReturnable<PistonHeadRenderState> cir) {
        cir.setReturnValue(new ProjectionPistonRenderer.State());
    }

    // Only the environment lookup changes; vanilla still derives models and offsets from the piston.
    @WrapOperation(method = "extractRenderState(Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/PistonHeadRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;getLevel()Lnet/minecraft/world/level/Level;"))
    private Level simulatica$renderEnvironment(PistonMovingBlockEntity piston, Operation<Level> original) {
        Level level = original.call(piston);
        return level instanceof WorldSchematic ? Minecraft.getInstance().level : level;
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/PistonHeadRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V", at = @At("TAIL"))
    private void simulatica$markProjection(PistonMovingBlockEntity piston, PistonHeadRenderState state,
                                         float partialTick, Vec3 camera,
                                         ModelFeatureRenderer.CrumblingOverlay breaking, CallbackInfo ci) {
        ((ProjectionPistonRenderer.State) state).projected = piston.getLevel() instanceof WorldSchematic;
    }

    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/PistonHeadRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitMovingBlock(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/block/MovingBlockRenderState;I)V"))
    private void simulatica$submitProjection(SubmitNodeCollector collector, PoseStack pose,
                                            MovingBlockRenderState block, int outline,
                                            Operation<Void> original,
                                            @Local(argsOnly = true) PistonHeadRenderState piston) {
        if (piston instanceof ProjectionPistonRenderer.State state && state.projected) {
            ProjectionPistonRenderer.submit(collector, pose, block,
                    block == state.base ? state.baseColor : state.blockColor);
        } else {
            original.call(collector, pose, block, outline);
        }
    }
}
