package ml.pypals.simulatica.mixin.workshop;

import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.ServerPackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DownloadedPackSource.class)
public interface WorkshopDownloadedPackAccessor {
    @Accessor("manager") ServerPackManager simulatica$manager();
}
