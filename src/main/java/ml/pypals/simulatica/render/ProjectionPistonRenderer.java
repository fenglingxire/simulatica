package ml.pypals.simulatica.render;

import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.malilib.util.data.Color4f;
import ml.pypals.simulatica.mixin.ChunkRendererSchematicAccessor;
import ml.pypals.simulatica.mixin.RenderTypeInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla piston models and transforms, with Litematica's ghost alpha and moving overlays. */
public final class ProjectionPistonRenderer {
    public static final class State extends PistonHeadRenderState {
        public boolean projected;
        public Color4f blockColor;
        public Color4f baseColor;
    }

    private static final RenderType SIDES = overlayType(OverlayRenderType.QUAD, false);
    private static final RenderType SIDES_THROUGH = overlayType(OverlayRenderType.QUAD, true);
    private static final RenderType OUTLINES = overlayType(OverlayRenderType.OUTLINE, false);
    private static final RenderType OUTLINES_THROUGH = overlayType(OverlayRenderType.OUTLINE, true);

    private ProjectionPistonRenderer() {}

    private static RenderType overlayType(OverlayRenderType type, boolean through) {
        // Composite the overlay together with vanilla's translucent moving model.
        var setup = RenderSetup.builder(through ? type.renderThrough() : type.pipeline())
                .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET);
        if (type == OverlayRenderType.QUAD) setup.sortOnUpload();
        return RenderTypeInvoker.simulatica$create("simulatica_piston_" + type + "_" + through,
                setup.createRenderSetup());
    }

    public static void setOverlayColors(State state, ChunkRendererSchematicVbo renderer) {
        state.blockColor = overlayColor(state.block, renderer);
        state.baseColor = overlayColor(state.base, renderer);
    }

    private static Color4f overlayColor(MovingBlockRenderState moving, ChunkRendererSchematicVbo renderer) {
        if (moving == null || Minecraft.getInstance().level == null) return null;
        return ChunkRendererSchematicAccessor.simulatica$overlayColor(
                ((ChunkRendererSchematicAccessor) renderer).simulatica$overlayType(moving.blockState,
                        Minecraft.getInstance().level.getBlockState(moving.blockPos)));
    }

    public static void submit(SubmitNodeCollector collector, PoseStack pose,
                              MovingBlockRenderState moving, Color4f overlayColor) {
        // The caller is vanilla PistonHeadRenderer.submit: its pose already includes piston motion.
        if (Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue()) {
            int alpha = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue()
                    ? (int) Math.round(255 * Configs.Visuals.GHOST_BLOCK_ALPHA.getDoubleValue()) : 255;
            collector.submitCustomGeometry(pose, RenderTypes.translucentMovingBlock(), (transform, buffer) ->
                    tessellate(moving, moving.blockState, (x, y, z, quad, colors) -> {
                        int[] original = {colors.getColor(0), colors.getColor(1), colors.getColor(2), colors.getColor(3)};
                        int[] lights = {colors.getLightCoords(0), colors.getLightCoords(1), colors.getLightCoords(2), colors.getLightCoords(3)};
                        try {
                            colors.multiplyColor(alpha << 24 | 0xFFFFFF);
                            if (Configs.Visuals.ENABLE_SCHEMATIC_FAKE_LIGHTING.getBooleanValue()) {
                                int level = Configs.Visuals.RENDER_FAKE_LIGHTING_LEVEL.getIntegerValue();
                                for (int i = 0; i < 4; i++) colors.setLightCoords(i, LightCoordsUtil.pack(level, level));
                            }
                            buffer.putBakedQuad(transform, quad, colors);
                        } finally {
                            for (int i = 0; i < 4; i++) {
                                colors.setColor(i, original[i]);
                                colors.setLightCoords(i, lights[i]);
                            }
                        }
                    }));
        }
        if (overlayColor == null || !Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY.getBooleanValue()) return;

        boolean through = Configs.Visuals.SCHEMATIC_OVERLAY_RENDER_THROUGH.getBooleanValue()
                || Hotkeys.RENDER_OVERLAY_THROUGH_BLOCKS.getKeybind().isKeybindHeld();
        if (Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_SIDES.getBooleanValue()) {
            BlockState shape = Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_SIDES.getBooleanValue()
                    ? moving.blockState : Blocks.STONE.defaultBlockState();
            // Custom geometry batches do not preserve insertion order across render types.
            collector.order(1).submitCustomGeometry(pose, through ? SIDES_THROUGH : SIDES, (transform, buffer) ->
                    tessellate(moving, shape, (x, y, z, quad, colors) -> {
                        for (int i = 0; i < 4; i++) {
                            buffer.addVertex(transform, quad.position(i))
                                    .setColor(overlayColor.r, overlayColor.g, overlayColor.b, overlayColor.a);
                        }
                    }));
        }
        if (Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_OUTLINES.getBooleanValue()) {
            BlockState shape = Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_OUTLINE.getBooleanValue()
                    ? moving.blockState : Blocks.STONE.defaultBlockState();
            float width = (float) (through ? Configs.Visuals.SCHEMATIC_OVERLAY_OUTLINE_WIDTH_THROUGH
                    : Configs.Visuals.SCHEMATIC_OVERLAY_OUTLINE_WIDTH).getDoubleValue();
            collector.order(2).submitCustomGeometry(pose, through ? OUTLINES_THROUGH : OUTLINES, (transform, buffer) ->
                    tessellate(moving, shape, (x, y, z, quad, colors) -> {
                        for (int i = 0; i < 4; i++) {
                            buffer.addVertex(transform, quad.position(i))
                                    .setColor(overlayColor.r, overlayColor.g, overlayColor.b, 1).setLineWidth(width);
                            buffer.addVertex(transform, quad.position((i + 1) % 4))
                                    .setColor(overlayColor.r, overlayColor.g, overlayColor.b, 1).setLineWidth(width);
                        }
                    }));
        }
    }

    private static void tessellate(MovingBlockRenderState moving, BlockState shape, BlockQuadOutput output) {
        Minecraft mc = Minecraft.getInstance();
        ModelBlockRenderer renderer = new ModelBlockRenderer(mc.options.ambientOcclusion().get(), false, mc.getBlockColors());
        renderer.tesselateBlock(output, 0, 0, 0, moving, moving.blockPos, shape,
                mc.getModelManager().getBlockStateModelSet().get(shape), shape.getSeed(moving.randomSeedPos));
    }
}
