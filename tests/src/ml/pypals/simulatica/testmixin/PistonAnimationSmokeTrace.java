package ml.pypals.simulatica.testmixin;
import ml.pypals.simulatica.simulation.server.PistonAnimationSmokeTest;
import net.minecraft.world.level.block.Blocks;
/** Observes the real frame submission, after schematic chunk visibility and culling. */
@org.spongepowered.asm.mixin.Mixin(net.minecraft.client.renderer.blockentity.PistonHeadRenderer.class)
public class PistonAnimationSmokeTrace {
    @org.spongepowered.asm.mixin.injection.Inject(
        method="submit(Lnet/minecraft/client/renderer/blockentity/state/PistonHeadRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at=@org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void trace(net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState state,
                       com.mojang.blaze3d.vertex.PoseStack pose,
                       net.minecraft.client.renderer.SubmitNodeCollector collector,
                       net.minecraft.client.renderer.state.level.CameraRenderState camera,
                       org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!(state instanceof ml.pypals.simulatica.render.ProjectionPistonRenderer.State p)
                || !p.projected || state.block == null) return;
        PistonAnimationSmokeTest.submissions++;
        float distance = Math.abs(state.xOffset);
        if (distance <= 0 || distance >= 1 || p.blockColor == null) return;
        if (state.block.blockState.is(Blocks.PISTON_HEAD) || state.block.blockState.is(Blocks.STICKY_PISTON)) PistonAnimationSmokeTest.movingHeads++;
        if (state.block.blockState.is(Blocks.STONE)) PistonAnimationSmokeTest.movingBlocks++;
    }
}
