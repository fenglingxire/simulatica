package ml.pypals.simulatica;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * [SIMULATICA-新增] 假人背包界面（含玩家背包，参考 GCA / 原版容器界面）。
 *
 * <p>上半部分为假人背包（快捷栏、主背包、盔甲、副手，共 41 格），下半部分为玩家背包（主背包
 * 27 格 + 快捷栏 9 格），支持物品在玩家背包与假人背包之间用鼠标拖拽/点击转移。</p>
 *
 * <p>假人对象在本客户端进程内（模拟世界），可绕过网络直接读写其背包。玩家背包在单机下读写
 * integrated server 的权威副本（改动即时同步），多人下回退到本地副本（客户端改动，已知限制）。</p>
 */
public final class BotInventoryScreen extends Screen {

    private static final int SLOT = 18;
    private static final int COLS = 9;

    private enum Region { BOT, PLAYER }

    private record SlotRef(Region region, int index) {
    }

    private final ServerPlayer bot;
    private final Screen parent;
    private ItemStack carried = ItemStack.EMPTY;

    private int guiLeft;
    private int guiTop;
    private int playerTop;

    public BotInventoryScreen(ServerPlayer bot) {
        this(bot, null);
    }

    public BotInventoryScreen(ServerPlayer bot, Screen parent) {
        super(Component.literal("假人背包 - " + bot.getName().getString()));
        this.bot = bot;
        this.parent = parent;
    }

    private Inventory botInventory() {
        return this.bot.getInventory();
    }

    /** 玩家背包：单机用 integrated server 的权威副本（改动即时同步），多人回退本地副本。 */
    private Inventory playerInventory() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && mc.player != null) {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (serverPlayer != null) {
                return serverPlayer.getInventory();
            }
        }
        return mc.player.getInventory();
    }

    @Override
    protected void init() {
        this.guiLeft = (this.width - COLS * SLOT) / 2;
        // 假人背包 4 行 + 玩家背包 4 行 + 间隔，整体垂直居中
        this.guiTop = this.height / 2 - 4 * SLOT - 16;
        this.playerTop = this.guiTop + 4 * SLOT + 24;
    }

    // ------------------------------------------------------------------
    // 假人背包槽坐标（0–8 快捷栏底行，9–35 主背包上三行，36–39 盔甲，40 副手）
    // ------------------------------------------------------------------
    private int botSlotX(int slot) {
        if (slot < 36) {
            return this.guiLeft + (slot % COLS) * SLOT;
        }
        return this.guiLeft - SLOT - 4;
    }

    private int botSlotY(int slot) {
        if (slot < 9) {
            return this.guiTop + 3 * SLOT + 4;
        }
        if (slot < 36) {
            return this.guiTop + ((slot - 9) / COLS) * SLOT;
        }
        if (slot == 40) {
            return this.guiTop - SLOT - 4;
        }
        return this.guiTop + (3 - (slot - 36)) * SLOT;
    }

    // ------------------------------------------------------------------
    // 玩家背包槽坐标（0–8 快捷栏底行，9–35 主背包上三行）
    // ------------------------------------------------------------------
    private int playerSlotX(int slot) {
        return this.guiLeft + (slot % COLS) * SLOT;
    }

    private int playerSlotY(int slot) {
        if (slot < 9) {
            return this.playerTop + 3 * SLOT + 4;
        }
        return this.playerTop + ((slot - 9) / COLS) * SLOT;
    }

    private SlotRef slotAt(double mouseX, double mouseY) {
        for (int i = 0; i <= 40; i++) {
            if (hit(botSlotX(i), botSlotY(i), mouseX, mouseY)) {
                return new SlotRef(Region.BOT, i);
            }
        }
        for (int i = 0; i < 36; i++) {
            if (hit(playerSlotX(i), playerSlotY(i), mouseX, mouseY)) {
                return new SlotRef(Region.PLAYER, i);
            }
        }
        return null;
    }

    private static boolean hit(int x, int y, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean hasActiveButton) {
        SlotRef ref = slotAt(event.x(), event.y());
        if (ref == null) {
            return super.mouseClicked(event, hasActiveButton);
        }

        Inventory inv = ref.region() == Region.BOT ? botInventory() : playerInventory();
        ItemStack inSlot = inv.getItem(ref.index());

        if (event.button() == 0) {
            // 左键：整组交换 / 合并
            if (this.carried.isEmpty()) {
                if (!inSlot.isEmpty()) {
                    this.carried = inSlot.copy();
                    inv.setItem(ref.index(), ItemStack.EMPTY);
                }
            } else if (inSlot.isEmpty()) {
                inv.setItem(ref.index(), this.carried);
                this.carried = ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameComponents(this.carried, inSlot)) {
                int space = inSlot.getMaxStackSize() - inSlot.getCount();
                int move = Math.min(space, this.carried.getCount());
                if (move > 0) {
                    inSlot.grow(move);
                    this.carried.shrink(move);
                }
            } else {
                inv.setItem(ref.index(), this.carried);
                this.carried = inSlot;
            }
        } else if (event.button() == 1) {
            // 右键：半组拾取 / 单个放置
            if (this.carried.isEmpty()) {
                if (!inSlot.isEmpty()) {
                    int half = inSlot.getCount() / 2;
                    this.carried = inSlot.split(half);
                    if (inSlot.isEmpty()) {
                        inv.setItem(ref.index(), ItemStack.EMPTY);
                    }
                }
            } else if (inSlot.isEmpty()) {
                inv.setItem(ref.index(), this.carried.split(1));
            } else if (ItemStack.isSameItemSameComponents(this.carried, inSlot)
                    && inSlot.getCount() < inSlot.getMaxStackSize()) {
                inSlot.grow(1);
                this.carried.shrink(1);
            }
        }
        return true;
    }

    @Override
    public void onClose() {
        // 关闭时把光标携带的物品放回（优先玩家背包，其次假人背包），避免丢失
        if (!this.carried.isEmpty()) {
            ItemStack leftover = this.carried;
            leftover = putBack(playerInventory(), leftover);
            if (!leftover.isEmpty()) {
                leftover = putBack(botInventory(), leftover);
            }
            if (!leftover.isEmpty()) {
                this.bot.drop(leftover, false);
            }
            this.carried = ItemStack.EMPTY;
        }
        Minecraft.getInstance().gui.setScreen(this.parent);
    }

    private static ItemStack putBack(Inventory inv, ItemStack stack) {
        ItemStack leftover = stack;
        for (int i = 0; i < inv.getContainerSize() && !leftover.isEmpty(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty()) {
                inv.setItem(i, leftover);
                leftover = ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameComponents(slot, leftover)
                    && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(slot.getMaxStackSize() - slot.getCount(), leftover.getCount());
                slot.grow(move);
                leftover.shrink(move);
            }
        }
        return leftover;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        this.extractTransparentBackground(extractor);
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        extractor.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 12, 0xFFFFFFFF);

        SlotRef hovered = slotAt(mouseX, mouseY);

        // 假人背包
        Inventory botInv = botInventory();
        for (int slot = 0; slot <= 40; slot++) {
            drawSlot(extractor, botSlotX(slot), botSlotY(slot), botInv.getItem(slot), hovered, Region.BOT, slot);
        }

        // 玩家背包
        Inventory playerInv = playerInventory();
        for (int slot = 0; slot < 36; slot++) {
            drawSlot(extractor, playerSlotX(slot), playerSlotY(slot), playerInv.getItem(slot), hovered, Region.PLAYER, slot);
        }

        // 光标携带的物品
        if (!this.carried.isEmpty()) {
            extractor.item(this.carried, mouseX - 8, mouseY - 8);
            extractor.itemDecorations(this.font, this.carried, mouseX - 8, mouseY - 8);
        }

        // 悬停提示
        if (hovered != null) {
            Inventory hoverInv = hovered.region() == Region.BOT ? botInv : playerInv;
            ItemStack hoverStack = hoverInv.getItem(hovered.index());
            if (!hoverStack.isEmpty()) {
                extractor.setTooltipForNextFrame(this.font, hoverStack, mouseX, mouseY);
            }
        }
    }

    private void drawSlot(GuiGraphicsExtractor extractor, int x, int y, ItemStack stack,
                          SlotRef hovered, Region region, int index) {
        extractor.fill(x, y, x + SLOT, y + SLOT, 0xFF373737);
        extractor.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFF8B8B8B);

        if (!stack.isEmpty()) {
            extractor.item(stack, x + 1, y + 1);
            extractor.itemDecorations(this.font, stack, x + 1, y + 1);
        }

        if (hovered != null && hovered.region() == region && hovered.index() == index) {
            extractor.fill(x, y, x + SLOT, y + 1, 0xFFFFFFFF);
            extractor.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, 0xFFFFFFFF);
            extractor.fill(x, y, x + 1, y + SLOT, 0xFFFFFFFF);
            extractor.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
