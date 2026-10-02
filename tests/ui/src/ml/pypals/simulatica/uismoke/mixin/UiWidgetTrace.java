package ml.pypals.simulatica.uismoke.mixin;

import ml.pypals.simulatica.uismoke.UiLayoutSmokeTest;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [测试专用] 记录每个控件真实绘制时的位置和当时生效的裁剪区。
 *
 * <p>控制面板把左列按钮包在 scissor 里画，所以「半露的按钮」在屏幕上的实际占位
 * 是 <em>控件矩形 ∩ 裁剪区</em>。断言必须用这个交集，否则滚动中间态会被误报成越界。</p>
 */
@Mixin(AbstractWidget.class)
public class UiWidgetTrace {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"))
    private void simulatica$traceWidget(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                        float partialTick, CallbackInfo ci) {
        UiLayoutSmokeTest.recordWidget((AbstractWidget) (Object) this);
    }
}
