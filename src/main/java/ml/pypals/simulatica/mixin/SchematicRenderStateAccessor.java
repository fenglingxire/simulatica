package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.render.schematic.SchematicRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

@Mixin(value = SchematicRenderState.class, remap = false)
public interface SchematicRenderStateAccessor {
    @Accessor("blockEntityStates")
    List<BlockEntityRenderState> simulatica$blockEntities();
}
