package ml.pypals.simulatica.mixin.workshop;

import net.minecraft.client.resources.server.ServerPackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

@Mixin(ServerPackManager.class)
public interface WorkshopPackManagerAccessor {
    @Accessor("packs") List<Object> simulatica$packs();
}
