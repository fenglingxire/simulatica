package ml.pypals.simulatica.simulation.server;

import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.mixin.simulation.MenuScreensAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - 26.2 容器菜单与屏幕适配（配合 MenuScreensAccessor 反射创建）
 */
/**
 * Opens a simulated block's container GUI on the client.
 *
 * <p>Nothing in the vanilla path works here. {@code Player.openMenu} is a no-op outside
 * {@code ServerPlayer}, and the screen the client would normally build from
 * {@code ClientboundOpenScreenPacket} is a stub whose contents arrive by packet -- there is no
 * server to send them. The menu is built from the block's own {@link MenuProvider} instead, so its
 * slots are backed directly by the simulated container.</p>
 *
 * <p>Putting items into a machine costs nothing -- the player's inventory is restored when the menu
 * closes, matching how a simulated item use is already rolled back. Taking items out only works in
 * creative, where the player could spawn them anyway; there the changed slots are sent on as
 * creative-mode slot sets, without which the real server would just revert them.</p>
 */
public final class SimulationMenus {

    /** Arbitrary and fixed. No packet ever carries it; it only has to match what the screen sends. */
    private static final int CONTAINER_ID = 119;

    private static boolean interacting;

    @Nullable
    private static AbstractContainerMenu open;
    @Nullable
    private static ItemStack[] snapshot;

    private SimulationMenus() {}

    public static void beginInteraction() {
        interacting = true;
    }

    public static void endInteraction() {
        interacting = false;
    }

    public static boolean isInteracting() {
        return interacting && !ml.pypals.simulatica.workshop.WorkshopManager.isActive();
    }

    public static int containerId() {
        return CONTAINER_ID;
    }

    public static boolean isSimulated(@Nullable AbstractContainerMenu menu) {
        return menu != null && menu == open && !ml.pypals.simulatica.workshop.WorkshopManager.isActive();
    }

    public static boolean openFor(MenuProvider provider) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }

        try {
            AbstractContainerMenu menu = provider.createMenu(CONTAINER_ID, player.getInventory(), player);
            if (menu == null) {
                return false;
            }

            Screen screen = screenFor(menu, player.getInventory(), provider.getDisplayName());
            if (screen == null) {
                return false;
            }

            snapshot = copyOf(player.getInventory());
            open = menu;
            player.containerMenu = menu;
            Minecraft.getInstance().gui.setScreen(screen);
            return true;
        } catch (Exception e) {
            Simulatica.LOGGER.error("[Simulatica] Failed to open a simulated container", e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Screen screenFor(AbstractContainerMenu menu, Inventory inventory, Component title) {
        MenuType<?> type;
        try {
            type = menu.getType();
        } catch (UnsupportedOperationException e) {
            return null;
        }

        Object constructor = MenuScreensAccessor.simulatica$screens().get(type);
        if (constructor == null) {
            return null;
        }
        try {
            return (Screen) screenConstructorCreate().invoke(constructor, menu, inventory, title);
        } catch (ReflectiveOperationException e) {
            Simulatica.LOGGER.error("[Simulatica] Failed to create a menu screen for {}", type, e);
            return null;
        }
    }

    // MenuScreens.ScreenConstructor is private in 26.x; resolve its create() once reflectively.
    private static Method screenConstructorCreate() {
        if (screenConstructorCreate == null) {
            try {
                Class<?> iface = Class.forName("net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor");
                Method m = iface.getMethod("create", AbstractContainerMenu.class, Inventory.class, Component.class);
                m.setAccessible(true);
                screenConstructorCreate = m;
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        return screenConstructorCreate;
    }

    private static Method screenConstructorCreate;

    public static void close() {
        AbstractContainerMenu menu = open;
        if (menu == null) {
            return;
        }
        open = null;

        ItemStack[] before = snapshot;
        snapshot = null;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        boolean creative = player.hasInfiniteMaterials();
        ItemStack carried = menu.getCarried();
        menu.setCarried(ItemStack.EMPTY);
        if (creative && !carried.isEmpty()) {
            player.getInventory().add(carried);
        }

        // Chests count their viewers; without this the simulated one stays open forever.
        menu.removed(player);

        if (creative) {
            syncCreativeChanges(player, before);
        } else if (before != null) {
            restore(player.getInventory(), before);
        }
    }

    /**
     * Tells the real server about slots the player filled from the simulation.
     *
     * <p>The client changed its own inventory with nothing to back it up, so the next time the
     * server syncs the player's container the items would simply vanish. Creative-mode slot sets are
     * the one channel a client is allowed to do this over, which is also why this is creative only.
     * </p>
     */
    private static void syncCreativeChanges(LocalPlayer player, @Nullable ItemStack[] before) {
        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        if (gameMode == null || before == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        for (Slot slot : player.inventoryMenu.slots) {
            if (slot.container != inventory) continue;

            int index = slot.getContainerSlot();
            if (index < 0 || index >= before.length) continue;

            ItemStack now = inventory.getItem(index);
            if (!ItemStack.matches(before[index], now)) {
                gameMode.handleCreativeModeItemAdd(now, slot.index);
            }
        }
    }

    private static ItemStack[] copyOf(Inventory inventory) {
        ItemStack[] items = new ItemStack[inventory.getContainerSize()];
        for (int i = 0; i < items.length; i++) {
            items[i] = inventory.getItem(i).copy();
        }
        return items;
    }

    private static void restore(Inventory inventory, ItemStack[] items) {
        for (int i = 0; i < items.length && i < inventory.getContainerSize(); i++) {
            inventory.setItem(i, items[i]);
        }
    }
}
