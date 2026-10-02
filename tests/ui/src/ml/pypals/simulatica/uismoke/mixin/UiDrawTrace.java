package ml.pypals.simulatica.uismoke.mixin;

import ml.pypals.simulatica.uismoke.UiLayoutSmokeTest;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [测试专用] 记录 Simulatica 控制面板真实提交的绘制调用。
 *
 * <p>只观察、不改变行为：把 {@code text(...)} 的坐标与当前 scissor 交给
 * {@link UiLayoutSmokeTest}，这样断言用的是真实字体宽度和真实裁剪区，
 * 而不是测试里另抄一份布局算式。</p>
 *
 * <p>必须放在 mixin 专属子包里：Mixin 不允许直接引用 mixin 包内的类，
 * 测试入口类在同包会导致启动即崩。</p>
 */
@Mixin(GuiGraphicsExtractor.class)
public class UiDrawTrace {

    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V", at = @At("HEAD"))
    private void simulatica$traceString(Font font, String text, int x, int y, int color, CallbackInfo ci) {
        UiLayoutSmokeTest.recordText(font, text, x, y);
    }

    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V", at = @At("HEAD"))
    private void simulatica$traceComponent(Font font, net.minecraft.network.chat.Component text,
                                           int x, int y, int color, CallbackInfo ci) {
        UiLayoutSmokeTest.recordText(font, text.getString(), x, y);
    }

    @Inject(method = "enableScissor(IIII)V", at = @At("HEAD"))
    private void simulatica$traceScissorOn(int x1, int y1, int x2, int y2, CallbackInfo ci) {
        UiLayoutSmokeTest.recordScissor(x1, y1, x2, y2);
    }

    @Inject(method = "disableScissor()V", at = @At("HEAD"))
    private void simulatica$traceScissorOff(CallbackInfo ci) {
        UiLayoutSmokeTest.recordScissorOff();
    }
}
