package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.WorkshopServer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Set;

@Mixin(PlayerList.class)
public abstract class WorkshopPlayerListMixin {
    @Shadow @Final private MinecraftServer server;
    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void workshop$spawn(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        if (!(server instanceof WorkshopServer workshop)) return;
        var pos = workshop.spawnPosition();
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(workshop.getLevel(workshop.sourceDimension()), pos.getX() + .5, pos.getY(), pos.getZ() + .5, Set.of(), 0, 0, true);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        workshop.getPlayerList().sendPlayerPermissionLevel(player);
    }
}
