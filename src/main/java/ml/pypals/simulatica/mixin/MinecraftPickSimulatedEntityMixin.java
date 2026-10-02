package ml.pypals.simulatica.mixin;

import fi.dy.masa.litematica.data.DataManager;
import ml.pypals.simulatica.SimulaticaClient;
import ml.pypals.simulatica.simulation.SimulationRaycast;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [SIMULATICA-新增] 与原版模组（1.21.11）的差异：
 * - 中键（选取）对模拟生物生效，直接取刷怪蛋
 */
/**
 * Middle-click picking for simulated mobs.
 *
 * <p>Vanilla picking reads {@code Minecraft.hitResult}, which is built from the client level and
 * therefore never contains a simulated mob -- middle-clicking one silently picked whatever real
 * block was behind it. The pick is done here instead, and done locally: vanilla's
 * {@code MultiPlayerGameMode.handlePickItemFromEntity} opens with a
 * {@code ServerboundPickItemFromEntityPacket} carrying the entity's id, and that id means nothing
 * on the real server.</p>
 *
 * <p>Survival only selects the egg if it is already carried, exactly like pick-block; creative
 * also places one in the active hotbar slot.</p>
 */
@Mixin(value = Minecraft.class, remap = false)
public class MinecraftPickSimulatedEntityMixin {

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void simulatica$pickSimulatedEntity(CallbackInfo ci) {
        if (ml.pypals.simulatica.workshop.WorkshopManager.isActive()) return;
        Minecraft mc = (Minecraft) (Object) this;
        LocalPlayer player = mc.player;
        if (player == null || DataManager.getToolMode() != SimulaticaClient.SIMULATE) {
            return;
        }

        SimulationRaycast.Hit hit = SimulationRaycast.traceEntity(mc, SimulationRaycast.REACH);
        if (hit == null || !(hit.entity().level() instanceof SimulationLevel)) {
            return;
        }

        ItemStack egg = hit.entity().getPickResult();
        if (egg == null || egg.isEmpty()) {
            return;
        }

        Inventory inventory = player.getInventory();
        int slot = inventory.findSlotMatchingItem(egg);
        if (slot >= 0) {
            if (slot < Inventory.getSelectionSize()) {
                inventory.setSelectedSlot(slot);
            } else {
                int selected = inventory.getSelectedSlot();
                inventory.setItem(slot, inventory.getItem(selected));
                inventory.setItem(selected, egg);
            }
        } else if (player.getAbilities().instabuild) {
            inventory.setItem(inventory.getSelectedSlot(), egg);
        }

        ci.cancel();
    }
}
