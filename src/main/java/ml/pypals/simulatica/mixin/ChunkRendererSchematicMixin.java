package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkMeshDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDispatcherBuffers;
import fi.dy.masa.litematica.util.OverlayType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkRendererSchematicVbo.class, remap = false)
public abstract class ChunkRendererSchematicMixin {
    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private void simulatica$skipStationaryPistonOverlay(OverlayType type, BlockPos pos,
                                                       BlockState state, boolean missing,
                                                       ChunkRenderDataSchematic data, ChunkMeshDataSchematic mesh,
                                                       ChunkRenderDispatcherBuffers buffers,
                                                       CallbackInfo ci) {
        if (state.is(Blocks.MOVING_PISTON)) ci.cancel();
    }
}
