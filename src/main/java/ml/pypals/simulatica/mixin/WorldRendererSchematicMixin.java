package ml.pypals.simulatica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import ml.pypals.simulatica.render.ProjectionPistonRenderer;
import ml.pypals.simulatica.simulation.SimulationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 适配 26.2 渲染管线：render(GuiGraphics) → extractRenderState(GuiGraphicsExtractor, ...)
 */
@Mixin(value = WorldRendererSchematic.class, remap = false)
public class WorldRendererSchematicMixin {
    @Shadow protected WorldSchematic world;
    @Shadow protected List<ChunkRendererSchematicVbo> renderInfos;

    @Inject(method = "prepareBlockEntities", at = @At("TAIL"))
    private void simulatica$prepareMovingPistons(Camera camera, Frustum frustum, LevelRenderState levelState,
                                                PoseStack pose, float partialTick, ProfilerFiller profiler,
                                                CallbackInfo ci) {
        var states = ((SchematicRenderStateAccessor) ((WorldRendererSchematic) (Object) this)
                .getSchematicRenderState()).simulatica$blockEntities();
        // Moving pistons last only a few ticks. Read live entities rather than a delayed mesh cache.
        states.removeIf(state -> state instanceof ProjectionPistonRenderer.State piston && piston.projected);
        // Litematica's renderer covers the chunk's full height, not one 16-block section.
        for (ChunkRendererSchematicVbo renderer : this.renderInfos) {
            var pos = renderer.getOrigin();
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            ChunkSchematic chunk = this.world.getChunkSource().getChunkForLighting(cx, cz);
            if (chunk == null || !DataManager.getSchematicPlacementManager().checkIfChunkShouldRender(cx, cz)) continue;
            for (var entity : chunk.getBlockEntities().values()) {
                if (!(entity instanceof PistonMovingBlockEntity) || entity.isRemoved()) continue;
                var blockPos = entity.getBlockPos();
                if (!DataManager.getRenderLayerRange().isPositionWithinRange(blockPos.getX(), blockPos.getY(), blockPos.getZ())
                        || !frustum.isVisible(new AABB(blockPos).inflate(1))) continue;
                var extracted = Minecraft.getInstance().getBlockEntityRenderDispatcher()
                        .tryExtractRenderState(entity, partialTick, null, false);
                if (extracted instanceof ProjectionPistonRenderer.State piston) {
                    ProjectionPistonRenderer.setOverlayColors(piston, renderer);
                    states.add(piston);
                }
            }
        }
    }

    @WrapOperation(
            method = "prepareBlockEntities",
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/world/ChunkSchematic;getTimeCreated()J")
    )
    private long simulatica$ignoreRebuildAge(ChunkSchematic chunk, Operation<Long> original) {
        if (SimulationManager.getInstance().isSimulatedChunk(chunk.getPos().x(), chunk.getPos().z())) {
            return Long.MIN_VALUE;
        }
        return original.call(chunk);
    }
}
