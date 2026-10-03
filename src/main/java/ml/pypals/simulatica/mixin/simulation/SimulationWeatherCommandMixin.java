package ml.pypals.simulatica.mixin.simulation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.WeatherCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WeatherCommand.class)
public abstract class SimulationWeatherCommandMixin {
    @WrapOperation(method = {"setClear", "setRain", "setThunder"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;setWeatherParameters(IIZZ)V"))
    private static void simulatica$weather(MinecraftServer server, int clearTime, int duration,
                                          boolean rain, boolean thunder, Operation<Void> original,
                                          @Local(argsOnly = true) CommandSourceStack source) {
        if (source.getLevel() instanceof SimulationLevel level) {
            level.setSimulationWeather(clearTime, duration, rain, thunder);
        } else original.call(server, clearTime, duration, rain, thunder);
    }
}
