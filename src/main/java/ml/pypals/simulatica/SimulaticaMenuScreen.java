package ml.pypals.simulatica;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.MaLiLibIcons;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import ml.pypals.simulatica.carpet.BotManager;
import ml.pypals.simulatica.carpet.CarpetIntegration;
import ml.pypals.simulatica.counter.HopperCounter;
import ml.pypals.simulatica.config.SimulaticaConfigs;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.SimulationCommands;
import ml.pypals.simulatica.workshop.WorkshopManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import ml.pypals.simulatica.simulation.TpsSettings;

/** malilib-style simulation controls; a placement-scoped child shows one placement's settings. */
public class SimulaticaMenuScreen extends GuiConfigsBase {
    private static final int PADDING = 10;
    private static final int GAP = 4;
    private static final int BTN_H = 20;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int COLOR_TRACK = 0x33000000;
    private static final int COLOR_THUMB = 0xCC888888;
    private static final String[] TIME_KEYS = {"day", "noon", "night", "midnight"};
    private static final String[] WEATHER_KEYS = {"clear_weather", "rain", "thunder"};
    private static final String[] DIFFICULTY_KEYS = {"peaceful", "easy", "normal", "hard"};

    private enum Page { PROJECTIONS, GENERAL, PLACEMENT }
    private record ListState(String search, boolean open, int scroll) {}
    private record Control(Supplier<String> text, Runnable run, BooleanSupplier enabled,
                           String hint, int width, boolean reset) {
        Control(Supplier<String> text, Runnable run, BooleanSupplier enabled, String hint, int width) {
            this(text, run, enabled, hint, width, false);
        }
    }
    private record Entry(String search, Supplier<String> label, List<Control> controls, boolean compact) {
        Entry(String search, Supplier<String> label, List<Control> controls) { this(search, label, controls, false); }
    }

    private record NumberField(GuiTextFieldGeneric field, IntSupplier value, IntConsumer commit,
                               int min, int max, BooleanSupplier editable) {}
    private record Label(Supplier<String> text, int x, int y, int color) {}

    private final SchematicPlacement placement;
    /** Non-null on a bot's config page, a child of the placement page. */
    private final BotManager.Bot bot;
    private Page page;
    private final EnumMap<Page, ListState> listStates = new EnumMap<>(Page.class);
    private final List<SchematicPlacement> placements = new ArrayList<>();
    private final List<ButtonGeneric> toolbarButtons = new ArrayList<>();
    private final List<Control> toolbarControls = new ArrayList<>();
    private ControlsList content;
    private float placementScale = 1.0f;
    private List<Object> dataStamp = List.of();
    private int contentTop;
    private int flowX;
    private int flowY;
    private final List<NumberField> numberFields = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private int botRowsTop;
    private int botRowsWidth;

    private GuiTextFieldGeneric commandField;
    private List<Suggestion> tabCandidates = List.of();
    private int tabCursor;
    private int tabScroll;
    private String tabBase;
    private String tabSuffix = "";
    private String tabApplied;
    private int completionRequest;

    public SimulaticaMenuScreen() { this(null, null); }

    protected SimulaticaMenuScreen(SchematicPlacement placement, Screen parent) {
        this(placement, null, parent);
    }

    protected SimulaticaMenuScreen(SchematicPlacement placement, BotManager.Bot bot, Screen parent) {
        super(10, 60, Simulatica.MOD_ID, parent, "simulatica.ui.title");
        this.placement = placement;
        this.bot = bot;
        this.page = placement == null ? Page.PROJECTIONS : Page.PLACEMENT;
        setParent(parent);
        this.useTitleHierarchy = false;
    }

    private static String tr(String key, Object... args) {
        return StringUtils.translate("simulatica.ui." + key, args);
    }

    @Override
    protected WidgetListConfigOptions createListWidget(int x, int y) { return null; }
    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(List.of(SimulaticaConfigs.TPS_HUD, SimulaticaConfigs.TPS_MULTILINE));
    }
    @Override
    protected void buildConfigSwitcher() {
        if (this.placement == null) {
            super.buildConfigSwitcher();
        }
    }

    /** Applies every number field being edited; invalid input falls back to the current value. */
    private void commitInputs() {
        for (NumberField number : this.numberFields) {
            if (!number.field().isFocused()) continue;
            try {
                int value = Integer.parseInt(number.field().getValue().trim());
                if (value >= number.min() && value <= number.max() && value != number.value().getAsInt())
                    number.commit().accept(value);
            } catch (NumberFormatException ignored) {}
            number.field().setFocused(false);
        }
        syncNumberFields();
    }

    private boolean editingNumber() {
        return this.numberFields.stream().anyMatch(number -> number.field().isFocused());
    }

    private void syncNumberFields() {
        for (NumberField number : this.numberFields) {
            GuiTextFieldGeneric field = number.field();
            if (field.isFocused()) continue;
            String value = Integer.toString(number.value().getAsInt());
            if (!value.equals(field.getValue())) field.setValue(value);
            boolean editable = number.editable().getAsBoolean();
            field.setEditable(editable);
            field.setTextColor(editable ? COLOR_TEXT : COLOR_DIM);
        }
    }

    private void numberField(int x, int y, int width, IntSupplier value, IntConsumer commit,
                             int min, int max, BooleanSupplier editable) {
        GuiTextFieldGeneric field = new GuiTextFieldGeneric(x + 1, y + 1, width - 2, BTN_H - 2, this.font);
        field.setMaxLength(Integer.toString(max).length());
        field.setValue(Integer.toString(value.getAsInt()));
        field.setFocused(false);
        addTextField(field, f -> {
            try {
                int typed = Integer.parseInt(f.getValue().trim());
                f.setTextColor(typed >= min && typed <= max ? COLOR_TEXT : 0xFFFF5555);
            } catch (NumberFormatException invalid) { f.setTextColor(0xFFFF5555); }
            return true;
        });
        this.numberFields.add(new NumberField(field, value, commit, min, max, editable));
    }

    private void rememberList() {
        if (this.content != null && this.placement == null) {
            this.listStates.put(this.page, new ListState(this.content.search.text(),
                    this.content.search.isSearchOpen(), this.content.getScrollbar().getValue()));
        }
    }

    private void selectPage(Page next) {
        rememberList();
        this.content = null;
        this.page = next;
        initGui();
    }

    @Override
    public void initGui() {
        commitInputs();
        rememberList();
        String command = this.commandField == null ? "" : this.commandField.getValue();
        int cursor = this.commandField == null ? 0 : this.commandField.getCursorPosition();
        boolean focused = this.commandField != null && this.commandField.isFocused();
        int botScroll = this.placement != null && this.content != null ? this.content.getScrollbar().getValue() : 0;
        clearTabState();
        if (this.placement != null) {
            int viewportWidth = this.mc.getWindow().getGuiScaledWidth();
            int viewportHeight = this.mc.getWindow().getGuiScaledHeight();
            // Keep the user's GUI scale where the page fits; only shrink this screen when necessary.
            this.placementScale = Math.min(1.0f, Math.min(viewportWidth / 480.0f,
                    viewportHeight / (this.bot == null ? 270.0f : 290.0f)));
            this.width = Math.round(viewportWidth / this.placementScale);
            this.height = Math.round(viewportHeight / this.placementScale);
        }
        super.initGui();
        this.toolbarButtons.clear();
        this.toolbarControls.clear();
        this.placements.clear();
        this.placements.addAll(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
        this.title = this.placement == null ? tr("title")
                : tr("title") + " => " + tr("placement_config") + " => " + this.placement.getName()
                + (this.bot == null ? "" : " => " + this.bot.name());
        this.numberFields.clear();
        this.labels.clear();
        if (this.placement != null) {
            this.commandField = null;
            if (this.bot != null) {
                this.content = null;
                buildBotLayout();
            } else {
                buildPlacementLayout();
                this.content.restoreScroll(botScroll);
            }
            this.dataStamp = currentDataStamp();
            return;
        }
        // Same geometry as malilib's own config screens (e.g. Litematica's GuiConfigs).
        this.flowX = PADDING;
        this.flowY = 26;
        for (Page next : new Page[]{Page.PROJECTIONS, Page.GENERAL}) {
            Control tab = control("tab." + next.name().toLowerCase(Locale.ROOT), () -> selectPage(next));
            ButtonGeneric button = flowButton(tab);
            button.setEnabled(this.page != next);
        }
        this.contentTop = 50;
        int toolbarWidth = buildToolbar();
        this.content = new ControlsList(PADDING, this.contentTop, this.width - 2 * PADDING,
                Math.max(24, this.height - 80), entries(), true, toolbarWidth);
        this.content.initGui();
        ListState saved = this.listStates.get(this.page);
        if (saved != null) this.content.restore(saved);
        this.dataStamp = currentDataStamp();
        int sendWidth = this.font.width(tr("send")) + 14;
        int y = this.height - 25;
        this.commandField = new GuiTextFieldGeneric(PADDING, y, this.width - 2 * PADDING - sendWidth - GAP, 18, this.font);
        this.commandField.setMaxLength(256);
        this.commandField.setHint(Component.literal(tr("command_hint")));
        this.commandField.setValue(command);
        this.commandField.setCursorPosition(Math.min(cursor, command.length()));
        this.commandField.setHighlightPos(this.commandField.getCursorPosition());
        this.commandField.setFocused(focused);
        addButton(new ButtonGeneric(this.width - PADDING - sendWidth, y - 1, sendWidth, BTN_H, tr("send")),
                (button, mouseButton) -> { if (mouseButton == 0) sendCommand(); });
    }

    private Control control(String key, Runnable run) {
        return dynamic(() -> tr(key), run, () -> true, "", tr(key));
    }

    private Control dynamic(Supplier<String> text, Runnable run, BooleanSupplier enabled, String hint, String... values) {
        int width = this.font.width(text.get());
        for (String value : values) width = Math.max(width, this.font.width(value));
        return new Control(text, run, () -> !WorkshopManager.isActive() && enabled.getAsBoolean(), hint, width + 10);
    }

    private Control botControl(String key, Runnable run) {
        return dynamic(() -> tr(key), run, CarpetIntegration::isLoaded, tr("requires_carpet"));
    }

    private ButtonGeneric flowButton(Control control) {
        int width = Math.min(control.width(), this.width - 2 * PADDING);
        if (this.flowX > PADDING && this.flowX + width > this.width - PADDING) {
            this.flowX = PADDING;
            this.flowY += BTN_H + GAP;
        }
        ButtonGeneric button = new ButtonGeneric(this.flowX, this.flowY, width, BTN_H, control.text().get());
        button.setEnabled(control.enabled().getAsBoolean());
        addButton(button, (b, mouseButton) -> { if (mouseButton == 0 && control.enabled().getAsBoolean()) control.run().run(); });
        this.flowX += width + 2;
        return button;
    }

    private void toolbar(Control control) {
        this.toolbarButtons.add(flowButton(control));
        this.toolbarControls.add(control);
    }

    /** Places the page's actions right of the search bar; returns the width they take. */
    private int buildToolbar() {
        if (this.page != Page.PROJECTIONS) return 0;
        Control start = control("start_all", () -> this.placements.forEach(SimulationManager.getInstance()::startSimulation));
        Control stop = control("stop_all", () -> SimulationManager.getInstance().stopAll());
        int width = start.width() + 2 + stop.width();
        this.flowX = Math.max(PADDING, this.width - PADDING - width);
        this.flowY = this.contentTop + 1;
        toolbar(start);
        toolbar(stop);
        return width;
    }

    /** A tracked button whose label and enabled state follow its control; right click runs {@code back}. */
    private ButtonGeneric placementButton(int x, int y, int width, Control control, Runnable back) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, BTN_H, control.text().get());
        button.setEnabled(control.enabled().getAsBoolean());
        addButton(button, (b, mouseButton) -> {
            if (!control.enabled().getAsBoolean()) return;
            if (mouseButton == 0) control.run().run();
            else if (mouseButton == 1 && back != null) back.run();
            refreshToolbar();
        });
        this.toolbarButtons.add(button);
        this.toolbarControls.add(control);
        return button;
    }

    private void buildPlacementLayout() {
        var manager = SimulationManager.getInstance();
        int top = 26, bottomRow = this.height - 28;
        int rightWidth = Math.max(170, Math.min(230, this.width * 34 / 100));
        int rightX = this.width - PADDING - rightWidth;
        int leftWidth = rightX - 12 - PADDING;

        // Right column: this placement's own settings, one wide button each.
        int y = top;
        placementButton(rightX, y, rightWidth, dynamic(() -> labeled(tr("tab.simulation"),
                manager.isSimulating(this.placement) ? "§a" + tr("running") : "§c" + tr("stopped")), () -> {
            if (manager.isSimulating(this.placement)) manager.stopSimulation(this.placement);
            else manager.startSimulation(this.placement);
        }, () -> this.placement.getSchematic() != null, ""), null);
        y += BTN_H + GAP;
        Control resetTps = new Control(() -> tr("reset"), () -> setTps(20),
                () -> !WorkshopManager.isActive() && TpsSettings.get(this.placement) != 20, "", this.font.width(tr("reset")) + 12, true);
        this.labels.add(new Label(() -> tr("target_tps"), rightX, y + 6, COLOR_TEXT));
        int fieldX = rightX + this.font.width(tr("target_tps")) + 6;
        numberField(fieldX, y, rightX + rightWidth - resetTps.width() - GAP - fieldX,
                () -> TpsSettings.get(this.placement), this::setTps, 1, 1000, () -> true);
        placementButton(rightX + rightWidth - resetTps.width(), y, resetTps.width(), resetTps, null);
        y += BTN_H + GAP;
        placementButton(rightX, y, rightWidth, dynamic(() -> labeled(tr("absorption"),
                manager.isItemAbsorption(this.placement) ? "§a" + tr("on") : "§c" + tr("off")),
                () -> manager.setItemAbsorption(this.placement, !manager.isItemAbsorption(this.placement)), () -> true, ""), null);
        y += BTN_H + GAP + 6;
        y = cycleButton(rightX, y, rightWidth, "time", TIME_KEYS, SimulationManager.WorldSetting.TIME);
        y = cycleButton(rightX, y, rightWidth, "weather", WEATHER_KEYS, SimulationManager.WorldSetting.WEATHER);
        y = cycleButton(rightX, y, rightWidth, "difficulty", DIFFICULTY_KEYS, SimulationManager.WorldSetting.DIFFICULTY);
        y += 6;
        placementButton(rightX, y, rightWidth, dynamic(() -> tr("purge"), () -> {
            int removed = manager.purgeEscapedEntities(this.placement);
            SimulaticaClient.sendFeedback(tr("purged", removed));
        }, () -> true, ""), null);

        // Left: this placement's bots, like Litematica's sub-region list.
        this.labels.add(new Label(() -> tr("bots_header", BotManager.botsOf(this.placement).size()), PADDING, top + 6, COLOR_TEXT));
        Control spawn = dynamic(() -> tr("spawn"), this::spawnBot,
                () -> CarpetIntegration.isLoaded() && manager.projectionLevel(this.placement) != null
                        && manager.isSimulating(this.placement), tr("spawn_hint"));
        Control removeAll = dynamic(() -> tr("remove_all"), () -> BotManager.removeAll(this.placement),
                () -> CarpetIntegration.isLoaded() && !BotManager.botsOf(this.placement).isEmpty(), tr("requires_carpet"));
        int x = PADDING + leftWidth - removeAll.width();
        placementButton(x, top, removeAll.width(), removeAll, null);
        placementButton(x - GAP - spawn.width(), top, spawn.width(), spawn, null);
        int listTop = top + BTN_H + 6;
        this.content = new ControlsList(PADDING, listTop, leftWidth, Math.max(24, bottomRow - 8 - listTop), entries(), false);
        this.content.initGui();

        // Bottom: entries that open another screen or workflow.
        x = PADDING;
        for (Control entry : List.of(
                dynamic(() -> tr("workshop"), () -> {
                    commitInputs();
                    this.mc.gui.setScreen(null);
                    WorkshopManager.enter(this.placement);
                }, () -> this.placement.getSchematic() != null, ""),
                // Counters belong to the simulation world, which a stopped simulation keeps.
                dynamic(() -> tr("counter_values"), () -> {
                    SimulationLevel level = manager.projectionLevel(this.placement);
                    if (level == null) SimulaticaClient.sendFeedback(tr("no_counter_data"));
                    else HopperCounter.format(level).forEach(SimulaticaClient::sendFeedback);
                }, () -> true, ""),
                dynamic(() -> tr("reset_counters"), () -> {
                    SimulationLevel level = manager.projectionLevel(this.placement);
                    if (level != null) HopperCounter.reset(level);
                    SimulaticaClient.sendFeedback(tr("counters_reset"));
                }, () -> true, ""))) {
            int width = entry.width() + 8;
            placementButton(x, bottomRow, width, entry, null);
            x += width + GAP;
        }
        Control back = new Control(() -> tr("back"), () -> { commitInputs(); closeGui(true); }, () -> true, "",
                this.font.width(tr("back")) + 20);
        placementButton(this.width - PADDING - back.width(), bottomRow, back.width(), back, null);
    }

    private static final GameType[] GAME_TYPES = {GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR};
    private static final String[] ACTION_KEYS = {"use", "attack", "jump", "drop_item", "drop_stack", "swap_hands"};

    /** One bot's actions (left list) and its own state (right column), laid out like the placement page. */
    private void buildBotLayout() {
        BotManager.Bot bot = this.bot;
        int top = 36, bottomRow = this.height - 28;
        int rightWidth = Math.max(170, Math.min(230, this.width * 34 / 100));
        int rightX = this.width - PADDING - rightWidth;
        int leftWidth = rightX - 12 - PADDING;

        int y = top;
        placementButton(rightX, y, rightWidth, dynamic(() -> labeled(tr("game_mode"), "§e" + tr("mode." + gameType(bot).getName())),
                () -> stepGameMode(bot, 1), () -> true, ""), () -> stepGameMode(bot, -1));
        y += BTN_H + GAP;
        placementButton(rightX, y, rightWidth, dynamic(() -> labeled(tr("flying"), onOff(bot.player().getAbilities().flying)),
                () -> BotManager.setFlying(bot, !bot.player().getAbilities().flying),
                () -> gameType(bot) == GameType.CREATIVE, tr("flying_hint")), null);
        y += BTN_H + GAP + 6;
        BotManager.Movement[] moves = BotManager.Movement.values();
        placementButton(rightX, y, rightWidth, dynamic(() -> labeled(tr("movement"),
                        (bot.movement() == BotManager.Movement.STOP ? "§7" : "§e") + tr("move." + bot.movement().name().toLowerCase(Locale.ROOT))),
                () -> BotManager.setMovement(bot, moves[(bot.movement().ordinal() + 1) % moves.length]), this::hasCarpet, tr("requires_carpet")),
                () -> BotManager.setMovement(bot, moves[Math.floorMod(bot.movement().ordinal() - 1, moves.length)]));
        y += BTN_H + GAP;
        placementButton(rightX, y, rightWidth, dynamic(() -> labeled(tr("sneak"), onOff(bot.sneaking())),
                () -> BotManager.setSneaking(bot, !bot.sneaking()), this::hasCarpet, tr("requires_carpet")), null);
        y += BTN_H + GAP;
        placementButton(rightX, y, rightWidth, dynamic(() -> labeled(tr("sprint"), onOff(bot.sprinting())),
                () -> BotManager.setSprinting(bot, !bot.sprinting()), this::hasCarpet, tr("requires_carpet")), null);
        y += BTN_H + GAP + 6;
        placementButton(rightX, y, rightWidth, dynamic(() -> labeled(tr("hotbar"), "§e" + (bot.player().getInventory().getSelectedSlot() + 1)),
                () -> stepHotbar(bot, 1), () -> true, ""), () -> stepHotbar(bot, -1));
        y += BTN_H + GAP;
        placementButton(rightX, y, rightWidth, dynamic(() -> tr("copy_facing"), () -> BotManager.copyFacing(bot), () -> true, ""), null);

        // Left: one row per Carpet action -- mode (off / continuous / every N ticks), N, run once.
        this.labels.add(new Label(() -> tr("actions_header"), PADDING, top + 6, COLOR_TEXT));
        this.botRowsTop = top + BTN_H + 4;
        this.botRowsWidth = leftWidth;
        int onceWidth = this.font.width(tr("action_once")) + 10;
        int modeWidth = dynamic(() -> "", () -> {}, () -> true, "", tr("off"), tr("continuous"), tr("interval")).width() + 8;
        int fieldWidth = 36;
        for (int i = 0; i < ACTION_KEYS.length; i++) {
            int action = i, rowY = this.botRowsTop + i * (BTN_H + GAP) + 2;
            this.labels.add(new Label(() -> tr("action." + ACTION_KEYS[action]), PADDING + 4, rowY + 6, COLOR_TEXT));
            int x = PADDING + leftWidth - 2 - onceWidth;
            placementButton(x, rowY, onceWidth, dynamic(() -> tr("action_once"),
                    () -> BotManager.actionOnce(bot, action), this::hasCarpet, tr("requires_carpet")), null);
            x -= GAP + fieldWidth;
            numberField(x, rowY, fieldWidth, () -> bot.actionInterval(action),
                    ticks -> BotManager.setActionInterval(bot, action, ticks), 1, 9999, () -> bot.actionMode(action) > 0);
            x -= GAP + modeWidth;
            placementButton(x, rowY, modeWidth, dynamic(() -> actionModeText(bot.actionMode(action)),
                    () -> stepAction(bot, action, 1), this::hasCarpet, tr("requires_carpet")), () -> stepAction(bot, action, -1));
        }

        int x = PADDING;
        for (Control entry : List.of(
                dynamic(() -> "§c" + tr("stop_bot_actions"), () -> BotManager.stopAll(bot), this::hasCarpet, tr("requires_carpet"), tr("stop_bot_actions")),
                control("inventory", () -> { commitInputs(); this.mc.gui.setScreen(new BotInventoryScreen(bot.player(), this)); }),
                dynamic(() -> tr("remove_bot"), () -> { BotManager.remove(this.placement, bot); closeGui(true); }, () -> true, ""))) {
            int width = entry.width() + 8;
            placementButton(x, bottomRow, width, entry, null);
            x += width + GAP;
        }
        Control back = new Control(() -> tr("back_placement"), () -> { commitInputs(); closeGui(true); }, () -> true, "",
                this.font.width(tr("back_placement")) + 20);
        placementButton(this.width - PADDING - back.width(), bottomRow, back.width(), back, null);
    }

    private boolean hasCarpet() { return CarpetIntegration.isLoaded(); }

    private String onOff(boolean value) { return value ? "§a" + tr("on") : "§c" + tr("off"); }

    private static GameType gameType(BotManager.Bot bot) { return bot.player().gameMode.getGameModeForPlayer(); }

    private static void stepGameMode(BotManager.Bot bot, int delta) {
        bot.player().setGameMode(GAME_TYPES[Math.floorMod(gameType(bot).ordinal() + delta, GAME_TYPES.length)]);
    }

    private static void stepHotbar(BotManager.Bot bot, int delta) {
        var inventory = bot.player().getInventory();
        inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() + delta, 9));
    }

    private String actionModeText(int mode) {
        return mode == 0 ? "§7" + tr("off") : mode < 0 ? "§a" + tr("continuous") : "§e" + tr("interval");
    }

    /** Cycles off -> continuous -> every N ticks; N is the interval remembered for that action. */
    private static void stepAction(BotManager.Bot bot, int action, int delta) {
        int current = bot.actionMode(action);
        int[] modes = {0, -1, bot.actionInterval(action)};
        int index = current == 0 ? 0 : current < 0 ? 1 : 2;
        BotManager.setAction(bot, action, modes[Math.floorMod(index + delta, modes.length)]);
    }

    private String botStatus() {
        var player = this.bot.player();
        var hand = player.getMainHandItem();
        String item = hand.isEmpty() ? tr("empty_hand") : hand.getHoverName().getString() + " ×" + hand.getCount();
        return tr("bot_status", decimal(player.getX()), decimal(player.getY()), decimal(player.getZ()),
                Math.round(net.minecraft.util.Mth.wrapDegrees(player.getYRot())), Math.round(player.getXRot()),
                decimal(player.getHealth()), item);
    }

    private static String decimal(double value) { return String.format(Locale.ROOT, "%.1f", value); }

    /**
     * A Litematica-style value button: left click steps forward, right click steps back. Works
     * while stopped; before the simulation world exists the choice is applied when it is created.
     */
    private int cycleButton(int x, int y, int width, String key, String[] values, SimulationManager.WorldSetting setting) {
        var manager = SimulationManager.getInstance();
        Consumer<Integer> step = delta -> {
            int current = manager.worldSetting(this.placement, setting);
            int next = current < 0 ? (delta > 0 ? 0 : values.length - 1) : Math.floorMod(current + delta, values.length);
            manager.setWorldSetting(this.placement, setting, next);
        };
        placementButton(x, y, width, dynamic(() -> {
            int current = manager.worldSetting(this.placement, setting);
            return labeled(tr(key), current < 0 ? "§7" + tr("default") : "§e" + tr(values[current]));
        }, () -> step.accept(1), () -> true, ""), () -> step.accept(-1));
        return y + BTN_H + GAP;
    }

    private static String labeled(String label, String value) {
        return tr("labeled", label, value);
    }

    private List<Entry> entries() {
        List<Entry> rows = new ArrayList<>();
        switch (this.page) {
            case PROJECTIONS -> {
                for (SchematicPlacement p : this.placements) {
                    rows.add(new Entry(p.getName(), p::getName, List.of(
                            control("configure", () -> this.mc.gui.setScreen(new PlacementConfigScreen(p, this))),
                            simulationToggle(p))));
                }
            }
            case GENERAL -> {
                rows.add(booleanSetting("tps_hud", SimulaticaConfigs.TPS_HUD));
                rows.add(booleanSetting("tps_multiline", SimulaticaConfigs.TPS_MULTILINE));
            }
            case PLACEMENT -> {
                for (BotManager.Bot bot : BotManager.botsOf(this.placement)) rows.add(botRow(this.placement, bot));
            }
        }
        return rows;
    }

    private Entry row(String key, Control... controls) {
        return new Entry(tr(key), () -> tr(key), List.of(controls));
    }

    private Control simulationToggle(SchematicPlacement placement) {
        var manager = SimulationManager.getInstance();
        return dynamic(() -> (manager.isSimulating(placement) ? "§a" : "§c")
                + tr(manager.isSimulating(placement) ? "running_toggle" : "stop"), () -> {
            if (manager.isSimulating(placement)) manager.stopSimulation(placement);
            else manager.startSimulation(placement);
        }, () -> placement.getSchematic() != null, "", tr("running_toggle"), tr("stop"));
    }

    private String disabledHint(Control control) {
        if (this.placement != null && control.hint().equals(tr("spawn_hint"))
                && SimulationManager.getInstance().isSimulating(this.placement)) return tr("simulation_initializing");
        return control.hint();
    }

    private String booleanText(boolean value) {
        return StringUtils.translate(value ? "malilib.gui.button.true" : "malilib.gui.button.false");
    }

    private Entry booleanSetting(String key, ConfigBoolean config) {
        return row(key, dynamic(() -> booleanText(config.getBooleanValue()), config::toggleBooleanValue,
                () -> true, "", booleanText(true), booleanText(false)),
                reset(config::resetToDefault, config::isModified));
    }

    private Control reset(Runnable run, BooleanSupplier modified) {
        String text = StringUtils.translate("malilib.gui.button.reset.caps");
        return new Control(() -> text, run,
                () -> !WorkshopManager.isActive() && modified.getAsBoolean(),
                "", this.font.width(text) + 12, true);
    }

    private void setTps(int target) {
        try { SimulationManager.getInstance().setTps(this.placement, target); }
        catch (java.io.IOException error) { SimulaticaClient.sendFeedback(tr("setting_failed", error.getMessage())); }
    }

    private Entry botRow(SchematicPlacement p, BotManager.Bot bot) {
        String[] modes = {tr("mode.survival"), tr("mode.creative"), tr("mode.adventure"), tr("mode.spectator")};
        List<Control> controls = List.of(
                dynamic(() -> tr("mode." + bot.player().gameMode.getGameModeForPlayer().getName()), () -> {
                    GameType[] types = {GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR};
                    bot.player().setGameMode(types[(bot.player().gameMode.getGameModeForPlayer().ordinal() + 1) % types.length]);
                }, () -> true, "", modes),
                control("configure", () -> this.mc.gui.setScreen(new BotConfigScreen(p, bot, this))),
                control("inventory", () -> this.mc.gui.setScreen(new BotInventoryScreen(bot.player(), this))),
                dynamic(() -> "§c" + tr("remove"), () -> BotManager.remove(p, bot), () -> true, "", tr("remove")));
        return new Entry(bot.name(), bot::name, controls, true);
    }

    private void spawnBot() {
        int index = BotManager.botsOf(this.placement).size() + 1;
        String name;
        List<String> names = BotManager.allBots().stream().map(BotManager.Bot::name).toList();
        do { name = "Bot" + index++; } while (names.contains(name));
        BotManager.Bot bot = BotManager.spawn(this.placement, name, GameType.CREATIVE);
        SimulaticaClient.sendFeedback(bot == null ? tr("spawn_hint") : tr("spawned", name));
    }

    private List<Object> currentDataStamp() {
        List<Object> stamp = new ArrayList<>();
        for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            stamp.add(p); stamp.add(p.getName()); stamp.addAll(BotManager.botsOf(p));
        }
        return stamp;
    }

    private void refreshToolbar() {
        for (int i = 0; i < this.toolbarButtons.size(); i++) {
            Control control = this.toolbarControls.get(i);
            this.toolbarButtons.get(i).setDisplayString(control.text().get());
            this.toolbarButtons.get(i).setEnabled(control.enabled().getAsBoolean());
        }
        syncNumberFields();
    }

    @Override
    public void tick() {
        if (this.bot != null && !BotManager.botsOf(this.placement).contains(this.bot)) {
            // Removed elsewhere, e.g. by stopping the simulation.
            closeGui(true);
            return;
        }
        List<Object> stamp = currentDataStamp();
        if (!stamp.equals(this.dataStamp)) initGui();
        refreshToolbar();
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        int available = this.width - 20 - PADDING - (this.modSwitchWidget == null ? 0 : 160);
        ctx.drawString(this.font, ellipsis(this.title, available), 20, 10, COLOR_TEXT);
    }

    @Override
    protected void drawScreenBackground(GuiContext ctx, int mouseX, int mouseY) {
        if (this.placement == null) super.drawScreenBackground(ctx, mouseX, mouseY);
        else ctx.fill(0, 0, this.width, this.height, 0x60000000);
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        if (this.placement != null) {
            if (this.bot != null) {
                for (int i = 0; i < ACTION_KEYS.length; i++) {
                    int rowY = this.botRowsTop + i * (BTN_H + GAP);
                    ctx.fill(PADDING, rowY, PADDING + this.botRowsWidth, rowY + BTN_H + GAP, (i & 1) == 0 ? 0x40202020 : 0x60303030);
                }
                ctx.drawString(this.font, ellipsis(botStatus(), this.width - 20 - PADDING), 20, 22, COLOR_DIM);
            }
            for (Label label : this.labels) ctx.drawString(this.font, label.text().get(), label.x(), label.y(), label.color());
            if (this.content == null) return;
            int bottom = this.content.posY() + this.content.totalHeight();
            ctx.enableScissor(PADDING, this.content.posY(), PADDING + this.content.width(), bottom);
            this.content.drawContents(ctx, mouseX, mouseY, partialTicks);
            ctx.disableScissor();
            if (this.content.getCurrentEntries().isEmpty())
                ctx.drawString(this.font, tr("no_bots"), PADDING + 4, this.content.posY() + 6, COLOR_DIM);
            return;
        }
        boolean covered = insideSuggest(mouseX, mouseY) || switcherAt(mouseX, mouseY);
        ctx.enableScissor(PADDING, this.contentTop, this.width - PADDING, this.contentTop + this.content.totalHeight());
        this.content.drawContents(ctx, covered ? -1 : mouseX, covered ? -1 : mouseY, partialTicks);
        ctx.disableScissor();
        if (this.content.getCurrentEntries().isEmpty()) {
            String empty = this.page == Page.PROJECTIONS ? "no_placements" : "no_matches";
            if (this.content.search.hasFilter()) empty = "no_matches";
            ctx.drawString(this.font, ellipsis(tr(empty), this.width - 2 * PADDING - 10), PADDING + 4, this.contentTop + 29, COLOR_DIM);
        }
        if (this.commandField != null) {
            this.commandField.extractRenderState(ctx.getGuiGraphics(), mouseX, mouseY, partialTicks);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTicks) {
        if (this.placement != null) {
            extractor.pose().pushMatrix();
            try {
                extractor.pose().scale(this.placementScale, this.placementScale);
                super.extractRenderState(extractor, (int) (mouseX / this.placementScale),
                        (int) (mouseY / this.placementScale), partialTicks);
            } finally { extractor.pose().popMatrix(); }
            return;
        }
        super.extractRenderState(extractor, mouseX, mouseY, partialTicks);
        if (suggestVisible()) drawSuggestionList(extractor, mouseX, mouseY);
    }

    @Override
    protected void drawHoveredWidget(GuiContext ctx, int mouseX, int mouseY) {
        if (this.content != null && (this.placement != null || (!insideSuggest(mouseX, mouseY) && !switcherAt(mouseX, mouseY))))
            this.content.drawHover(ctx, mouseX, mouseY);
        for (int i = 0; i < this.toolbarButtons.size(); i++) {
            ButtonGeneric button = this.toolbarButtons.get(i);
            Control control = this.toolbarControls.get(i);
            if (!control.enabled().getAsBoolean() && !control.hint().isEmpty()
                    && mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
                    && mouseY >= button.getY() && mouseY < button.getY() + button.getHeight())
                ctx.renderTooltip(this.font, Component.literal(disabledHint(control)), mouseX, mouseY);
        }
        super.drawHoveredWidget(ctx, mouseX, mouseY);
    }

    private boolean switcherAt(double x, double y) {
        return this.modSwitchWidget != null && this.modSwitchWidget.isMouseOver((int) x, (int) y);
    }

    private String ellipsis(String text, int width) {
        if (this.font.width(text) <= width) return text;
        return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("…"))) + "…";
    }

    private void sendCommand() {
        String command = this.commandField.getValue().trim();
        if (!command.isEmpty()) { SimulationCommands.execute(command); this.commandField.setValue(""); clearTabState(); }
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (this.placement != null) {
            click = placementClick(click);
            MouseButtonEvent at = click;
            if (this.numberFields.stream().anyMatch(n -> n.field().isFocused() && !n.field().isMouseOver(at.x(), at.y()))) commitInputs();
            if (super.onMouseClicked(click, doubleClick)) return true;
            return this.content != null && this.content.onMouseClicked(click, doubleClick);
        }
        if (switcherAt(click.x(), click.y())) {
            clearTabState();
            if (this.commandField != null) this.commandField.setFocused(false);
            this.content.search.unfocus();
            return this.modSwitchWidget.onMouseClicked(click, doubleClick);
        }
        if (click.input() == 0 && clickSuggestion((int) click.x(), (int) click.y())) return true;
        if (this.commandField != null) {
            boolean commandClick = this.commandField.mouseClicked(click, doubleClick);
            if (commandClick) { clearTabState(); this.content.search.unfocus(); return true; }
            clearTabState();
        }
        if (super.onMouseClicked(click, doubleClick)) return true;
        return this.content.onMouseClicked(click, doubleClick);
    }

    private MouseButtonEvent placementClick(MouseButtonEvent click) {
        return new MouseButtonEvent(click.x() / this.placementScale, click.y() / this.placementScale, click.buttonInfo());
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click) {
        if (this.placement != null) click = placementClick(click);
        if (this.content != null) this.content.onMouseReleased(click);
        return super.onMouseReleased(click);
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragX, double dragY) {
        if (this.placement == null) return super.onMouseDragged(click, dragX, dragY);
        // malilib forwards drags to every text field and focuses it; the release of the click that
        // opened this screen would otherwise select a number field. Only an active edit may drag-select.
        if (!editingNumber()) return false;
        return super.onMouseDragged(placementClick(click), dragX / this.placementScale, dragY / this.placementScale);
    }

    @Override
    public boolean onMouseScrolled(double x, double y, double horizontal, double vertical) {
        if (this.placement != null) {
            return this.content != null && this.content.onMouseScrolled(x / this.placementScale, y / this.placementScale, horizontal, vertical);
        }
        if (switcherAt(x, y)) {
            this.modSwitchWidget.onMouseScrolled(x, y, horizontal, vertical);
            return true;
        }
        if (insideSuggest(x, y)) {
            this.tabScroll = Math.max(0, Math.min(suggestMaxScroll(), this.tabScroll - (int) Math.signum(vertical)));
            return true;
        }
        return this.content.onMouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean onKeyTyped(KeyEvent event) {
        if (this.commandField != null && this.commandField.isFocused()) {
            if (event.key() == InputConstants.KEY_TAB) { onTabComplete(); return true; }
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) { sendCommand(); return true; }
            if (event.key() == InputConstants.KEY_ESCAPE) {
                if (suggestVisible()) { clearTabState(); return true; }
                this.commandField.setFocused(false);
            } else { clearTabState(); return this.commandField.keyPressed(event); }
        }
        if (this.placement != null) {
            if (event.key() == InputConstants.KEY_ESCAPE) { commitInputs(); closeGui(true); return true; }
            if (editingNumber() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
                commitInputs(); return true;
            }
            return super.onKeyTyped(event) || (this.content != null && this.content.onKeyTyped(event));
        }
        if (event.key() != InputConstants.KEY_ESCAPE && super.onKeyTyped(event)) return true;
        if (this.content.onKeyTyped(event)) return true;
        return event.key() == InputConstants.KEY_ESCAPE && super.onKeyTyped(event);
    }

    @Override
    public boolean onCharTyped(CharacterEvent event) {
        clearTabState();
        if (this.commandField != null && this.commandField.isFocused()) return this.commandField.charTyped(event);
        if (this.placement != null) return super.onCharTyped(event);
        return super.onCharTyped(event) || this.content.onCharTyped(event);
    }

    /** The native list owns filtering, wheel scrolling, and scrollbar dragging. */
    private final class ControlsList extends WidgetListBase<Entry, ControlRow> {
        private final List<Entry> entries;
        private final Search search;
        private final int labelWidth;
        private final int height;

        ControlsList(int x, int y, int width, int height, List<Entry> entries) {
            this(x, y, width, height, entries, true);
        }
        ControlsList(int x, int y, int width, int height, List<Entry> entries, boolean searchable) {
            this(x, y, width, height, entries, searchable, 0);
        }
        /** {@code reserved} keeps room right of the search bar for toolbar buttons. */
        ControlsList(int x, int y, int width, int height, List<Entry> entries, boolean searchable, int reserved) {
            super(x, y, width, height, null);
            this.entries = entries;
            this.height = height;
            this.labelWidth = Math.min(searchable ? 180 : 110, Math.max(70, (width - 20) / 3));
            this.search = new Search(x + 2, y + 4, width - 14 - (reserved == 0 ? 0 : reserved + 8));
            this.widgetSearchBar = searchable ? this.search : null;
            this.browserEntriesOffsetY = !searchable ? 0 : reserved == 0 ? 17 : 23;
            this.allowKeyboardNavigation = true;
        }

        int totalHeight() { return this.height; }
        void restoreScroll(int scroll) {
            getScrollbar().setValue(scroll);
            reCreateListEntryWidgets();
        }
        int posY() { return this.posY; }
        int width() { return this.browserWidth; }
        void restore(ListState state) {
            this.search.restore(state.search(), state.open());
            refreshEntries();
            getScrollbar().setValue(state.scroll());
            reCreateListEntryWidgets();
        }
        @Override protected Collection<Entry> getAllEntries() { return this.entries; }
        @Override protected List<String> getEntryStringsForFilter(Entry entry) { return List.of(entry.search().toLowerCase(Locale.ROOT)); }
        @Override protected int getBrowserEntryHeightFor(Entry entry) {
            return rowHeight(entry, this.browserEntryWidth, this.labelWidth);
        }
        @Override protected ControlRow createListEntryWidget(int x, int y, int index, boolean odd, Entry entry) {
            return new ControlRow(x, y, this.browserEntryWidth, getBrowserEntryHeightFor(entry), entry, index, this.labelWidth);
        }
        @Override public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick) {
            if (click.y() < this.posY || click.y() >= this.posY + this.browserHeight || click.x() < this.posX || click.x() >= this.posX + this.browserWidth) return false;
            return super.onMouseClicked(click, doubleClick);
        }
        @Override public boolean onCharTyped(CharacterEvent event) {
            return this.widgetSearchBar != null && super.onCharTyped(event);
        }
        @Override public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
            if (this.widgetSearchBar != null || this.listWidgets.size() < this.listContents.size()) {
                super.drawContents(ctx, mouseX, mouseY, partialTicks);
                return;
            }
            this.hoveredWidget = null;
            for (ControlRow row : this.listWidgets) {
                row.render(ctx, mouseX, mouseY, false);
                if (row.isMouseOver(mouseX, mouseY)) this.hoveredWidget = row;
            }
        }
        void drawHover(GuiContext ctx, int x, int y) {
            if (this.hoveredWidget != null) this.hoveredWidget.postRenderHovered(ctx, x, y, false);
        }
    }

    private static final class Search extends WidgetSearchBar {
        Search(int x, int y, int width) { super(x, y, width, 14, 0, MaLiLibIcons.SEARCH, LeftRight.LEFT); }
        String text() { return this.searchBox.getValue(); }
        void unfocus() { this.searchBox.setFocused(false); }
        void restore(String text, boolean open) { this.searchBox.setValue(text); setSearchOpen(open); unfocus(); }
    }

    private record RowLayout(int start, int top, int columns, int buttonWidth, int resetWidth, int rows) {}

    private RowLayout rowLayout(Entry entry, int width, int labelWidth) {
        int resetWidth = entry.controls().stream().filter(Control::reset).mapToInt(Control::width).sum();
        int count = (int) entry.controls().stream().filter(c -> !c.reset()).count();
        int minWidth = entry.controls().stream().filter(c -> !c.reset()).mapToInt(Control::width).max().orElse(20);
        if (this.page == Page.PROJECTIONS) {
            int buttonWidth = Math.max(52, minWidth);
            int start = Math.max(4, width - 2 * buttonWidth - GAP - 24);
            return new RowLayout(start, 1, 2, buttonWidth, 0, 1);
        }
        int compactWidth = entry.controls().stream().mapToInt(Control::width).sum() + Math.max(0, count - 1) * GAP;
        if (entry.compact() && compactWidth + 48 <= width)
            return new RowLayout(width - compactWidth - 4, 1, count, -1, 0, 1);
        if (count == 0) return new RowLayout(4, 1, 1, 1, 0, 1);
        if (entry.label().get().isEmpty()) labelWidth = 4;
        boolean stacked = width - labelWidth - 4 < minWidth + (resetWidth == 0 ? 0 : resetWidth + GAP);
        int start = stacked ? 4 : labelWidth;
        int available = Math.max(1, Math.min(this.placement != null && count > 1 ? width : getConfigWidth(),
                width - start - 4 - (resetWidth == 0 ? 0 : resetWidth + GAP)));
        int columns = Math.max(1, Math.min(count, (available + GAP) / (minWidth + GAP)));
        int buttonWidth = Math.max(1, (available - (columns - 1) * GAP) / columns);
        return new RowLayout(start, stacked ? 17 : 1, columns, buttonWidth, resetWidth,
                (count + columns - 1) / columns);
    }

    private int rowHeight(Entry entry, int width, int labelWidth) {
        RowLayout layout = rowLayout(entry, width, labelWidth);
        return layout.top() + layout.rows() * (BTN_H + GAP) - GAP + 1;
    }

    private final class ControlRow extends WidgetListEntryBase<Entry> {
        private final List<ButtonGeneric> buttons = new ArrayList<>();
        private final int textWidth;
        ControlRow(int x, int y, int width, int height, Entry entry, int index, int labelWidth) {
            super(x, y, width, height, entry, index);
            RowLayout layout = rowLayout(entry, width, labelWidth);
            this.textWidth = layout.start() == 4 ? width - 8 : layout.start() - 8;
            int indexInRow = 0;
            for (Control control : entry.controls()) {
                int w = control.reset() ? layout.resetWidth() : layout.buttonWidth() < 0 ? control.width() : layout.buttonWidth();
                int bx = control.reset() ? x + layout.start() + layout.columns() * (layout.buttonWidth() + GAP)
                        : x + layout.start() + (indexInRow % layout.columns()) * (w + GAP);
                if (layout.buttonWidth() < 0) bx = x + layout.start()
                        + entry.controls().subList(0, indexInRow).stream().mapToInt(c -> c.width() + GAP).sum();
                int by = y + layout.top() + (control.reset() ? 0 : indexInRow / layout.columns()) * (BTN_H + GAP);
                ButtonGeneric button = new ButtonGeneric(bx, by, w, BTN_H, control.text().get());
                this.buttons.add(addButton(button, (b, mouseButton) -> {
                    if (mouseButton == 0 && control.enabled().getAsBoolean()) {
                        control.run().run();
                        refreshButtons();
                    }
                }));
                if (!control.reset()) indexInRow++;
            }
        }
        private void refreshButtons() {
            for (int i = 0; i < this.buttons.size(); i++) {
                Control control = this.entry.controls().get(i);
                this.buttons.get(i).setDisplayString(control.text().get());
                this.buttons.get(i).setEnabled(control.enabled().getAsBoolean());
            }
        }
        @Override public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
            ctx.fill(this.x, this.y, this.x + this.width, this.y + this.height, (this.listIndex & 1) == 0 ? 0x40202020 : 0x60303030);
            refreshButtons();
            ctx.drawString(SimulaticaMenuScreen.this.font, ellipsis(this.entry.label().get(), this.textWidth), this.x + 4, this.y + 6, COLOR_TEXT);
            super.render(ctx, mouseX, mouseY, selected);
        }
        @Override public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
            super.postRenderHovered(ctx, mouseX, mouseY, selected);
            for (int i = 0; i < this.buttons.size(); i++) {
                ButtonGeneric button = this.buttons.get(i);
                Control control = this.entry.controls().get(i);
                if (!control.enabled().getAsBoolean() && !control.hint().isEmpty()
                        && mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
                        && mouseY >= button.getY() && mouseY < button.getY() + button.getHeight())
                    ctx.renderTooltip(SimulaticaMenuScreen.this.font, Component.literal(disabledHint(control)), mouseX, mouseY);
            }
            if (SimulaticaMenuScreen.this.font.width(this.entry.label().get()) > this.textWidth
                    && mouseX < this.x + this.textWidth + 4 && mouseY < this.y + 16)
                ctx.renderTooltip(SimulaticaMenuScreen.this.font, Component.literal(this.entry.label().get()), mouseX, mouseY);
        }
    }

    private void onTabComplete() {
        int request = ++this.completionRequest;
        GuiTextFieldGeneric field = this.commandField;
        String value = field.getValue();
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
                    if (request != this.completionRequest || field != this.commandField || this.mc.gui.screen() != this) return;
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
        return this.commandField.getY() - 4;
    }

    /** 候选列表当前是否显示。 */
    private boolean suggestVisible() {
        return !this.tabCandidates.isEmpty() && this.commandField != null && this.commandField.isFocused();
    }

    /** 候选列表画几行（最多 {@link #SUGGEST_MAX} 行，行数固定，滚轮滚动时高度不跳）。 */
    private int suggestRows() {
        return Math.min(this.tabCandidates.size(), Math.min(SUGGEST_MAX, Math.max(1, (suggestionBottom() - 28) / SUGGEST_ROW)));
    }

    private int suggestMaxScroll() {
        return Math.max(0, this.tabCandidates.size() - suggestRows());
    }

    private int suggestTop() {
        return suggestionBottom() - suggestRows() * SUGGEST_ROW - 2;
    }

    private int suggestWidth() {
        return Math.min(this.commandField.getWidth(), 360);
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
        } else if (this.tabCursor >= this.tabScroll + suggestRows()) {
            this.tabScroll = this.tabCursor - suggestRows() + 1;
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
            int thumb = Math.max(12, track * suggestRows() / this.tabCandidates.size());
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
        ++this.completionRequest;
        this.tabCandidates = List.of();
        this.tabBase = null;
        this.tabSuffix = "";
        this.tabApplied = null;
        this.tabScroll = 0;
        this.tabCursor = 0;
    }

}
