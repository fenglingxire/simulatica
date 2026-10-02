package ml.pypals.simulatica.mixin.workshop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface WorkshopMinecraftAccessor {
    @Accessor("singleplayerServer") void workshop$server(IntegratedServer server);
    @Accessor("pendingConnection") void workshop$pending(Connection connection);
    @Accessor("isLocalServer") void workshop$local(boolean local);
}
