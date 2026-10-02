package ml.pypals.simulatica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.carpet.BotManager;
import ml.pypals.simulatica.carpet.CarpetIntegration;
import ml.pypals.simulatica.simulation.SimulationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;

/**
 * [SIMULATICA-新增] 单个投影的模拟配置界面。
 *
 * <p>承载该投影的假人联动（召唤、一键清理、传送、打开背包、动作、游戏模式）与 Simulatica 原生功能
 * （掉落物吸收、清除越界）。假人动作按钮过多时折叠，用「展开动作」按钮展开。</p>
 */
public final class PlacementConfigScreen extends Screen {

    private static final int PADDING = 16;
    private static final int ROW_HEIGHT = 22;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HEADER = 0xFF55FFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;

    // 假人行：名字列 + 四个按钮列。四列宽度写死，每个假人行的列 x 完全一致，
    // 这样多个假人之间纵向严格成列；名字列宽度取所有名字里最宽的那个，
    // 所以名字再长也不会把右边的按钮推着往右跑。
    private static final int GAP = 4;
    private static final int[] BOT_SLOT_WIDTHS = {46, 46, 70, 46};

    private final SchematicPlacement placement;
    private final List<Button> botButtons = new ArrayList<>();
    private final List<NameTag> botNameTags = new ArrayList<>();
    /** 每个可视行按钮的 y，索引 = 可视行号（不含 scrollOffset）。绘制名字时按它对齐。 */
    private final List<Integer> botTagY = new ArrayList<>();
    private boolean actionsExpanded = false;
    private int scrollOffset;
    private int botListTop;
    /** 假人行第一个按钮的 x（名字列之后）。所有行共用，保证纵向成列。 */
    private int botRowX;

    private int flowX;
    private int flowY;

    // 动作参数状态（0=关，-1=长按，>0=间隔 tick）
    private int attackInterval = 0;
    private int useInterval = 0;
    private int jumpInterval = 0;
    private boolean sneaking = false;
    private boolean sprinting = false;
    private boolean forward = false;

    /** 假人名字标签。y 不存这里，由 {@link #botTagY} 每个可视行现算，滚动时才跟得上。 */
    private record NameTag(int x, String text) {}

    public PlacementConfigScreen(SchematicPlacement placement) {
        super(Component.literal("投影配置 - " + placement.getName()));
        this.placement = placement;
    }

    @Override
    protected void init() {
        this.botButtons.clear();
        this.botNameTags.clear();
        this.botTagY.clear();
        this.botListTop = 0;
        this.scrollOffset = 0;
        this.flowX = PADDING;
        this.flowY = 36;

        buildBotTools();
        buildBotList();
        buildNative();
    }

    private void place(Button button, int width) {
        if (this.flowX + width > this.width - PADDING) {
            this.flowX = PADDING;
            this.flowY += ROW_HEIGHT;
        }
        button.setX(this.flowX);
        button.setY(this.flowY);
        button.setWidth(width);
        this.flowX += width + 4;
        addRenderableWidget(button);
    }

    private void newRow() {
        this.flowX = PADDING;
        this.flowY += ROW_HEIGHT;
    }

    // ------------------------------------------------------------------
    // 假人工具 + 折叠动作
    // ------------------------------------------------------------------
    private void buildBotTools() {
        if (!CarpetIntegration.isLoaded()) {
            place(Button.builder(Component.literal("假人联动需安装 Carpet 模组"),
                            button -> SimulaticaClient.sendFeedback("未检测到 Carpet 模组，无法使用假人联动。"))
                    .build(), 230);
            this.newRow();
            return;
        }

        place(Button.builder(Component.literal("召唤假人"), button -> {
                    if (spawnBot()) {
                        rebuild();
                    }
                }).build(), 80);
        place(Button.builder(Component.literal("一键清理"), button -> {
                    int n = BotManager.botsOf(this.placement).size();
                    BotManager.removeAll(this.placement);
                    SimulaticaClient.sendFeedback("已清理 " + n + " 个假人。");
                    rebuild();
                }).build(), 80);
        place(Button.builder(Component.literal("传送全部到玩家"), button -> {
                    for (BotManager.Bot bot : BotManager.botsOf(this.placement)) {
                        BotManager.teleportToPlayer(bot);
                    }
                    SimulaticaClient.sendFeedback("已把该投影全部假人传送到玩家位置。");
                }).build(), 130);

        this.newRow();
        place(Button.builder(Component.literal(this.actionsExpanded ? "收起动作" : "展开动作"),
                        button -> {
                            this.actionsExpanded = !this.actionsExpanded;
                            rebuild();
                        }).build(), 80);

        if (this.actionsExpanded) {
            place(Button.builder(Component.literal(intervalLabel("攻击间隔", this.attackInterval)), button -> {
                        this.attackInterval = nextInterval(this.attackInterval);
                        BotManager.actionAllInterval(this.placement, "ATTACK", this.attackInterval);
                        button.setMessage(Component.literal(intervalLabel("攻击间隔", this.attackInterval)));
                    }).build(), 90);
            place(Button.builder(Component.literal(intervalLabel("使用间隔", this.useInterval)), button -> {
                        this.useInterval = nextInterval(this.useInterval);
                        BotManager.actionAllInterval(this.placement, "USE", this.useInterval);
                        button.setMessage(Component.literal(intervalLabel("使用间隔", this.useInterval)));
                    }).build(), 90);
            place(Button.builder(Component.literal(intervalLabel("跳跃间隔", this.jumpInterval)), button -> {
                        this.jumpInterval = nextInterval(this.jumpInterval);
                        BotManager.actionAllInterval(this.placement, "JUMP", this.jumpInterval);
                        button.setMessage(Component.literal(intervalLabel("跳跃间隔", this.jumpInterval)));
                    }).build(), 90);
            this.newRow();
            place(Button.builder(Component.literal(this.sneaking ? "潜行:开" : "潜行:关"), button -> {
                        this.sneaking = !this.sneaking;
                        BotManager.setSneakingAll(this.placement, this.sneaking);
                        button.setMessage(Component.literal(this.sneaking ? "潜行:开" : "潜行:关"));
                    }).build(), 62);
            place(Button.builder(Component.literal(this.sprinting ? "疾跑:开" : "疾跑:关"), button -> {
                        this.sprinting = !this.sprinting;
                        BotManager.setSprintingAll(this.placement, this.sprinting);
                        button.setMessage(Component.literal(this.sprinting ? "疾跑:开" : "疾跑:关"));
                    }).build(), 62);
            place(Button.builder(Component.literal(this.forward ? "前进:开" : "前进:关"), button -> {
                        this.forward = !this.forward;
                        BotManager.setForwardAll(this.placement, this.forward ? 1.0F : 0.0F);
                        button.setMessage(Component.literal(this.forward ? "前进:开" : "前进:关"));
                    }).build(), 62);
            this.newRow();
            place(Button.builder(Component.literal("单击左键"), button ->
                    BotManager.actionAll(this.placement, "ATTACK", false)).build(), 70);
            place(Button.builder(Component.literal("单击右键"), button ->
                    BotManager.actionAll(this.placement, "USE", false)).build(), 70);
            place(Button.builder(Component.literal("丢弃"), button -> BotManager.actionAll(this.placement, "DROP_ITEM", false)).build(), 54);
            place(Button.builder(Component.literal("换手"), button -> BotManager.actionAll(this.placement, "SWAP_HANDS", false)).build(), 54);
            place(Button.builder(Component.literal("停止"), button -> BotManager.stopAllActions(this.placement)).build(), 54);
        }
    }

    private static final int[] INTERVALS = {0, -1, 1, 2, 5, 10, 20};

    private static int nextInterval(int current) {
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == current) {
                return INTERVALS[(i + 1) % INTERVALS.length];
            }
        }
        return 0;
    }

    private static String intervalLabel(String prefix, int interval) {
        String value = switch (interval) {
            case 0 -> "关";
            case -1 -> "长按";
            default -> interval + "t";
        };
        return prefix + ":" + value;
    }

    private boolean spawnBot() {
        int index = BotManager.botsOf(this.placement).size() + 1;
        String name;
        do {
            name = "Bot" + index++;
        } while (hasBotNamed(name));

        BotManager.Bot bot = BotManager.spawn(this.placement, name, GameType.CREATIVE);
        if (bot == null) {
            SimulaticaClient.sendFeedback("无法召唤假人：请先启动该投影的模拟。");
            return false;
        }
        SimulaticaClient.sendFeedback("已召唤假人 " + name + "。");
        return true;
    }

    private boolean hasBotNamed(String name) {
        for (BotManager.Bot bot : BotManager.botsOf(this.placement)) {
            if (bot.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 假人列表
    // ------------------------------------------------------------------
    private void buildBotList() {
        this.newRow();
        this.flowY += 4;
        this.botListTop = this.flowY;

        List<BotManager.Bot> bots = BotManager.botsOf(this.placement);

        // 先量一遍所有名字，取最宽的那个当名字列宽度。名字列宽度必须与具体某个
        // 名字无关，否则每个假人行的按钮起点都会跟着各自名字的长度左右移动。
        int nameColWidth = 0;
        for (BotManager.Bot bot : bots) {
            nameColWidth = Math.max(nameColWidth, this.font.width(bot.name()));
        }
        int avail = this.width - 2 * PADDING;
        int btnRunWidth = 0;
        for (int w : BOT_SLOT_WIDTHS) {
            btnRunWidth += w + GAP;
        }
        btnRunWidth -= GAP; // 最后一列后面没有间距
        // 窗口宽度连「左右边距 + 四列按钮」都放不下时（四列 46+46+70+46 + 三个 4px
        // 间距 = 224），只能连左边距一起压缩让按钮居中。名字列此时为 0，名字会和
        // 第一个按钮重叠——可接受的降级，因为按钮才是能点的东西。
        int leftPad = PADDING;
        if (btnRunWidth > avail) {
            leftPad = Math.max(0, (this.width - btnRunWidth) / 2);
        }
        int nameCol;
        if (nameColWidth + GAP + btnRunWidth <= this.width - leftPad - PADDING) {
            nameCol = nameColWidth + GAP;
        } else {
            nameCol = Math.max(0, this.width - leftPad - PADDING - btnRunWidth);
        }
        this.botRowX = leftPad + nameCol;

        for (BotManager.Bot bot : bots) {
            this.newRow();

            this.botNameTags.add(new NameTag(leftPad, bot.name()));

            int x = this.botRowX;
            for (int i = 0; i < BOT_SLOT_WIDTHS.length; i++) {
                this.botButtons.add(addRenderableWidget(
                        botSlot(bot, i, x, BOT_SLOT_WIDTHS[i])));
                x += BOT_SLOT_WIDTHS[i] + GAP;
            }
        }
        layoutBotRows();
    }

    /** 按列序号建第 {@code slot} 列的按钮（0=背包 1=传送 2=模式 3=移除）。 */
    private Button botSlot(BotManager.Bot bot, int slot, int x, int width) {
        Component label = switch (slot) {
            case 0 -> Component.literal("背包");
            case 1 -> Component.literal("传送");
            case 2 -> Component.literal("模式:" + modeName(bot.player()));
            default -> Component.literal("移除");
        };
        Button button = Button.builder(label, b -> onBotSlot(bot, slot, b))
                .bounds(x, 0, width, 20).build();
        return button;
    }

    private void onBotSlot(BotManager.Bot bot, int slot, Button button) {
        switch (slot) {
            case 0 -> Minecraft.getInstance().gui.setScreen(new BotInventoryScreen(bot.player()));
            case 1 -> BotManager.teleportToPlayer(bot);
            case 2 -> {
                bot.player().setGameMode(nextMode(bot.player()));
                button.setMessage(Component.literal("模式:" + modeName(bot.player())));
            }
            default -> {
                BotManager.remove(this.placement, bot);
                rebuild();
            }
        }
    }

    private void layoutBotRows() {
        if (this.botListTop == 0) {
            return;
        }
        int visible = visibleBotRows();
        for (int i = 0; i < this.botButtons.size(); i++) {
            int botIndex = i / BOT_SLOT_WIDTHS.length;
            Button row = this.botButtons.get(i);
            int slot = botIndex - this.scrollOffset;
            boolean shown = slot >= 0 && slot < visible;
            row.visible = shown;
            row.active = shown;
            if (shown) {
                row.setY(botRowY(slot));
            }
        }
        this.botTagY.clear();
        for (int slot = 0; slot < visible; slot++) {
            this.botTagY.add(botRowY(slot));
        }
    }

    /** 第 {@code slot} 个可视行里，按钮的 y。名字标签在此基础上 +6 做视觉居中。 */
    private int botRowY(int slot) {
        return this.botListTop + slot * ROW_HEIGHT + 1;
    }

    /** 假人列表的可视区下边界。绘制时的 scissor 和行数计算必须共用它，否则会画出框外。 */
    private int botListBottom() {
        return this.height - 20;
    }

    private int visibleBotRows() {
        if (this.botListTop <= 0) {
            return 1;
        }
        return Math.max(1, (botListBottom() - this.botListTop) / ROW_HEIGHT);
    }

    private int maxBotScroll() {
        int count = BotManager.botsOf(this.placement).size();
        return Math.max(0, count - visibleBotRows());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxBotScroll() > 0 && mouseY >= this.botListTop && mouseY < botListBottom()) {
            this.scrollOffset = Math.max(0, Math.min(maxBotScroll(), this.scrollOffset - (int) Math.signum(scrollY)));
            layoutBotRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // Simulatica 原生
    // ------------------------------------------------------------------
    private void buildNative() {
        this.newRow();
        this.flowY += 8;

        place(Button.builder(Component.literal("掉落物吸收：" + (SimulationManager.getInstance().isItemAbsorption() ? "开" : "关")),
                        button -> {
                            SimulationManager.getInstance().setItemAbsorption(null);
                            button.setMessage(Component.literal("掉落物吸收：" + (SimulationManager.getInstance().isItemAbsorption() ? "开" : "关")));
                        }).build(), 130);
        place(Button.builder(Component.literal("清除越界实体"),
                        button -> {
                            int removed = SimulationManager.getInstance().purgeEscapedEntities();
                            SimulaticaClient.sendFeedback(removed == 0
                                    ? "No escaped entities found."
                                    : "Purged " + removed + " escaped entities.");
                        }).build(), 110);
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private static String modeName(ServerPlayer player) {
        return switch (player.gameMode.getGameModeForPlayer()) {
            case SURVIVAL -> "生存";
            case CREATIVE -> "创造";
            case ADVENTURE -> "冒险";
            case SPECTATOR -> "旁观";
        };
    }

    private static GameType nextMode(ServerPlayer player) {
        return switch (player.gameMode.getGameModeForPlayer()) {
            case SURVIVAL -> GameType.CREATIVE;
            case CREATIVE -> GameType.ADVENTURE;
            case ADVENTURE -> GameType.SPECTATOR;
            case SPECTATOR -> GameType.SURVIVAL;
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        this.extractTransparentBackground(extractor);
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        extractor.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 12, COLOR_TEXT);

        extractor.text(this.font, "假人联动（Carpet，单个投影）", PADDING, 36 - 12, COLOR_HEADER);

        extractor.enableScissor(0, this.botListTop, this.width, this.botListBottom());
        int slot = 0;
        for (NameTag tag : this.botNameTags) {
            int row = slot - this.scrollOffset;
            if (row >= 0 && row < this.botTagY.size()) {
                extractor.text(this.font, tag.text(), tag.x(), this.botTagY.get(row) + 6, COLOR_TEXT);
            }
            slot++;
        }
        extractor.disableScissor();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean hasActiveButton) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) {
            ml.pypals.simulatica.workshop.WorkshopManager.requestReturn();
            return true;
        }
        return super.mouseClicked(event, hasActiveButton);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) {
            ml.pypals.simulatica.workshop.WorkshopManager.requestReturn();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void tick() {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive())
            ml.pypals.simulatica.workshop.WorkshopManager.requestReturn();
        else super.tick();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
