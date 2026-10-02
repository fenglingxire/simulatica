package ml.pypals.simulatica;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.carpet.BotManager;
import ml.pypals.simulatica.carpet.CarpetIntegration;
import ml.pypals.simulatica.counter.HopperCounter;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.SimulationCommands;
import ml.pypals.simulatica.simulation.server.SimulationSelfTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * [SIMULATICA-修改] 游戏内控制面板（裸输 /simulatica 打开）。
 *
 * <p>左右分类：左列「全局操作」+「世界调整」（折叠）+「假人全局」（Carpet 加载时显示，含批量清理/传送/
 * 停止动作）+ 底部指令输入栏，整列支持滚轮滚动与滑块拖动；右列「单个投影」启停列表（滚轮翻页 + 滑块）。</p>
 *
 * <h2>布局不变量（改这个文件时请保持）</h2>
 * <ol>
 *   <li>左右两列宽度按屏幕宽度自适应：左列取 {@link #LEFT_WIDTH}（窄屏时收缩到
 *       {@link #MIN_LEFT_WIDTH}），剩下的宽度归右列，右列至少留 {@link #MIN_LIST_WIDTH}
 *       以放得下「配置」+「启动/停止」两个按钮。任何宽度下两列内容都不相交。</li>
 *   <li>滚动条画在列的**外侧**（左列滑块在按钮右边缘之外、右列滑块在按钮右边缘之外），
 *       不会压住按钮。</li>
 *   <li>每个分区标题独占一行（{@link #HEADER_H}），不再与上一个分区最后一个按钮重叠；
 *       且第一行标题落在左列裁剪区上边界 {@link #LEFT_TOP} 之内，因此不会被裁掉。</li>
 *   <li>顶部状态行（y=28，居中）与右列标题（y={@link #LEFT_TOP}）不在同一行，互不遮挡。</li>
 *   <li>左列可滚动区底部预留 {@link #LEFT_LABEL_H}，保证底部固定文字「模拟世界指令」
 *       不会压住最后一个可见按钮；该文字与「发送」按钮同样不重叠。</li>
 *   <li>左列按钮由 {@link #extractRenderState} 手动包在
 *       {@code [0, LEFT_TOP] ~ [listX, leftBottom]} 的裁剪区里绘制。因此按钮只露出一部分时
 *       可以照画（滚动平滑），露出去的部分由裁剪区切掉，不会压到上方分区标题或底部指令栏。
 *       <b>改这里时必须同时保留裁剪，否则半露按钮会溢出到可视区之外。</b></li>
 * </ol>
 */
public final class SimulaticaMenuScreen extends Screen {

    private static final int PADDING = 16;
    private static final int ROW_HEIGHT = 22;
    private static final int LEFT_WIDTH = 200;
    private static final int MIN_LEFT_WIDTH = 96;
    private static final int MIN_LIST_WIDTH = 148;
    private static final int COLUMN_GAP = 24;
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int SCROLLBAR_GUTTER = SCROLLBAR_WIDTH + 4;
    private static final int LEFT_TOP = 40;
    private static final int BOTTOM_MARGIN = 44;
    private static final int LEFT_LABEL_H = 12;
    private static final int HEADER_H = 14;
    private static final int SECTION_GAP = 8;
    private static final int BTN_H = 20;
    private static final int CFG_BTN_W = 48;
    private static final int ROW_BTN_W = 56;
    private static final int CMD_BTN_W = 46;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HEADER = 0xFF55FFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int COLOR_TRACK = 0x33000000;
    private static final int COLOR_THUMB = 0xCC888888;

    private final List<SchematicPlacement> placements = new ArrayList<>();
    private final List<Button> placementButtons = new ArrayList<>();
    private final List<Button> configButtons = new ArrayList<>();

    // 左列滚动（按像素）
    private final List<Button> leftWidgets = new ArrayList<>();
    private final List<Integer> leftBaseY = new ArrayList<>();
    private final List<Header> leftHeaders = new ArrayList<>();
    private int leftNextY;
    private int leftContentHeight;
    private int leftScroll;
    private boolean worldExpanded = false;

    // 右列滚动（按行）
    private int scrollOffset;
    private int listX;
    private int listWidth;

    // 自适应尺寸（init 时按屏幕宽度算好）
    private int leftWidth = LEFT_WIDTH;
    private int leftBottom = LEFT_TOP;

    // 滑块拖拽状态
    private boolean draggingLeft = false;
    private boolean draggingRight = false;

    // 指令栏 TAB 补齐状态
    private EditBox commandField;
    private List<Suggestion> tabCandidates = List.of();
    private int tabCursor = 0;
    private int tabScroll = 0;
    /** 候选表是对这一段文本（光标前的内容）算出来的，替换必须回到这段文本上做。 */
    private String tabBase = null;
    /** 算候选时光标后面的内容，补全后要原样接回去。 */
    private String tabSuffix = "";
    /** 上一次补全后光标前的内容，用来识别「连续按 TAB 应该循环候选」。 */
    private String tabApplied = null;

    private record Header(String text, int baseY) {}

    public SimulaticaMenuScreen() {
        super(Component.literal("Simulatica 控制面板"));
    }

    @Override
    protected void init() {
        this.placements.clear();
        this.placements.addAll(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
        this.placementButtons.clear();
        this.configButtons.clear();
        this.leftWidgets.clear();
        this.leftBaseY.clear();
        this.leftHeaders.clear();
        this.leftNextY = LEFT_TOP - SECTION_GAP;
        this.scrollOffset = Math.min(this.scrollOffset, maxScroll());

        layoutColumns();
        buildLeftColumn();
        this.leftScroll = Math.min(this.leftScroll, maxLeftScroll());
        buildCommandField();
        buildPlacementList();
        layoutLeft();
        layoutRows();
    }

    /**
     * 按屏幕宽度切分左右两列。窄屏优先保右列（右列放不下两个按钮就没法用了），
     * 左列收缩到 {@link #MIN_LEFT_WIDTH} 为止。
     */
    private void layoutColumns() {
        int avail = Math.max(0, this.width - PADDING * 2 - COLUMN_GAP - SCROLLBAR_GUTTER);
        int left = Math.min(LEFT_WIDTH, Math.max(MIN_LEFT_WIDTH, avail - MIN_LIST_WIDTH));
        this.leftWidth = Math.min(left, Math.max(MIN_LEFT_WIDTH, avail));
        this.listX = PADDING + this.leftWidth + COLUMN_GAP;
        this.listWidth = Math.max(0, this.width - this.listX - PADDING - SCROLLBAR_GUTTER);
        this.leftBottom = this.height - BOTTOM_MARGIN - LEFT_LABEL_H;
    }

    // ------------------------------------------------------------------
    // 左列构建（全局操作 + 世界调整 + 假人全局）
    // ------------------------------------------------------------------
    private void buildLeftColumn() {
        addSection("全局操作");
        addLeftButton("启动全部模拟", this.leftNextY, this.leftWidth, b -> startAllPlacements());
        this.leftNextY += ROW_HEIGHT;
        addLeftButton("停止全部模拟", this.leftNextY, this.leftWidth, b -> {
            SimulationManager.getInstance().stopAll();
            refreshPlacementButtons();
        });
        this.leftNextY += ROW_HEIGHT;
        addLeftButton(absorbLabel(), this.leftNextY, this.leftWidth, b -> {
            SimulationManager.getInstance().setItemAbsorption(null);
            b.setMessage(absorbLabel());
        });
        this.leftNextY += ROW_HEIGHT;
        addLeftButton("清除越界实体", this.leftNextY, this.leftWidth, b -> {
            int removed = SimulationManager.getInstance().purgeEscapedEntities();
            SimulaticaClient.sendFeedback(removed == 0
                    ? "No escaped entities found."
                    : "Purged " + removed + " escaped entities.");
        });
        this.leftNextY += ROW_HEIGHT;
        addLeftButton("运行自检诊断", this.leftNextY, this.leftWidth,
                b -> SimulationSelfTest.run().forEach(SimulaticaClient::sendFeedback));
        this.leftNextY += ROW_HEIGHT;

        // 漏斗计数器
        addSection("漏斗计数器");
        addLeftButton("查看计数", this.leftNextY, this.leftWidth, b -> {
            for (Component line : HopperCounter.formatAll()) {
                SimulaticaClient.sendFeedback(line);
            }
        });
        this.leftNextY += ROW_HEIGHT;
        addLeftButton("重置计数", this.leftNextY, this.leftWidth, b -> {
            HopperCounter.resetAll();
            SimulaticaClient.sendFeedback("已重置所有漏斗计数器。");
        });
        this.leftNextY += ROW_HEIGHT;

        // 世界调整（折叠）
        addSection("世界调整");
        addLeftButton(this.worldExpanded ? "收起世界调整" : "展开世界调整", this.leftNextY, this.leftWidth, b -> {
            this.worldExpanded = !this.worldExpanded;
            rebuild();
        });
        this.leftNextY += ROW_HEIGHT;
        if (this.worldExpanded) {
            addWorldRow("时间:白天", "/time set day", "时间:夜晚", "/time set night", "时间:正午", "/time set noon");
            addWorldRow("时间:午夜", "/time set midnight", "难度:和平", "/difficulty peaceful", "难度:简单", "/difficulty easy");
            addWorldRow("难度:普通", "/difficulty normal", "难度:困难", "/difficulty hard", "天气:晴", "/weather clear");
            addWorldRow("天气:雨", "/weather rain", "天气:雷", "/weather thunder", null, null);
        }

        // 假人全局（仅 Carpet 加载时显示）
        if (CarpetIntegration.isLoaded()) {
            addSection("假人全局");
            addLeftButton("清理全部假人", this.leftNextY, this.leftWidth, b -> {
                int n = BotManager.removeAllGlobally();
                SimulaticaClient.sendFeedback("已清理 " + n + " 个假人。");
                rebuild();
            });
            this.leftNextY += ROW_HEIGHT;
            addLeftButton("传送全部假人", this.leftNextY, this.leftWidth, b -> {
                int n = BotManager.teleportAllToPlayer();
                SimulaticaClient.sendFeedback("已把 " + n + " 个假人传送到玩家位置。");
            });
            this.leftNextY += ROW_HEIGHT;
            addLeftButton("停止全部动作", this.leftNextY, this.leftWidth, b -> BotManager.stopAllActionsGlobally());
            this.leftNextY += ROW_HEIGHT;
        }

        this.leftContentHeight = this.leftNextY - LEFT_TOP;
    }

    /** 分区标题独占一行：先留出与上一段的间距，再占 {@link #HEADER_H} 的高度。 */
    private void addSection(String title) {
        this.leftNextY += SECTION_GAP;
        this.leftHeaders.add(new Header(title, this.leftNextY));
        this.leftNextY += HEADER_H;
    }

    private Button addLeftButton(String label, int y, int width, Button.OnPress onPress) {
        return addLeftButton(Component.literal(label), y, width, onPress);
    }

    private Button addLeftButton(Component label, int y, int width, Button.OnPress onPress) {
        return addLeftButtonAt(label, PADDING, y, width, onPress);
    }

    private Button addLeftButtonAt(Component label, int x, int y, int width, Button.OnPress onPress) {
        Button b = Button.builder(label, onPress).bounds(x, y, width, BTN_H).build();
        this.leftWidgets.add(b);
        this.leftBaseY.add(y);
        addRenderableWidget(b);
        return b;
    }

    /** 世界调整一行三格：按左列实际宽度等分，窄屏时自动变窄而不是溢出。 */
    private void addWorldRow(String l1, String c1, String l2, String c2, String l3, String c3) {
        int y = this.leftNextY;
        int w = Math.max(24, (this.leftWidth - 4) / 3);
        if (l1 != null) {
            addLeftButtonAt(Component.literal(l1), PADDING, y, w, b -> SimulationCommands.execute(c1));
        }
        if (l2 != null) {
            addLeftButtonAt(Component.literal(l2), PADDING + w + 2, y, w, b -> SimulationCommands.execute(c2));
        }
        if (l3 != null) {
            addLeftButtonAt(Component.literal(l3), PADDING + 2 * (w + 2), y, w, b -> SimulationCommands.execute(c3));
        }
        this.leftNextY += ROW_HEIGHT;
    }

    // ------------------------------------------------------------------
    // 指令输入栏（固定在底部）
    // ------------------------------------------------------------------
    private void buildCommandField() {
        int x = PADDING;
        int fieldY = this.height - 40;

        this.commandField = new EditBox(this.font, x, fieldY, Math.max(20, this.leftWidth - CMD_BTN_W - 4), 18,
                Component.literal(""));
        this.commandField.setHint(Component.literal("/指令"));
        this.commandField.setMaxLength(256);
        clearTabState();
        addRenderableWidget(this.commandField);

        addRenderableWidget(Button.builder(Component.literal("发送"),
                        button -> {
                            String command = this.commandField.getValue().trim();
                            if (!command.isEmpty()) {
                                SimulationCommands.execute(command);
                                this.commandField.setValue("");
                            }
                        }).bounds(x + this.leftWidth - CMD_BTN_W, fieldY - 1, CMD_BTN_W, BTN_H).build());
    }

    // ------------------------------------------------------------------
    // 右列：单个投影列表
    // ------------------------------------------------------------------
    private void buildPlacementList() {
        // 两个按钮从右往左排，保证任何宽度下都右对齐且互不重叠
        int rowX = Math.max(this.listX, this.listX + this.listWidth - ROW_BTN_W);
        int cfgX = Math.max(this.listX, rowX - 4 - CFG_BTN_W);

        for (SchematicPlacement placement : this.placements) {
            Button config = Button.builder(Component.literal("配置"),
                            button -> Minecraft.getInstance().gui.setScreen(new PlacementConfigScreen(placement)))
                    .bounds(cfgX, 0, CFG_BTN_W, BTN_H).build();
            this.configButtons.add(addRenderableWidget(config));

            Button row = Button.builder(toggleLabel(placement), button -> {
                SimulationManager manager = SimulationManager.getInstance();
                if (manager.isSimulating(placement)) {
                    manager.stopSimulation(placement);
                } else {
                    manager.startSimulation(placement);
                }
                button.setMessage(toggleLabel(placement));
            }).bounds(rowX, 0, ROW_BTN_W, BTN_H).build();
            this.placementButtons.add(addRenderableWidget(row));
        }
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private void startAllPlacements() {
        for (SchematicPlacement placement : this.placements) {
            SimulationManager.getInstance().startSimulation(placement);
        }
        refreshPlacementButtons();
    }

    private void refreshPlacementButtons() {
        for (int i = 0; i < this.placementButtons.size(); i++) {
            this.placementButtons.get(i).setMessage(toggleLabel(this.placements.get(i)));
        }
    }

    private static Component absorbLabel() {
        return Component.literal("掉落物吸收：" + (SimulationManager.getInstance().isItemAbsorption() ? "开" : "关"));
    }

    private static Component toggleLabel(SchematicPlacement placement) {
        return Component.literal(SimulationManager.getInstance().isSimulating(placement) ? "停止" : "启动");
    }

    // ------------------------------------------------------------------
    // 滚动布局
    // ------------------------------------------------------------------
    private static final int LIST_TOP = LEFT_TOP + HEADER_H;
    private static final int LIST_BOTTOM_MARGIN = 40;

    private int leftViewportHeight() {
        return Math.max(1, this.leftBottom - LEFT_TOP);
    }

    private int maxLeftScroll() {
        return Math.max(0, this.leftContentHeight - leftViewportHeight());
    }

    private void layoutLeft() {
        int top = LEFT_TOP;
        int bottom = this.leftBottom;
        for (int i = 0; i < this.leftWidgets.size(); i++) {
            Button w = this.leftWidgets.get(i);
            int y = this.leftBaseY.get(i) - this.leftScroll;
            w.setY(y);
            // 只要有一点点露在可视区内就照画（绘制时会按同一个裁剪区裁掉露出去的部分），
            // 这样滚动是平滑滑入滑出；整块跳变会很难看。
            boolean shown = y + BTN_H > top && y < bottom;
            w.visible = shown;
            w.active = shown;
        }
    }

    private int visibleRows() {
        return Math.max(1, (this.height - LIST_BOTTOM_MARGIN - LIST_TOP) / ROW_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, this.placements.size() - visibleRows());
    }

    private void layoutRows() {
        int visible = visibleRows();
        for (int i = 0; i < this.placementButtons.size(); i++) {
            Button row = this.placementButtons.get(i);
            int slot = i - this.scrollOffset;
            boolean shown = slot >= 0 && slot < visible;
            row.visible = shown;
            row.active = shown;
            if (shown) {
                row.setY(LIST_TOP + slot * ROW_HEIGHT + 1);
            }
            this.configButtons.get(i).visible = shown;
            this.configButtons.get(i).active = shown;
            if (shown) {
                this.configButtons.get(i).setY(LIST_TOP + slot * ROW_HEIGHT + 1);
            }
        }
    }

    // ------------------------------------------------------------------
    // 鼠标交互（滚轮 + 滑块拖拽）
    // ------------------------------------------------------------------
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 候选列表在最上层：滚轮落在它身上时只滚它，不再穿透到下面的列
        if (insideSuggest(mouseX, mouseY)) {
            int max = suggestMaxScroll();
            if (max > 0) {
                this.tabScroll = Math.max(0, Math.min(max,
                        this.tabScroll - (int) Math.signum(scrollY)));
            }
            return true;
        }

        double wheel = Math.signum(scrollY);
        // 右列（投影列表）
        if (mouseX >= this.listX && maxScroll() > 0) {
            this.scrollOffset = Math.max(0, Math.min(maxScroll(), this.scrollOffset - (int) wheel));
            layoutRows();
            return true;
        }
        // 左列
        if (mouseX < this.listX && maxLeftScroll() > 0) {
            this.leftScroll = Math.max(0, Math.min(maxLeftScroll(), this.leftScroll - (int) wheel * ROW_HEIGHT));
            layoutLeft();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        for (GuiEventListener child : this.children()) {
            if (this.leftWidgets.contains(child)
                    && (mouseX < 0 || mouseX >= this.listX || mouseY < LEFT_TOP || mouseY >= this.leftBottom)) {
                continue;
            }
            if (child.isMouseOver(mouseX, mouseY)) {
                return Optional.of(child);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean hasActiveButton) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            if (clickSuggestion(mx, my)) {
                return true;
            }
            if (hitLeftScrollbar(mx, my)) {
                this.draggingLeft = true;
                dragLeft(my);
                return true;
            }
            if (hitRightScrollbar(mx, my)) {
                this.draggingRight = true;
                dragRight(my);
                return true;
            }
        }
        return super.mouseClicked(event, hasActiveButton);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.draggingLeft) {
            dragLeft((int) event.y());
            return true;
        }
        if (this.draggingRight) {
            dragRight((int) event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.draggingLeft = false;
        this.draggingRight = false;
        return super.mouseReleased(event);
    }

    // ------------------------------------------------------------------
    // 指令栏 TAB 补齐（像原版：命令名/参数补全、多候选循环、唯一候选直接补全）
    // ------------------------------------------------------------------
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_TAB
                && this.commandField != null
                && this.commandField.isFocused()) {
            onTabComplete();
            return true;
        }
        // 非 TAB 按键（输入/删除/移动光标）→ 补全候选失效
        clearTabState();
        return super.keyPressed(event);
    }

    private void onTabComplete() {
        String value = this.commandField.getValue();
        int cursor = this.commandField.getCursorPosition();
        String prefix = value.substring(0, cursor);

        // 连续 TAB：输入框里还是上一次补全出来的结果 → 在同一张候选表里循环
        if (!this.tabCandidates.isEmpty() && prefix.equals(this.tabApplied)) {
            this.tabCursor = (this.tabCursor + 1) % this.tabCandidates.size();
            ensureTabCursorVisible();
            applySuggestion(this.tabCandidates.get(this.tabCursor));
            return;
        }

        this.tabBase = prefix;
        this.tabSuffix = value.substring(cursor);
        this.tabCursor = 0;
        this.tabScroll = 0;
        this.tabApplied = null;
        // 面板指令框只补全本 mod 自己的指令（/simulatica ...）。直接输子指令也行，
        // 例如 `st` → start/stop/status。
        SimulationCommands.suggestModCommands(prefix, prefix.length()).thenAccept(suggestions ->
                Minecraft.getInstance().execute(() -> {
                    // 等待期间用户又输入了 → 这次结果作废
                    String now = this.commandField.getValue();
                    int caret = Math.min(this.commandField.getCursorPosition(), now.length());
                    if (!now.substring(0, caret).equals(prefix)) {
                        return;
                    }
                    this.tabCandidates = new ArrayList<>(suggestions.getList());
                    this.tabScroll = 0;
                    if (this.tabCandidates.isEmpty()) {
                        return;
                    }
                    this.tabCursor = 0;
                    // 补全第一个候选（保留候选列表供鼠标点击/滚轮选取）
                    applySuggestion(this.tabCandidates.get(0));
                }));
    }

    /**
     * 按候选自带的 StringRange 替换内容（与 Brigadier 的 Suggestion.apply 一致）。
     *
     * <p>关键：范围是相对 {@link #tabBase}（算候选时那段文本）的，不是相对输入框当前内容。
     * 补全过一次之后输入框内容已经变了，若拿当前内容去套旧范围就会拼出
     * {@code clearadvancement} 这种鬼东西，连续 TAB 循环也会失效。</p>
     */
    private void applySuggestion(Suggestion suggestion) {
        String base = this.tabBase == null ? this.commandField.getValue() : this.tabBase;
        StringRange range = suggestion.getRange();
        int start = Math.max(0, Math.min(range.getStart(), base.length()));
        int end = Math.max(start, Math.min(range.getEnd(), base.length()));
        String head = base.substring(0, start) + suggestion.getText() + base.substring(end);
        this.commandField.setValue(head + this.tabSuffix);
        int caret = head.length();
        this.commandField.setCursorPosition(caret);
        this.commandField.setHighlightPos(caret);
        this.tabApplied = head;
    }

    // ------------------------------------------------------------------
    // 指令补全候选列表（像原版聊天栏：TAB 弹出，鼠标点击选取）
    // ------------------------------------------------------------------
    private static final int SUGGEST_ROW = 14;
    private static final int SUGGEST_MAX = 8;

    /** 候选列表的底边 Y（指令栏上方 4px）。 */
    private int suggestionBottom() {
        return this.height - 44;
    }

    /** 候选列表当前是否显示。 */
    private boolean suggestVisible() {
        return !this.tabCandidates.isEmpty() && this.commandField != null && this.commandField.isFocused();
    }

    /** 候选列表画几行（最多 {@link #SUGGEST_MAX} 行，行数固定，滚轮滚动时高度不跳）。 */
    private int suggestRows() {
        return Math.min(this.tabCandidates.size(), SUGGEST_MAX);
    }

    private int suggestMaxScroll() {
        return Math.max(0, this.tabCandidates.size() - SUGGEST_MAX);
    }

    private int suggestTop() {
        return suggestionBottom() - suggestRows() * SUGGEST_ROW - 2;
    }

    private int suggestWidth() {
        return Math.max(40, this.leftWidth - 50);
    }

    private boolean insideSuggest(double mx, double my) {
        if (!suggestVisible()) {
            return false;
        }
        return mx >= PADDING && mx < PADDING + suggestWidth()
                && my >= suggestTop() && my < suggestionBottom();
    }

    /** 鼠标落在候选列表的第几行（可见窗口内的相对行号），不在列表上返回 -1。 */
    private int suggestRowAt(double mx, double my) {
        if (!insideSuggest(mx, my)) {
            return -1;
        }
        int row = (int) ((suggestionBottom() - my - 1) / SUGGEST_ROW);
        return (row >= 0 && row < suggestRows()) ? row : -1;
    }

    /** 候选列表是否盖住了某个矩形——盖住的控件这一帧按「鼠标不在上面」渲染，避免底下跟着高亮。 */
    private boolean suggestCovers(int x, int y, int w, int h) {
        if (!suggestVisible()) {
            return false;
        }
        int sx = PADDING;
        int sw = suggestWidth();
        int top = suggestTop();
        int bottom = suggestionBottom();
        return x < sx + sw && sx < x + w && y < bottom && top < y + h;
    }

    /** TAB 循环时让当前候选保持在可见窗口里。 */
    private void ensureTabCursorVisible() {
        if (this.tabCursor < this.tabScroll) {
            this.tabScroll = this.tabCursor;
        } else if (this.tabCursor >= this.tabScroll + SUGGEST_MAX) {
            this.tabScroll = this.tabCursor - SUGGEST_MAX + 1;
        }
        this.tabScroll = Math.max(0, Math.min(this.tabScroll, suggestMaxScroll()));
    }

    private void drawSuggestionList(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        int x = PADDING;
        int width = suggestWidth();
        int rows = suggestRows();
        int bottom = suggestionBottom();
        int top = suggestTop();
        extractor.fill(x, top, x + width, bottom, 0xE0101010);

        int hovered = suggestRowAt(mouseX, mouseY);
        for (int row = 0; row < rows; row++) {
            int index = this.tabScroll + row;
            if (index >= this.tabCandidates.size()) {
                break;
            }
            int y = bottom - (row + 1) * SUGGEST_ROW;
            boolean selected = row == hovered || index == this.tabCursor;
            if (selected) {
                extractor.fill(x, y, x + width, y + SUGGEST_ROW, 0x60484848);
            }
            extractor.text(this.font, this.tabCandidates.get(index).getText(), x + 4, y + 3,
                    selected ? COLOR_TEXT : COLOR_DIM);
        }

        int max = suggestMaxScroll();
        if (max > 0) {
            int track = bottom - top;
            int thumb = Math.max(12, track * SUGGEST_MAX / this.tabCandidates.size());
            int thumbTop = top + (track - thumb) * this.tabScroll / max;
            extractor.fill(x + width - 3, top, x + width, bottom, COLOR_TRACK);
            extractor.fill(x + width - 3, thumbTop, x + width, thumbTop + thumb, COLOR_THUMB);
        }
    }

    private boolean clickSuggestion(int mx, int my) {
        int row = suggestRowAt(mx, my);
        if (row < 0) {
            return false;
        }
        int index = this.tabScroll + row;
        if (index >= this.tabCandidates.size()) {
            return false;
        }
        applySuggestion(this.tabCandidates.get(index));
        clearTabState();
        return true;
    }

    private void clearTabState() {
        this.tabCandidates = List.of();
        this.tabBase = null;
        this.tabSuffix = "";
        this.tabApplied = null;
        this.tabScroll = 0;
        this.tabCursor = 0;
    }

    private int leftScrollbarX() {
        return PADDING + this.leftWidth + 4;
    }

    private int rightScrollbarX() {
        return this.width - PADDING - SCROLLBAR_WIDTH;
    }

    private boolean hitLeftScrollbar(int mx, int my) {
        return maxLeftScroll() > 0
                && mx >= leftScrollbarX() && mx <= leftScrollbarX() + SCROLLBAR_WIDTH + 4
                && my >= LEFT_TOP && my <= this.leftBottom;
    }

    private boolean hitRightScrollbar(int mx, int my) {
        return maxScroll() > 0
                && mx >= rightScrollbarX() && mx <= rightScrollbarX() + SCROLLBAR_WIDTH + 4
                && my >= LIST_TOP && my <= this.height - LIST_BOTTOM_MARGIN;
    }

    private void dragLeft(int mouseY) {
        int max = maxLeftScroll();
        if (max <= 0) {
            return;
        }
        int top = LEFT_TOP;
        int bottom = this.leftBottom;
        int track = bottom - top;
        int thumb = Math.max(20, leftViewportHeight() * track / Math.max(1, this.leftContentHeight));
        int travel = track - thumb;
        double ratio = (mouseY - top - thumb / 2.0) / Math.max(1, travel);
        this.leftScroll = Math.max(0, Math.min(max, (int) Math.round(ratio * max)));
        layoutLeft();
    }

    private void dragRight(int mouseY) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int top = LIST_TOP;
        int bottom = this.height - LIST_BOTTOM_MARGIN;
        int track = bottom - top;
        int thumb = Math.max(20, visibleRows() * track / Math.max(1, this.placements.size()));
        int travel = track - thumb;
        double ratio = (mouseY - top - thumb / 2.0) / Math.max(1, travel);
        this.scrollOffset = Math.max(0, Math.min(max, (int) Math.round(ratio * max)));
        layoutRows();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------
    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        this.extractTransparentBackground(extractor);

        // 左列按钮自己带裁剪区绘制。控件不像文字那样自动受 scissor 影响，所以必须在这里
        // 手动包一层，才能让只露出一半的按钮被正确裁掉、滚动时平滑滑入滑出。
        // 被候选列表盖住的控件这一帧按「鼠标不在上面」渲染：悬停是每帧按坐标重算的，
        // 不这么做的话鼠标明明在候选列表上，底下的按钮却会跟着高亮。
        extractor.enableScissor(0, LEFT_TOP, this.listX, this.leftBottom);
        for (Button button : this.leftWidgets) {
            if (button.visible) {
                boolean covered = suggestCovers(button.getX(), button.getY(),
                        button.getWidth(), button.getHeight());
                button.extractRenderState(extractor, covered ? -1 : mouseX, covered ? -1 : mouseY, partialTick);
            }
        }
        extractor.disableScissor();

        // 其余控件（指令栏、发送、右列按钮）按原插入顺序绘制，不裁剪
        for (var child : this.children()) {
            if (child instanceof AbstractWidget widget && !this.leftWidgets.contains(widget)) {
                boolean covered = suggestCovers(widget.getX(), widget.getY(),
                        widget.getWidth(), widget.getHeight());
                widget.extractRenderState(extractor, covered ? -1 : mouseX, covered ? -1 : mouseY, partialTick);
            } else if (child instanceof Renderable renderable && !this.leftWidgets.contains(renderable)) {
                renderable.extractRenderState(extractor, mouseX, mouseY, partialTick);
            }
        }

        SimulationManager manager = SimulationManager.getInstance();
        extractor.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 12, COLOR_TEXT);

        String status = "活动模拟 " + manager.getActiveCount()
                + " · 等待启动 " + manager.getPendingCount()
                + " · 投影总数 " + this.placements.size();
        extractor.text(this.font, status, this.width / 2 - this.font.width(status) / 2, 28, COLOR_DIM);

        // 左列：标题随内容滚动（用 scissor 裁剪）
        extractor.enableScissor(0, LEFT_TOP, this.listX, this.leftBottom);
        for (Header header : this.leftHeaders) {
            extractor.text(this.font, header.text(), PADDING, header.baseY() - this.leftScroll, COLOR_HEADER);
        }
        extractor.disableScissor();

        extractor.text(this.font, "模拟世界指令", PADDING, this.height - 40 - 12, COLOR_HEADER);
        extractor.text(this.font, "单个投影（滚轮翻页）", this.listX, LEFT_TOP, COLOR_HEADER);

        if (this.placements.isEmpty()) {
            extractor.text(this.font, "没有已加载的投影放置", this.listX, LIST_TOP + 4, COLOR_DIM);
        } else {
            extractor.enableScissor(this.listX, LIST_TOP, this.listX + this.listWidth, this.height - LIST_BOTTOM_MARGIN);
            int rowX = Math.max(this.listX, this.listX + this.listWidth - ROW_BTN_W);
            int cfgX = Math.max(this.listX, rowX - 4 - CFG_BTN_W);
            int lineMax = Math.max(0, cfgX - (this.listX + 2) - 4);
            int visible = visibleRows();
            for (int slot = 0; slot < visible; slot++) {
                int index = slot + this.scrollOffset;
                if (index >= this.placements.size()) {
                    break;
                }
                boolean running = manager.isSimulating(this.placements.get(index));
                // 整行一起截断：状态圆点也算进宽度。先拼再截，窄屏时圆点不会顶到「配置」按钮。
                String line = this.font.plainSubstrByWidth(
                        (running ? "● " : "○ ") + this.placements.get(index).getName(), lineMax);
                extractor.text(this.font, line, this.listX + 2, LIST_TOP + slot * ROW_HEIGHT + 7,
                        running ? 0xFF55FF55 : COLOR_DIM);
            }
            extractor.disableScissor();
        }

        // 滑块
        drawScrollbar(extractor, leftScrollbarX(), LEFT_TOP, this.leftBottom,
                this.leftScroll, maxLeftScroll(), leftViewportHeight(), Math.max(1, this.leftContentHeight));
        drawScrollbar(extractor, rightScrollbarX(), LIST_TOP, this.height - LIST_BOTTOM_MARGIN,
                this.scrollOffset * ROW_HEIGHT, maxScroll() * ROW_HEIGHT,
                visibleRows() * ROW_HEIGHT, Math.max(1, this.placements.size() * ROW_HEIGHT));

        // 指令补全候选列表（TAB 后显示，鼠标点击/滚轮选取）
        if (suggestVisible()) {
            drawSuggestionList(extractor, mouseX, mouseY);
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor extractor, int x, int top, int bottom,
                               int scrollPx, int maxScrollPx, int viewportPx, int contentPx) {
        if (maxScrollPx <= 0 || bottom <= top) {
            return;
        }
        int track = bottom - top;
        int thumb = Math.max(20, viewportPx * track / contentPx);
        int travel = track - thumb;
        int thumbTop = top + travel * scrollPx / maxScrollPx;
        extractor.fill(x, top, x + SCROLLBAR_WIDTH, bottom, COLOR_TRACK);
        extractor.fill(x, thumbTop, x + SCROLLBAR_WIDTH, thumbTop + thumb, COLOR_THUMB);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
