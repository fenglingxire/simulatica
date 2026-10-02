package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import ml.pypals.simulatica.SimulaticaMenuScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [SIMULATICA-新增] 在 Litematica 主菜单第二列「任务管理器」按钮下方追加「投影交互菜单」按钮。
 *
 * <p>主菜单布局（GuiMainMenu.initGui）：第二列 x = 12 + getButtonWidth() + 20，纵向依次为
 * 配置(30) → 原理图管理器(118) → 任务管理器(140)，若开启「原理图项目管理器」则再加 22。
 * 按钮点击后打开 Simulatica 控制面板。</p>
 */
@Mixin(value = GuiMainMenu.class, remap = false)
public abstract class LitematicaMainMenuMixin {

    @Inject(method = "initGui", at = @At("TAIL"))
    private void simulatica$addMenuButton(CallbackInfo ci) {
        GuiMainMenu self = (GuiMainMenu) (Object) this;
        int width = this.simulatica$getButtonWidth();
        int x = 12 + width + 20;

        // 任务管理器位于 y=140；若「原理图项目管理器」按钮存在，则本按钮再下移一行。
        boolean projectsShown = Configs.Generic.UNHIDE_SCHEMATIC_PROJECTS.getBooleanValue();
        int y = projectsShown ? 184 : 162;

        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, "投影交互菜单");
        self.addButton(button, (btn, mouseButton) -> {
            if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) {
                ml.pypals.simulatica.workshop.WorkshopManager.requestReturn();
            } else Minecraft.getInstance().gui.setScreen(new SimulaticaMenuScreen());
        });
    }

    @Invoker("getButtonWidth")
    abstract int simulatica$getButtonWidth();
}
