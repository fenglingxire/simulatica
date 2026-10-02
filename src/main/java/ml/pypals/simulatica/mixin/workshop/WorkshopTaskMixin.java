package ml.pypals.simulatica.mixin.workshop;

import ml.pypals.simulatica.workshop.*;
import net.minecraft.client.Minecraft;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(BlockableEventLoop.class)
public class WorkshopTaskMixin {
    @ModifyVariable(method = {"execute(Ljava/lang/Runnable;)V", "schedule(Ljava/lang/Runnable;)V"},
            at = @At("HEAD"), argsOnly = true)
    private Runnable workshop$captureOwner(Runnable task) {
        if ((Object) this != Minecraft.getInstance() || !WorkshopSession.isWorldScope()) return task;
        RemoteSession owner = WorkshopSession.remote();
        long generation = owner.generation();
        return () -> {
            if (WorkshopSession.remote() == owner && owner.generation() == generation) owner.run(task);
            else if (!WorkshopSession.isActive() && owner.generation() == generation
                    && Minecraft.getInstance().getConnection() != null
                    && Minecraft.getInstance().getConnection().getConnection() == owner.connection()) task.run();
        };
    }
}
