package ml.pypals.simulatica.mixin.workshop;

import net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractChanneledNetworkAddon.class)
public interface WorkshopFabricAddonAccessor {
    @Accessor("connection") Connection simulatica$connection();
}
