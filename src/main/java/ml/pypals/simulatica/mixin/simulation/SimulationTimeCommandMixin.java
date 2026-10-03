package ml.pypals.simulatica.mixin.simulation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.TimeCommand;
import net.minecraft.world.clock.ServerClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla command parsing, with the selected simulation's clock as the target. */
@Mixin(TimeCommand.class)
public abstract class SimulationTimeCommandMixin {
    @WrapOperation(method = {"suggestTimeMarkers", "queryTime", "queryTimelineTicks", "queryTimelineRepetitions",
            "setTotalTicks", "addTime", "setTimeToTimeMarker", "setPaused", "setRate"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;clockManager()Lnet/minecraft/world/clock/ServerClockManager;"))
    private static ServerClockManager simulatica$clock(MinecraftServer server, Operation<ServerClockManager> original,
                                                     @Local(argsOnly = true) CommandSourceStack source) {
        return source.getLevel() instanceof SimulationLevel level ? level.clockManager() : original.call(server);
    }
}
