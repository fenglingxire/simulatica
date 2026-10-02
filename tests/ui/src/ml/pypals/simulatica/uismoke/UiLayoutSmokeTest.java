package ml.pypals.simulatica.uismoke;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import ml.pypals.simulatica.SimulaticaMenuScreen;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.SimulationCommands;
import ml.pypals.simulatica.simulation.server.SimulationServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * [测试专用] Simulatica 控制面板布局回归测试。
 *
 * <p>进入独立空白创造档、放 20 个投影放置，然后在真实客户端里把控制面板按一批屏幕尺寸
 * × 一批滚动位置逐一布局并渲染，用**真实字体宽度 + 真实裁剪区**断言：控件不越界、
 * 控件之间不相交、文字不压控件/不压文字、文字不被裁掉。最后对几个尺寸真截图。</p>
 *
 * <p>断言用的是 mixin 记录下来的真实绘制调用（{@code UiDrawTrace} 记文字与 scissor，
 * {@code UiWidgetTrace} 记控件），而不是测试里另抄一份布局算式；每个矩形都先按当时生效的
 * scissor 裁剪，所以「只露出一半的按钮」不会被误判成越界。</p>
 *
 * <p>运行：{@code .\gradlew.bat -I tests/ui-smoke.gradle runClient}</p>
 */
public class UiLayoutSmokeTest implements ClientModInitializer {

    /** 断言用的屏幕尺寸（GUI 缩放后的逻辑像素）。 */
    private static final int[][] SIZES = {
            {320, 240}, {427, 240}, {455, 256}, {480, 270},
            {640, 360}, {640, 400}, {800, 450}, {854, 480},
            {960, 540}, {1024, 768}, {1280, 720}, {1720, 720},
            {1920, 1080}, {2560, 1080}, {400, 900}, {1200, 300},
            {320, 480}, {360, 640}, {500, 250}, {700, 350},
    };

    /**
     * 每个尺寸要测的滚轮格数。0 = 顶，99 = 滚到底。
     * 中间几档是为了覆盖「按钮只露出一部分」的边界状态——左列可视区上下沿
     * 不对齐 22px 的行距，只有滚到某些位置才会出现半露按钮。
     */
    private static final int[] WHEEL_STEPS = {0, 1, 2, 3, 4, 5, 6, 99};

    /** 真正改窗口大小并截图的尺寸。 */
    private static final int[][] SHOTS = {
            {320, 240}, {427, 240}, {480, 270}, {640, 400}, {1200, 300}, {1920, 1080},
    };

    /**
     * 面板指令框的补全样本。候选应该**只有本 mod 的指令**，
     * 最后一条用来验证 {@code execute} 后面仍然补全模拟世界的指令。
     */
    private static final String[] TAB_CASES = {"", "/", "s", "st", "simulatica ", "/simulatica ", "execute time "};

    /** 三轮：服务器关 / 服务器开但没在模拟 / 已启动一个模拟。 */
    private static final String[] TAB_ROUNDS = {"server-off", "server-on-no-sim", "sim-running"};

    // ------------------------------------------------------------------
    // 绘制记录（渲染与 tick 都在主线程，无需加锁）
    // ------------------------------------------------------------------
    public static volatile boolean recording;
    public static final List<Draw> draws = new ArrayList<>();
    public static final List<Draw> widgetDraws = new ArrayList<>();
    private static int scissorX1, scissorY1, scissorX2 = Integer.MAX_VALUE, scissorY2 = Integer.MAX_VALUE;
    private static boolean scissorActive;

    public record Draw(String text, int x, int y, int w, int h,
                       boolean scissored, int sx1, int sy1, int sx2, int sy2) {}

    public static void recordText(Font font, String text, int x, int y) {
        if (!recording || text == null || text.isEmpty()) {
            return;
        }
        draws.add(new Draw(text, x, y, font.width(text), Math.max(1, font.lineHeight - 1),
                scissorActive, scissorX1, scissorY1, scissorX2, scissorY2));
    }

    public static void recordWidget(AbstractWidget widget) {
        if (!recording || !widget.visible) {
            return;
        }
        String message = widget.getMessage().getString();
        String label = message.isEmpty() ? widget.getClass().getSimpleName() : message;
        widgetDraws.add(new Draw(label, widget.getX(), widget.getY(),
                widget.getWidth(), widget.getHeight(),
                scissorActive, scissorX1, scissorY1, scissorX2, scissorY2));
    }

    public static void recordScissor(int x1, int y1, int x2, int y2) {
        if (!recording) {
            return;
        }
        scissorX1 = x1;
        scissorY1 = y1;
        scissorX2 = x2;
        scissorY2 = y2;
        scissorActive = true;
    }

    public static void recordScissorOff() {
        if (!recording) {
            return;
        }
        scissorActive = false;
        scissorX2 = Integer.MAX_VALUE;
        scissorY2 = Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------------
    // 流程
    // ------------------------------------------------------------------
    private int ticks;
    private int index;
    private int stepIndex;
    private int phase;
    private int wait;
    private boolean worldRequested;
    private boolean worldReady;
    private boolean failed;
    private SimulaticaMenuScreen panel;
    private int shotIndex;
    private int shotPhase;
    private Set<String> shotsBefore = Set.of();
    private final List<String> report = new ArrayList<>();

    // TAB 探针状态
    private int tabRound;
    private int tabCase;
    private int tabWait;
    private int tabWarmup;
    private boolean tabRoundStarted;
    private boolean tabHandled;
    private final List<String> tabLog = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        ticks++;
        try {
            if (!worldReady) {
                if (!worldRequested && mc.gui.screen() instanceof TitleScreen && ticks > 10) {
                    worldRequested = true;
                    mc.createWorldOpenFlows().createFreshLevel("ui-layout-" + System.currentTimeMillis(),
                            new LevelSettings("UI layout test", GameType.CREATIVE,
                                    LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                            new WorldOptions(1, false, false), VoidWorld::create, mc.gui.screen());
                }
                if (mc.level != null && mc.player != null && mc.gui.screen() == null) {
                    worldReady = true;
                    setupPlacements();
                    mc.player.getAbilities().flying = true;
                    report.add("placements=" + DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().size());
                    beginSize(mc);
                }
                return;
            }
            if (phase == 1) {
                if (--wait > 0) {
                    return;
                }
                recording = false;
                evaluate(SIZES[index][0], SIZES[index][1], "wheel=" + WHEEL_STEPS[stepIndex],
                        WHEEL_STEPS[stepIndex] == 0);
                if (++stepIndex < WHEEL_STEPS.length) {
                    beginStep(mc);
                } else if (++index < SIZES.length) {
                    beginSize(mc);
                } else {
                    tabRound = 0;
                    tabRoundStarted = false;
                    phase = 2;
                }
                return;
            }
            if (phase == 2) {
                if (tabWarmup > 0) {
                    tabWarmup--;
                    return;
                }
                if (!tabRoundStarted) {
                    beginTabRound(mc, TAB_ROUNDS[tabRound]);
                    tabRoundStarted = true;
                    return;
                }
                if (!tabStep()) {
                    return;
                }
                tabRoundStarted = false;
                if (++tabRound < TAB_ROUNDS.length) {
                    return;
                }
                dropdownStep = 0;
                phase = 5;
                return;
            }
            if (phase == 5) {
                if (dropdownProbeStep(mc)) {
                    botProbeIndex = 0;
                    botProbeStep = 0;
                    phase = 6;
                }
                return;
            }
            if (phase == 6) {
                if (botRowProbeStep(mc)) {
                    index = 0;
                    shotIndex = 0;
                    shotPhase = 0;
                    phase = 3;
                }
                return;
            }
            if (phase == 3) {
                doShots(mc);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            finish(mc, "FAIL ui layout: " + t);
        }
    }

    private void setupPlacements() {
        var projection = SchematicWorldHandler.getSchematicWorld();
        projection.getChunkSource().loadChunk(0, 0);
        BlockPos pos = new BlockPos(0, 70, 0);
        projection.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        var area = new AreaSelection();
        area.setName("ui-smoke");
        area.createNewSubRegionBox(pos.offset(-1, -1, -1), "test");
        area.getSelectedSubRegionBox().setPos2(pos.offset(1, 1, 1));
        var schematic = LitematicaSchematic.createFromWorld(projection, area,
                new LitematicaSchematic.SchematicSaveInfo(false, true), "uismoke", s -> {});
        for (int i = 0; i < 20; i++) {
            DataManager.getSchematicPlacementManager().addSchematicPlacement(
                    SchematicPlacement.createFor(schematic, pos.offset(0, 0, i * 8), "proj-" + i, true, true),
                    false);
        }
    }

    private void beginSize(Minecraft mc) {
        stepIndex = 0;
        beginStep(mc);
    }

    /** 重建面板 → 滚到目标位置 → 连续渲染两帧让绘制调用被记录。 */
    private void beginStep(Minecraft mc) {
        int[] s = SIZES[index];
        panel = new SimulaticaMenuScreen();
        mc.gui.setScreen(panel);
        panel.resize(s[0], s[1]);

        int steps = WHEEL_STEPS[stepIndex];
        int times = steps >= 99 ? 80 : steps;
        for (int i = 0; i < times; i++) {
            panel.mouseScrolled(20, 200, 0, -1);    // 左列
            panel.mouseScrolled(1000, 200, 0, -1);  // 右列
        }

        draws.clear();
        widgetDraws.clear();
        recording = true;
        phase = 1;
        wait = 2;
    }

    // ------------------------------------------------------------------
    // TAB 补全探针
    // ------------------------------------------------------------------
    private void beginTabRound(Minecraft mc, String round) {
        tabLog.add("");
        tabLog.add("=== 轮次: " + round + " ===");
        if (!"server-off".equals(round)) {
            try {
                SimulationServer.getOrCreate();
            } catch (Throwable t) {
                tabLog.add("启动模拟服务器失败: " + t);
            }
        }
        if ("sim-running".equals(round)) {
            startFirstSimulation();
            tabWarmup = 60;
        }
        tabLog.add("  Carpet: isLoaded=" + ml.pypals.simulatica.carpet.CarpetIntegration.isLoaded()
                + " isAvailable(反射解析成功)=" + ml.pypals.simulatica.carpet.CarpetIntegration.isAvailable());
        panel = new SimulaticaMenuScreen();
        mc.gui.setScreen(panel);
        panel.resize(854, 480);
        tabCase = 0;
        tabWait = 0;
        tabLog.add("  状态: 服务器=" + (SimulationServer.getRunning() != null ? "on" : "off")
                + " 活动模拟=" + SimulationManager.getInstance().getActiveCount()
                + " 等待=" + SimulationManager.getInstance().getPendingCount());
        probeExecute();
        probeSuggestDirect();
    }

    /** 挑一个放置启动模拟，好让 commandLevel() 不再抛异常。 */
    private void startFirstSimulation() {
        try {
            var manager = DataManager.getSchematicPlacementManager();
            var placements = manager.getAllSchematicsPlacements();
            tabLog.add("  可见放置数 = " + placements.size());
            if (!placements.isEmpty()) {
                var placement = placements.iterator().next();
                SimulationManager.getInstance().startSimulation(placement);
                manager.setSelectedSchematicPlacement(placement);
            }
        } catch (Throwable t) {
            tabLog.add("  启动模拟失败: " + t);
        }
    }

    /** 绕过面板，直接问 SimulationCommands 要补全，看是「拿不到候选」还是「面板没用上候选」。 */
    private void probeSuggestDirect() {
        SimulationServer server = SimulationServer.getRunning();
        if (server == null) {
            tabLog.add("  [直连] 模拟服务器仍未启动");
            return;
        }
        try {
            var dispatcher = server.getCommands().getDispatcher();
            tabLog.add("  [直连] dispatcher 根节点子命令数 = " + dispatcher.getRoot().getChildren().size());
        } catch (Throwable t) {
            tabLog.add("  [直连] 取 dispatcher 失败: " + t);
        }
        for (String input : TAB_CASES) {
            final String label = input;
            try {
                SimulationCommands.suggestModCommands(input, input.length())
                        .thenAccept(s -> tabLog.add(String.format("  [直连] suggestModCommands('%s', %d) → %d 条 %s",
                                label, label.length(), s.getList().size(), firstTexts(s))))
                        .exceptionally(e -> {
                            tabLog.add("  [直连] suggestModCommands('" + label + "') 异常: " + e);
                            return null;
                        });
            } catch (Throwable t) {
                tabLog.add("  [直连] suggestModCommands('" + label + "') 直接抛: " + t);
            }
        }
    }

    private static String firstTexts(Suggestions suggestions) {
        List<String> texts = new ArrayList<>();
        for (Suggestion suggestion : suggestions.getList()) {
            texts.add(suggestion.getText() + suggestion.getRange());
            if (texts.size() >= 8) {
                texts.add("...");
                break;
            }
        }
        return texts.toString();
    }

    /** 完整指令和补全产生的简写都应走客户端执行，不依赖模拟服务器。 */
    private void probeExecute() {
        boolean original = SimulationManager.getInstance().isItemAbsorption();
        try {
            for (String command : List.of("simulatica absorb", "/simulatica absorb", "absorb", "/absorb")) {
                boolean before = SimulationManager.getInstance().isItemAbsorption();
                SimulationCommands.execute(command);
                boolean after = SimulationManager.getInstance().isItemAbsorption();
                failed |= before == after;
                tabLog.add("  execute('" + command + "'): " + before + " → " + after);
            }
            SimulationCommands.execute("absorb on");
            failed |= !SimulationManager.getInstance().isItemAbsorption();
            SimulationCommands.execute("/absorb off");
            failed |= SimulationManager.getInstance().isItemAbsorption();
        } catch (Throwable t) {
            failed = true;
            tabLog.add("  指令执行抛异常: " + t);
        } finally {
            SimulationManager.getInstance().setItemAbsorption(original);
        }
    }

    /** 返回 true 表示本轮跑完。 */
    private boolean tabStep() {
        EditBox field = commandField();
        if (field == null) {
            tabLog.add("找不到指令输入框");
            failed = true;
            return true;
        }
        if (tabCase >= TAB_CASES.length) {
            return true;
        }
        if (tabWait == 0) {
            String value = TAB_CASES[tabCase];
            field.setValue(value);
            field.setCursorPosition(value.length());
            field.setFocused(true);
            tabHandled = panel.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
            tabWait = 1;
            return false;
        }
        if (--tabWait > 0) {
            return false;
        }
        String after = field.getValue();
        int candidates = tabCandidateCount();
        boolean serverUp = SimulationServer.getRunning() != null;
        boolean dead = after.equals(TAB_CASES[tabCase]) && candidates == 0;
        failed |= !tabHandled || dead;
        tabLog.add(String.format("server=%-3s 输入 '%s' → keyPressed 返回=%s 结果 '%s' 候选=%d%s",
                serverUp ? "on" : "off", TAB_CASES[tabCase], tabHandled, after, candidates,
                dead ? "   <<< TAB 完全没有反应" : ""));
        tabCase++;
        return false;
    }

    private EditBox commandField() {
        for (var child : panel.children()) {
            if (child instanceof EditBox box) {
                return box;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 候选列表探针：滚轮是否只滚列表、点击是否落在列表上
    // ------------------------------------------------------------------
    private int dropdownStep;

    /** 返回 true 表示跑完。 */
    private boolean dropdownProbeStep(Minecraft mc) {
        tabLog.add("");
        tabLog.add("=== 候选列表层级探针 (427x240) ===");
        if (dropdownStep == 0) {
            // 427x240：左列可视区 [40,184]，候选列表 x∈[16,166) y∈[82,196)
            panel = new SimulaticaMenuScreen();
            mc.gui.setScreen(panel);
            panel.resize(427, 240);
            EditBox field = commandField();
            field.setValue("");
            field.setCursorPosition(0);
            field.setFocused(true);
            panel.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
            dropdownStep = 1;
            return false;
        }
        if (dropdownStep == 1) {
            List<String> candidates = tabCandidateTexts();
            tabLog.add("  候选=" + candidates
                    + " tabScroll=" + intField("tabScroll")
                    + " leftScroll=" + intField("leftScroll"));

            // 连续 TAB 应该按候选表顺序循环，而不是重新算
            String v0 = commandField().getValue();
            panel.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
            String v1 = commandField().getValue();
            panel.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
            String v2 = commandField().getValue();
            boolean cycleOk = candidates.size() >= 3
                    && v0.equals(candidates.get(0)) && v1.equals(candidates.get(1)) && v2.equals(candidates.get(2));
            failed |= !cycleOk;
            tabLog.add("  连续 TAB 循环: '" + v0 + "' → '" + v1 + "' → '" + v2 + "'"
                    + (cycleOk ? "  ✓ 按候选表顺序循环" : "  <<< 循环不对"));

            // 滚轮落在候选列表里 → 只滚列表，绝不穿透到下面的列
            for (int i = 0; i < 3; i++) {
                panel.mouseScrolled(60, 190, 0, -1);
            }
            int tabAfter = intField("tabScroll");
            int leftAfter = intField("leftScroll");
            boolean needsScroll = candidates.size() > 8;
            boolean scrollOk = leftAfter == 0 && (!needsScroll || tabAfter > 0);
            failed |= !scrollOk;
            tabLog.add("  列表内滚 3 格: tabScroll=" + tabAfter + " leftScroll=" + leftAfter
                    + (scrollOk
                    ? (needsScroll ? "  ✓ 只滚了列表" : "  ✓ 候选不足 8 条无需滚动，也没有穿透")
                    : "  <<< 滚轮穿透了"));

            // 滚轮落在左列、但在候选列表之外 → 应该滚左列
            for (int i = 0; i < 3; i++) {
                panel.mouseScrolled(200, 100, 0, -1);
            }
            int leftAfter2 = intField("leftScroll");
            failed |= leftAfter2 <= 0;
            tabLog.add("  列表外滚 3 格: tabScroll=" + intField("tabScroll") + " leftScroll=" + leftAfter2
                    + (leftAfter2 > 0 ? "  ✓ 左列正常滚动" : "  <<< 左列没滚"));

            // 点击候选列表第 1 行 → 应该取到 tabScroll 位置的那个候选
            String before = commandField().getValue();
            int scrollNow = intField("tabScroll");
            String expected = scrollNow < candidates.size() ? candidates.get(scrollNow) : "?";
            boolean clicked = panel.mouseClicked(
                    new MouseButtonEvent(60, 190, new MouseButtonInfo(0, 0)), false);
            String after = commandField().getValue();
            int remaining = tabCandidateCount();
            failed |= !clicked || remaining != 0 || !after.equals(expected);
            tabLog.add("  点击列表第 1 行: handled=" + clicked + " 值 '" + before + "' → '" + after
                    + "' (期望 '" + expected + "') 剩余候选=" + remaining
                    + (clicked && remaining == 0 && after.equals(expected) ? "  ✓ 点击被列表吃掉并生效"
                    : "  <<< 点击结果不对"));
            dropdownStep = 2;
            return false;
        }
        // 启动按钮滚动到 y=32..52，仅 y=40..52 可见；隐藏部分不得响应点击。
        panel = new SimulaticaMenuScreen();
        mc.gui.setScreen(panel);
        panel.resize(427, 240);
        panel.mouseScrolled(30, 80, 0, -1);
        boolean hiddenClick = panel.mouseClicked(
                new MouseButtonEvent(30, 35, new MouseButtonInfo(0, 0)), false);
        boolean visibleHit = panel.getChildAt(30, 45).isPresent();
        boolean fieldHit = panel.getChildAt(30, 205).orElse(null) == commandField();
        failed |= hiddenClick || !visibleHit || !fieldHit;
        tabLog.add("  裁剪点击: 隐藏部分=" + hiddenClick + " 可见部分=" + visibleHit + " 指令栏=" + fieldHit);
        return true;
    }

    private int intField(String name) {
        try {
            var field = SimulaticaMenuScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(panel);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private int tabCandidateCount() {
        return tabCandidateTexts().size();
    }

    private List<String> tabCandidateTexts() {
        try {
            var field = SimulaticaMenuScreen.class.getDeclaredField("tabCandidates");
            field.setAccessible(true);
            List<String> texts = new ArrayList<>();
            for (Object candidate : (List<?>) field.get(panel)) {
                texts.add(((Suggestion) candidate).getText());
            }
            return texts;
        } catch (Throwable t) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 假人行布局探针（PlacementConfigScreen）
    // ------------------------------------------------------------------
    private int botProbeIndex;
    private int botProbeStep;
    private final List<String> botLog = new ArrayList<>();

    /** 要测的面板逻辑尺寸。第一个就是用户截图那台（1080 物理 / GUI scale 4 = 270 逻辑）。 */
    private static final int[][] BOT_SIZES = {
            {270, 240}, {320, 240}, {240, 180}, {260, 240},
            {427, 240}, {480, 270}, {640, 400}, {854, 480}, {1920, 1080},
    };

    /** 返回 true 表示全部跑完。 */
    private boolean botRowProbeStep(Minecraft mc) {
        if (botProbeStep == 0) {
            botLog.add("");
            botLog.add("=== PlacementConfigScreen 假人行探针 ===");
            botLog.add("  Carpet: isLoaded=" + ml.pypals.simulatica.carpet.CarpetIntegration.isLoaded()
                    + " isAvailable=" + ml.pypals.simulatica.carpet.CarpetIntegration.isAvailable());

            // 用真实假人：先给某个放置启动模拟，再召唤 2 个。名字会是 Bot1 / Bot2。
            botLog.add("  召唤假人: " + spawnProbeBots());
            botLog.add("  假人数=" + probeBotCount());
            botProbeStep = 1;
            return false;
        }
        if (botProbeIndex >= BOT_SIZES.length) {
            if (!botShotDone) {
                // 先把界面挂上，等窗口和缩放都生效后再单独截一帧（Screenshot.grab 是异步的）
                mc.getWindow().setWindowed(1080, 960);
                mc.getWindow().setGuiScale(4);
                var screen = newPlacementConfigScreen();
                if (screen != null) {
                    mc.gui.setScreen(screen);
                } else {
                    failed = true;
                    botLog.add("  截图失败：拿不到放置");
                }
                botShotDone = true;
                botWait = 4;
                return false;
            }
            if (botShotWait > 0) {
                botShotWait--;
                return false;
            }
            if (!botShotGrabbed) {
                shotsBefore = screenshotNames();
                Screenshot.grab(mc, false);
                botShotGrabbed = true;
                botWait = 20;
                return false;
            }
            if (--botWait > 0) {
                return false;
            }
            botLog.add("  配置界面截图: " + newestShot(shotsBefore));
            return true;
        }

        int[] s = BOT_SIZES[botProbeIndex];
        int bots = Math.max(probeBotCount(), 2);
        var screen = newPlacementConfigScreen();
        if (screen == null) {
            botLog.add("  无法构造 PlacementConfigScreen（找不到放置？）");
            failed = true;
            return true;
        }
        mc.gui.setScreen(screen);
        screen.resize(s[0], s[1]);

        List<String> issues = BotRowLayoutProbe.check(screen, bots, s[0], s[1]);
        int firstX = BotRowLayoutProbe.firstColumnX(screen);
        int toolX = firstToolButtonX(screen);
        String detail = String.format("%4dx%-4d 行数=%d 首列 x=%d 工具按钮 x=%d 偏移=%d",
                s[0], s[1], BotRowLayoutProbe.rows(screen, bots).size(), firstX, toolX,
                firstX == Integer.MIN_VALUE ? -999 : firstX - toolX);

        // 正常宽度下，假人行的按钮左边缘不得跑到上面那排工具按钮的左边——那说明
        // 名字的宽度把按钮往左挤了。逻辑宽 <= 240 时（四列按钮 46+46+70+46 加三个
        // 4px 间距 = 224，再加左右各 16 边距正好 256）连边距都要压缩才能放下，
        // 首列 x 小于工具按钮是预期降级，不算错。
        if (s[0] > 240 && firstX != Integer.MIN_VALUE && toolX != Integer.MIN_VALUE
                && firstX < toolX) {
            issues.add(String.format("假人按钮首列 x=%d 跑到工具按钮 x=%d 的左边", firstX, toolX));
        }

        failed |= !issues.isEmpty();
        String line = (issues.isEmpty() ? "PASS" : "FAIL") + " " + detail
                + (issues.isEmpty() ? "  ✓ 纵向成列" : "  <<< " + String.join(" || ", issues));
        botLog.add("  " + line);
        System.out.println("SIMULATICA_BOTROW " + line);

        // 名字列宽度只取最长名字，所以窗口一旦宽到能完整放下名字列，首列 x 就固定。
        // 以第一个「明显宽裕」的档（>=320）作为基准，后面所有宽裕档都必须一样；
        // 比基准小只可能是窄窗口压缩名字列，那属于预期降级，不算错。
        if (firstX != Integer.MIN_VALUE && s[0] >= 320) {
            if (!wideFirstXSet) {
                wideFirstX = firstX;
                wideFirstXSet = true;
            } else if (firstX != wideFirstX) {
                failed = true;
                String drift = String.format("宽裕窗口下首列 x 漂移：本档 %d，基准 %d",
                        firstX, wideFirstX);
                botLog.add("  FAIL " + drift);
                System.out.println("SIMULATICA_BOTROW FAIL " + drift);
            }
        }

        botProbeIndex++;
        return false;
    }

    private int wideFirstX = Integer.MIN_VALUE;
    private boolean wideFirstXSet;
    private boolean botShotDone;
    private boolean botShotGrabbed;
    private int botShotWait = 2;
    private int botWait;

    private int spawnProbeBots() {
        try {
            var placements = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
            if (placements.isEmpty()) {
                return -1;
            }
            var placement = placements.iterator().next();
            SimulationManager.getInstance().startSimulation(placement);
            int made = 0;
            for (int i = 0; i < 2; i++) {
                String name = "Bot" + (i + 1);
                if (ml.pypals.simulatica.carpet.BotManager.spawn(
                        placement, name, GameType.CREATIVE) != null) {
                    made++;
                }
            }
            botProbePlacement = placement;
            return made;
        } catch (Throwable t) {
            botLog.add("    召唤异常: " + t);
            return -1;
        }
    }

    private int probeBotCount() {
        if (this.botProbePlacement == null) {
            return 0;
        }
        try {
            return ml.pypals.simulatica.carpet.BotManager.botsOf(this.botProbePlacement).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private fi.dy.masa.litematica.schematic.placement.SchematicPlacement botProbePlacement;

    private ml.pypals.simulatica.PlacementConfigScreen newPlacementConfigScreen() {
        try {
            var placements = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
            if (placements.isEmpty()) {
                return null;
            }
            return new ml.pypals.simulatica.PlacementConfigScreen(placements.iterator().next());
        } catch (Throwable t) {
            botLog.add("    构造面板异常: " + t);
            return null;
        }
    }

    /** 抓上面那排工具按钮（召唤假人）的左边缘，用来对比假人行的偏移。 */
    private static int firstToolButtonX(net.minecraft.client.gui.screens.Screen screen) {
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w
                    && "召唤假人".equals(w.getMessage().getString())) {
                return w.getX();
            }
        }
        return Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------
    private void evaluate(int w, int h, String tag, boolean atTop) {
        List<String> issues = new ArrayList<>();

        List<int[]> rects = new ArrayList<>();
        List<String> names = new ArrayList<>();
        LinkedHashSet<String> seenWidgets = new LinkedHashSet<>();
        for (Draw d : widgetDraws) {
            if (d.w() <= 0 || d.h() <= 0 || !seenWidgets.add(key(d))) {
                continue;
            }
            int[] r = effective(d, w, h, issues, "WIDGET");
            if (r != null) {
                rects.add(r);
                names.add(d.text());
            }
        }

        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                if (overlap(rects.get(i), rects.get(j))) {
                    issues.add(String.format("WIDGET_CLASH %s ∩ %s", names.get(i), names.get(j)));
                }
            }
        }

        List<int[]> textRects = new ArrayList<>();
        List<String> textLabels = new ArrayList<>();
        LinkedHashSet<String> seenTexts = new LinkedHashSet<>();
        for (Draw d : draws) {
            if (d.w() <= 0 || d.h() <= 0 || !seenTexts.add(key(d))) {
                continue;
            }
            if (d.scissored()) {
                if (d.x() < d.sx1() || d.x() + d.w() > d.sx2()) {
                    issues.add(String.format("TEXT_CLIPPED_H 「%s」rect=(%d,%d,%d,%d) scissor=(%d,%d,%d,%d)",
                            d.text(), d.x(), d.y(), d.w(), d.h(), d.sx1(), d.sy1(), d.sx2(), d.sy2()));
                }
                if (atTop && d.y() + d.h() <= d.sy1()) {
                    issues.add(String.format("TEXT_ABOVE_VIEWPORT 「%s」y=%d scissorTop=%d",
                            d.text(), d.y(), d.sy1()));
                }
            }
            int[] r = effective(d, w, h, issues, "TEXT");
            if (r != null) {
                textRects.add(r);
                textLabels.add(d.text());
            }
        }

        for (int i = 0; i < textRects.size(); i++) {
            int[] t = textRects.get(i);
            for (int j = 0; j < rects.size(); j++) {
                // 控件自己的标签画在控件内部属正常，只有「压到别的控件」才算问题
                if (overlap(t, rects.get(j)) && !contains(rects.get(j), t)) {
                    issues.add(String.format("TEXT_CLASH 「%s」∩ %s rect=(%d,%d,%d,%d)",
                            textLabels.get(i), names.get(j),
                            rects.get(j)[0], rects.get(j)[1], rects.get(j)[2], rects.get(j)[3]));
                }
            }
        }
        for (int i = 0; i < textRects.size(); i++) {
            for (int j = i + 1; j < textRects.size(); j++) {
                if (overlap(textRects.get(i), textRects.get(j))) {
                    issues.add(String.format("TEXT_CLASH 「%s」∩ 「%s」",
                            textLabels.get(i), textLabels.get(j)));
                }
            }
        }

        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(issues));
        String line = String.format("%s %4dx%-4d %-12s %s", distinct.isEmpty() ? "PASS" : "FAIL",
                w, h, tag, distinct.isEmpty() ? "无越界/无重叠/无裁剪" : String.join(" || ", distinct));
        report.add(line);
        System.out.println("SIMULATICA_UI " + line);
        if (!distinct.isEmpty()) {
            failed = true;
        }
    }

    private static String key(Draw d) {
        return d.text() + "@" + d.x() + "," + d.y();
    }

    /**
     * 一个绘制调用的实际占位：有 scissor 就取交集（完全在区外返回 null，即根本没画出来），
     * 没有 scissor 就检查是否越出屏幕。
     */
    private static int[] effective(Draw d, int w, int h, List<String> issues, String kind) {
        int tx = d.x();
        int ty = d.y();
        int tw = d.w();
        int th = d.h();
        if (d.scissored()) {
            int x1 = Math.max(tx, d.sx1());
            int y1 = Math.max(ty, d.sy1());
            int x2 = Math.min(tx + tw, d.sx2());
            int y2 = Math.min(ty + th, d.sy2());
            return (x2 <= x1 || y2 <= y1) ? null : new int[]{x1, y1, x2 - x1, y2 - y1};
        }
        if (tx < 0 || ty < 0 || tx + tw > w || ty + th > h) {
            issues.add(String.format("%s_OFFSCREEN 「%s」rect=(%d,%d,%d,%d) 屏幕 %dx%d",
                    kind, d.text(), tx, ty, tw, th, w, h));
        }
        return new int[]{tx, ty, tw, th};
    }

    private static boolean overlap(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private static boolean contains(int[] outer, int[] inner) {
        return inner[0] >= outer[0] && inner[1] >= outer[1]
                && inner[0] + inner[2] <= outer[0] + outer[2]
                && inner[1] + inner[3] <= outer[1] + outer[3];
    }

    // ------------------------------------------------------------------
    // 截图
    // ------------------------------------------------------------------
    private void doShots(Minecraft mc) {
        if (wait > 0) {
            wait--;
            return;
        }
        if (shotIndex >= SHOTS.length) {
            finish(mc, null);
            return;
        }
        int[] s = SHOTS[shotIndex];
        switch (shotPhase) {
            case 0 -> {
                mc.getWindow().setWindowed(s[0], s[1]);
                shotPhase = 1;
                wait = 3;
            }
            case 1 -> {
                mc.getWindow().setGuiScale(1);
                panel = new SimulaticaMenuScreen();
                mc.gui.setScreen(panel);
                panel.resize(s[0], s[1]);
                // 滚到中段，截图里能直接看到「半露按钮被正确裁掉」的效果
                for (int i = 0; i < 3; i++) {
                    panel.mouseScrolled(20, 200, 0, -1);
                    panel.mouseScrolled(1000, 200, 0, -1);
                }
                shotPhase = 2;
                wait = 6;
            }
            case 2 -> {
                shotsBefore = screenshotNames();
                Screenshot.grab(mc, false);
                shotPhase = 3;
                wait = 14;
            }
            default -> {
                String created = newestShot(shotsBefore);
                report.add("SHOT " + s[0] + "x" + s[1] + " -> " + (created == null ? "未生成截图" : created));
                if (created == null) {
                    failed = true;
                }
                shotIndex++;
                shotPhase = 0;
            }
        }
    }

    private static File shotDir() {
        // runClient 的工作目录就是 run/，MC 的截图也落在这里
        return new File("screenshots");
    }

    private static Set<String> screenshotNames() {
        Set<String> names = new LinkedHashSet<>();
        File[] files = shotDir().listFiles();
        if (files != null) {
            for (File f : files) {
                names.add(f.getName());
            }
        }
        return names;
    }

    private static String newestShot(Set<String> before) {
        File[] files = shotDir().listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File f : files) {
            if (before.contains(f.getName())) {
                continue;
            }
            if (newest == null || f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest == null ? null : newest.getName();
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------
    private void finish(Minecraft mc, String failure) {
        List<String> lines = new ArrayList<>(report);
        boolean ok = !failed && failure == null;
        lines.add(0, (ok ? "PASS" : "FAIL") + " simulatica control panel layout"
                + (failure == null ? "" : " | " + failure));
        lines.add("");
        lines.add("--- TAB 补全探针 ---");
        lines.addAll(tabLog);
        lines.add("");
        lines.add("--- 假人行布局探针 ---");
        lines.addAll(botLog);
        try {
            Files.write(Path.of("ui-layout-result.txt"), lines);
        } catch (Exception e) {
            e.printStackTrace();
        }
        mc.stop();
    }
}
