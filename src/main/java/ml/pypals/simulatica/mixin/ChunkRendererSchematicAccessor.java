package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.util.OverlayType;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ChunkRendererSchematicVbo.class, remap = false)
public interface ChunkRendererSchematicAccessor {
    @Invoker("getOverlayType")
    OverlayType simulatica$overlayType(BlockState schematic, BlockState real);

    @Invoker("getOverlayColor")
    static Color4f simulatica$overlayColor(OverlayType type) {
        throw new AssertionError();
    }
}
