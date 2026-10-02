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

/** malilib-style simulation controls; a placement-scoped child reuses the bot rows. */
public class SimulaticaMenuScreen extends GuiConfigsBase {
    private static final int PADDING = 10;
    private static final int GAP = 4;
    private static final int BTN_H = 20;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int COLOR_TRACK = 0x33000000;
    private static final int COLOR_THUMB = 0xCC888888;
    private static final int[] INTERVALS = {0, -1, 1, 2, 5, 10, 20};

    private enum Page { PROJECTIONS, GENERAL, WORLD, COUNTERS, BOTS, ACTIONS }
    private record ListState(String search, boolean open, int scroll) {}
    private record Control(Supplier<String> text, Runnable run, BooleanSupplier enabled,
                           String hint, int width) {}
    private record Entry(String search, Supplier<String> label, List<Control> controls) {}

    private final SchematicPlacement placement;
    private Page page;
    private final EnumMap<Page, ListState> listStates = new EnumMap<>(Page.class);
    private final List<SchematicPlacement> placements = new ArrayList<>();
    private final List<ButtonGeneric> toolbarButtons = new ArrayList<>();
    private final List<Control> toolbarControls = new ArrayList<>();
    private ControlsList content;
    private List<Object> dataStamp = List.of();
    private int contentTop;
    private int flowX;
    private int flowY;
    private final int[] intervals = {0, 0, 0};
    private final boolean[] movement = {false, false, false};

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
        super(10, 60, Simulatica.MOD_ID, parent, "simulatica.ui.title");
        this.placement = placement;
        this.page = placement == null ? Page.PROJECTIONS : Page.BOTS;
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
        } else {
            // malilib matches factories by exact screen class. Reuse its registered main
            // screen's native switcher without registering the child as another mod.
            SimulaticaMenuScreen main = new SimulaticaMenuScreen();
            main.buildConfigSwitcher();
            this.modSwitchWidget = main.modSwitchWidget;
            if (this.modSwitchWidget != null) addWidget(this.modSwitchWidget);
        }
    }

    private void rememberList() {
        if (this.content != null) {
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
        rememberList();
        String command = this.commandField == null ? "" : this.commandField.getValue();
        int cursor = this.commandField == null ? 0 : this.commandField.getCursorPosition();
        boolean focused = this.commandField != null && this.commandField.isFocused();
        clearTabState();
        super.initGui();
        this.toolbarButtons.clear();
        this.toolbarControls.clear();
        this.placements.clear();
        this.placements.addAll(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
        this.title = this.placement == null ? tr("title") : tr("placement_title", this.placement.getName());
        this.flowX = PADDING;
        this.flowY = 28;
        Page[] pages = this.placement == null
                ? new Page[]{Page.PROJECTIONS, Page.GENERAL, Page.WORLD, Page.COUNTERS, Page.BOTS}
                : new Page[]{Page.BOTS, Page.ACTIONS};
        for (Page next : pages) {
            Control tab = control("tab." + next.name().toLowerCase(Locale.ROOT), () -> selectPage(next));
            ButtonGeneric button = flowButton(tab);
            button.setEnabled(this.page != next);
        }
        this.flowX = PADDING;
        this.flowY += BTN_H + 6;
        buildToolbar();
        this.contentTop = this.flowY + (this.toolbarButtons.isEmpty() ? 0 : BTN_H + 4);
        int bottom = this.height - (this.placement == null ? 32 : 10);
        this.content = new ControlsList(PADDING, this.contentTop, this.width - 2 * PADDING,
                Math.max(24, bottom - this.contentTop), entries());
        this.content.initGui();
        ListState saved = this.listStates.get(this.page);
        if (saved != null) this.content.restore(saved);
        this.dataStamp = currentDataStamp();
        if (this.placement == null) {
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
    }

    private Control control(String key, Runnable run) {
        return dynamic(() -> tr(key), run, () -> true, "", tr(key));
    }

    private Control dynamic(Supplier<String> text, Runnable run, BooleanSupplier enabled, String hint, String... values) {
        int width = this.font.width(text.get());
        for (String value : values) width = Math.max(width, this.font.width(value));
        return new Control(text, run, () -> !ml.pypals.simulatica.workshop.WorkshopManager.isActive()
                && enabled.getAsBoolean(), hint, width + 12);
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
        if (!control.hint().isEmpty()) button.setHoverStrings(control.hint());
        addButton(button, (b, mouseButton) -> { if (mouseButton == 0 && control.enabled().getAsBoolean()) control.run().run(); });
        this.flowX += width + GAP;
        return button;
    }

    private void toolbar(Control control) {
        this.toolbarButtons.add(flowButton(control));
        this.toolbarControls.add(control);
    }

    private void buildToolbar() {
        if (this.page == Page.PROJECTIONS) {
            toolbar(control("start_all", () -> this.placements.forEach(SimulationManager.getInstance()::startSimulation)));
            toolbar(control("stop_all", () -> SimulationManager.getInstance().stopAll()));
        } else if (this.page == Page.BOTS) {
            if (this.placement != null) {
                toolbar(dynamic(() -> tr("spawn"), this::spawnBot,
                        () -> CarpetIntegration.isLoaded() && SimulationManager.getInstance().getSimulations(this.placement) != null
                                && !SimulationManager.getInstance().getSimulations(this.placement).isEmpty(),
                        tr("spawn_hint")));
            }
            toolbar(botControl("remove_all", () -> {
                if (this.placement == null) BotManager.removeAllGlobally(); else BotManager.removeAll(this.placement);
            }));
            toolbar(botControl("teleport_all", () -> {
                if (this.placement == null) BotManager.teleportAllToPlayer();
                else BotManager.botsOf(this.placement).forEach(BotManager::teleportToPlayer);
            }));
            toolbar(botControl("stop_actions", this::stopActions));
        }
    }

    private List<Entry> entries() {
        List<Entry> rows = new ArrayList<>();
        switch (this.page) {
            case PROJECTIONS -> {
                for (SchematicPlacement p : this.placements) {
                    rows.add(new Entry(p.getName(), () -> placementStatus(p) + "  " + p.getName(), List.of(
                            dynamic(() -> tr(SimulationManager.getInstance().isSimulating(p) ? "stop" : "start"), () -> {
                                if (SimulationManager.getInstance().isSimulating(p)) SimulationManager.getInstance().stopSimulation(p);
                                else SimulationManager.getInstance().startSimulation(p);
                            }, () -> p.getSchematic() != null, "", tr("start"), tr("stop")),
                            botControl("tab.bots", () -> this.mc.gui.setScreen(new PlacementConfigScreen(p, this))))));
                }
            }
            case GENERAL -> {
                rows.add(row("tps_hud", dynamic(() -> tr(SimulaticaConfigs.TPS_HUD.getBooleanValue() ? "on" : "off"),
                        () -> SimulaticaConfigs.TPS_HUD.setBooleanValue(!SimulaticaConfigs.TPS_HUD.getBooleanValue()),
                        () -> true, "", tr("on"), tr("off"))));
                rows.add(row("tps_multiline", dynamic(() -> tr(SimulaticaConfigs.TPS_MULTILINE.getBooleanValue() ? "on" : "off"),
                        () -> SimulaticaConfigs.TPS_MULTILINE.setBooleanValue(!SimulaticaConfigs.TPS_MULTILINE.getBooleanValue()),
                        () -> true, "", tr("on"), tr("off"))));
                rows.add(row("absorption", dynamic(() -> tr(SimulationManager.getInstance().isItemAbsorption() ? "on" : "off"),
                        () -> SimulationManager.getInstance().setItemAbsorption(null), () -> true, "", tr("on"), tr("off"))));
                rows.add(row("purge", control("clear", () -> {
                    int removed = SimulationManager.getInstance().purgeEscapedEntities();
                    SimulaticaClient.sendFeedback(tr("purged", removed));
                })));
            }
            case WORLD -> {
                rows.add(row("time", command("day", "/time set day"), command("noon", "/time set noon"),
                        command("night", "/time set night"), command("midnight", "/time set midnight")));
                rows.add(row("weather", command("clear_weather", "/weather clear"), command("rain", "/weather rain"), command("thunder", "/weather thunder")));
                rows.add(row("difficulty", command("peaceful", "/difficulty peaceful"), command("easy", "/difficulty easy"),
                        command("normal", "/difficulty normal"), command("hard", "/difficulty hard")));
            }
            case COUNTERS -> {
                rows.add(row("counter_values", control("view", () -> HopperCounter.formatAll().forEach(SimulaticaClient::sendFeedback))));
                rows.add(row("counter_reset", control("reset", () -> { HopperCounter.resetAll(); SimulaticaClient.sendFeedback(tr("counters_reset")); })));
            }
            case BOTS -> {
                for (SchematicPlacement p : this.placementsForBots()) {
                    for (BotManager.Bot bot : BotManager.botsOf(p)) rows.add(botRow(p, bot));
                }
            }
            case ACTIONS -> {
                String[] keys = {"attack", "use", "jump"};
                String[] types = {"ATTACK", "USE", "JUMP"};
                for (int i = 0; i < keys.length; i++) {
                    int slot = i;
                    rows.add(row(keys[i], dynamic(() -> intervalText(this.intervals[slot]), () -> {
                        int index = 0;
                        while (index < INTERVALS.length - 1 && INTERVALS[index] != this.intervals[slot]) index++;
                        this.intervals[slot] = INTERVALS[(index + 1) % INTERVALS.length];
                        BotManager.actionAllInterval(this.placement, types[slot], this.intervals[slot]);
                    }, this::hasBots, "", tr("off"), tr("hold"), tr("ticks", 20))));
                }
                String[] moves = {"sneak", "sprint", "forward"};
                for (int i = 0; i < moves.length; i++) {
                    int slot = i;
                    rows.add(row(moves[i], dynamic(() -> tr(this.movement[slot] ? "on" : "off"), () -> {
                        this.movement[slot] = !this.movement[slot];
                        if (slot == 0) BotManager.setSneakingAll(this.placement, this.movement[slot]);
                        else if (slot == 1) BotManager.setSprintingAll(this.placement, this.movement[slot]);
                        else BotManager.setForwardAll(this.placement, this.movement[slot] ? 1 : 0);
                    }, this::hasBots, "", tr("on"), tr("off"))));
                }
                rows.add(row("single_actions", once("attack_once", "ATTACK"), once("use_once", "USE"), once("drop", "DROP_ITEM"), once("swap", "SWAP_HANDS")));
                rows.add(row("all_actions", dynamic(() -> tr("stop"), this::stopActions, this::hasBots, "")));
            }
        }
        return rows;
    }

    private Entry row(String key, Control... controls) {
        return new Entry(tr(key), () -> tr(key), List.of(controls));
    }

    private String placementStatus(SchematicPlacement p) {
        SimulationManager manager = SimulationManager.getInstance();
        if (!manager.isSimulating(p)) return tr("stopped");
        var bridges = manager.getSimulations(p);
        return tr(bridges == null || bridges.isEmpty() ? "pending" : "running");
    }

    private Control command(String key, String command) {
        return control(key, () -> SimulationCommands.execute(command));
    }

    private Control once(String key, String action) {
        return dynamic(() -> tr(key), () -> BotManager.actionAll(this.placement, action, false), this::hasBots, "");
    }

    private boolean hasBots() { return CarpetIntegration.isLoaded() && !BotManager.botsOf(this.placement).isEmpty(); }
    private String intervalText(int interval) { return interval == 0 ? tr("off") : interval < 0 ? tr("hold") : tr("ticks", interval); }

    private List<SchematicPlacement> placementsForBots() {
        return this.placement == null ? this.placements : List.of(this.placement);
    }

    private Entry botRow(SchematicPlacement p, BotManager.Bot bot) {
        String name = this.placement == null ? p.getName() + " / " + bot.name() : bot.name();
        List<Control> controls = List.of(
                control("inventory", () -> this.mc.gui.setScreen(new BotInventoryScreen(bot.player()))),
                control("teleport", () -> BotManager.teleportToPlayer(bot)),
                dynamic(() -> tr("mode", tr("mode." + bot.player().gameMode.getGameModeForPlayer().getName())), () -> {
                    GameType[] modes = {GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR};
                    bot.player().setGameMode(modes[(bot.player().gameMode.getGameModeForPlayer().ordinal() + 1) % modes.length]);
                }, () -> true, "", tr("mode", tr("mode.survival")), tr("mode", tr("mode.creative")),
                        tr("mode", tr("mode.adventure")), tr("mode", tr("mode.spectator"))),
                control("remove", () -> BotManager.remove(p, bot)));
        return new Entry(name, () -> name, controls);
    }

    private void spawnBot() {
        int index = BotManager.botsOf(this.placement).size() + 1;
        String name;
        List<String> names = BotManager.allBots().stream().map(BotManager.Bot::name).toList();
        do { name = "Bot" + index++; } while (names.contains(name));
        BotManager.Bot bot = BotManager.spawn(this.placement, name, GameType.CREATIVE);
        SimulaticaClient.sendFeedback(bot == null ? tr("spawn_hint") : tr("spawned", name));
    }

    private void stopActions() {
        if (this.placement == null) BotManager.stopAllActionsGlobally(); else BotManager.stopAllActions(this.placement);
        java.util.Arrays.fill(this.intervals, 0);
        java.util.Arrays.fill(this.movement, false);
    }

    private List<Object> currentDataStamp() {
        List<Object> stamp = new ArrayList<>();
        for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            stamp.add(p); stamp.add(p.getName()); stamp.addAll(BotManager.botsOf(p));
        }
        return stamp;
    }

    @Override
    public void tick() {
        List<Object> stamp = currentDataStamp();
        if (!stamp.equals(this.dataStamp)) initGui();
        for (int i = 0; i < this.toolbarButtons.size(); i++) this.toolbarButtons.get(i).setEnabled(this.toolbarControls.get(i).enabled().getAsBoolean());
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        int available = this.width - PADDING * 2 - (this.modSwitchWidget == null ? 0 : 160);
        ctx.drawString(this.font, ellipsis(this.title, available), PADDING, 10, COLOR_TEXT);
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        boolean covered = insideSuggest(mouseX, mouseY) || switcherAt(mouseX, mouseY);
        ctx.enableScissor(PADDING, this.contentTop, this.width - PADDING, this.contentTop + this.content.totalHeight());
        this.content.drawContents(ctx, covered ? -1 : mouseX, covered ? -1 : mouseY, partialTicks);
        ctx.disableScissor();
        if (this.content.getCurrentEntries().isEmpty()) {
            String empty = this.page == Page.PROJECTIONS ? "no_placements" : this.page == Page.BOTS ? "no_bots" : "no_matches";
            if (this.content.search.hasFilter()) empty = "no_matches";
            ctx.drawString(this.font, ellipsis(tr(empty), this.width - 2 * PADDING - 10), PADDING + 4, this.contentTop + 27, COLOR_DIM);
        }
        if (this.commandField != null) {
            this.commandField.extractRenderState(ctx.getGuiGraphics(), mouseX, mouseY, partialTicks);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTicks);
        if (suggestVisible()) drawSuggestionList(extractor, mouseX, mouseY);
    }

    @Override
    protected void drawHoveredWidget(GuiContext ctx, int mouseX, int mouseY) {
        if (!insideSuggest(mouseX, mouseY) && !switcherAt(mouseX, mouseY)) this.content.drawHover(ctx, mouseX, mouseY);
        for (int i = 0; i < this.toolbarButtons.size(); i++) {
            ButtonGeneric button = this.toolbarButtons.get(i);
            Control control = this.toolbarControls.get(i);
            if (!control.enabled().getAsBoolean() && !control.hint().isEmpty()
                    && mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
                    && mouseY >= button.getY() && mouseY < button.getY() + button.getHeight())
                ctx.renderTooltip(this.font, Component.literal(control.hint()), mouseX, mouseY);
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

    @Override
    public boolean onMouseReleased(MouseButtonEvent click) {
        this.content.onMouseReleased(click);
        return super.onMouseReleased(click);
    }

    @Override
    public boolean onMouseScrolled(double x, double y, double horizontal, double vertical) {
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
        if (event.key() == InputConstants.KEY_ESCAPE && this.placement != null) { closeGui(true); return true; }
        if (event.key() != InputConstants.KEY_ESCAPE && super.onKeyTyped(event)) return true;
        if (this.content.onKeyTyped(event)) return true;
        return event.key() == InputConstants.KEY_ESCAPE && super.onKeyTyped(event);
    }

    @Override
    public boolean onCharTyped(CharacterEvent event) {
        clearTabState();
        if (this.commandField != null && this.commandField.isFocused()) return this.commandField.charTyped(event);
        return super.onCharTyped(event) || this.content.onCharTyped(event);
    }

    /** The native list owns filtering, wheel scrolling, and scrollbar dragging. */
    private final class ControlsList extends WidgetListBase<Entry, ControlRow> {
        private final List<Entry> entries;
        private final Search search;
        private final int labelWidth;
        private final int height;

        ControlsList(int x, int y, int width, int height, List<Entry> entries) {
            super(x, y, width, height, null);
            this.entries = entries;
            this.height = height;
            int actionsWidth = entries.stream().mapToInt(e -> e.controls().stream().mapToInt(c -> c.width() + GAP).sum()).max().orElse(0);
            this.labelWidth = page == Page.PROJECTIONS || page == Page.BOTS
                    ? Math.max(70, width - 20 - actionsWidth) : Math.min(180, Math.max(70, (width - 20) / 3));
            this.search = new Search(x + 2, y + 4, width - 14);
            this.widgetSearchBar = this.search;
            this.browserEntriesOffsetY = 21;
            this.allowKeyboardNavigation = true;
        }

        int totalHeight() { return this.height; }
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
        void drawHover(GuiContext ctx, int x, int y) {
            if (this.hoveredWidget != null) this.hoveredWidget.postRenderHovered(ctx, x, y, false);
        }
    }

    private static final class Search extends WidgetSearchBar {
        Search(int x, int y, int width) { super(x, y, width, 16, 0, MaLiLibIcons.SEARCH, LeftRight.LEFT); }
        String text() { return this.searchBox.getValue(); }
        void unfocus() { this.searchBox.setFocused(false); }
        void restore(String text, boolean open) { this.searchBox.setValue(text); setSearchOpen(open); unfocus(); }
    }

    private int rowHeight(Entry entry, int width, int labelWidth) {
        int controlsWidth = entry.controls().stream().mapToInt(c -> c.width() + GAP).sum();
        boolean stacked = controlsWidth > width - labelWidth - GAP;
        int x = stacked ? 4 : labelWidth;
        int y = stacked ? 16 : 2;
        for (Control control : entry.controls()) {
            int w = Math.min(control.width(), width - 8);
            if (x > 4 && x + w > width - 4) { x = 4; y += BTN_H + GAP; }
            x += w + GAP;
        }
        return y + BTN_H + 3;
    }

    private final class ControlRow extends WidgetListEntryBase<Entry> {
        private final List<ButtonGeneric> buttons = new ArrayList<>();
        private final int textWidth;
        ControlRow(int x, int y, int width, int height, Entry entry, int index, int labelWidth) {
            super(x, y, width, height, entry, index);
            int controlsWidth = entry.controls().stream().mapToInt(c -> c.width() + GAP).sum();
            boolean stacked = controlsWidth > width - labelWidth - GAP;
            this.textWidth = stacked ? width - 8 : labelWidth - 8;
            int bx = stacked ? x + 4 : x + labelWidth;
            int by = stacked ? y + 16 : y + 2;
            for (Control control : entry.controls()) {
                int w = Math.min(control.width(), width - 8);
                if (bx > x + 4 && bx + w > x + width - 4) { bx = x + 4; by += BTN_H + GAP; }
                ButtonGeneric button = new ButtonGeneric(bx, by, w, BTN_H, control.text().get());
                if (!control.hint().isEmpty()) button.setHoverStrings(control.hint());
                this.buttons.add(addButton(button, (b, mouseButton) -> {
                    if (mouseButton == 0 && control.enabled().getAsBoolean()) control.run().run();
                }));
                bx += w + GAP;
            }
        }
        @Override public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
            ctx.fill(this.x, this.y, this.x + this.width, this.y + this.height, (this.listIndex & 1) == 0 ? 0x40202020 : 0x60303030);
            for (int i = 0; i < this.buttons.size(); i++) {
                Control control = this.entry.controls().get(i);
                this.buttons.get(i).setDisplayString(control.text().get());
                this.buttons.get(i).setEnabled(control.enabled().getAsBoolean());
            }
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
                    ctx.renderTooltip(SimulaticaMenuScreen.this.font, Component.literal(control.hint()), mouseX, mouseY);
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
