package ml.pypals.simulatica.mixin.simulation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.DifficultyCommand;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DifficultyCommand.class)
public abstract class SimulationDifficultyCommandMixin {
    @WrapOperation(method = "setDifficulty", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getWorldData()Lnet/minecraft/world/level/storage/WorldData;"))
    private static WorldData simulatica$data(MinecraftServer server, Operation<WorldData> original,
                                            @Local(argsOnly = true) CommandSourceStack source) {
        return source.getLevel() instanceof SimulationLevel level
                ? (WorldData) level.getLevelData() : original.call(server);
    }

    @WrapOperation(method = "setDifficulty", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;setDifficulty(Lnet/minecraft/world/Difficulty;Z)V"))
    private static void simulatica$difficulty(MinecraftServer server, Difficulty difficulty, boolean force,
                                             Operation<Void> original,
                                             @Local(argsOnly = true) CommandSourceStack source) {
        if (source.getLevel() instanceof SimulationLevel level) level.setSimulationDifficulty(difficulty);
        else original.call(server, difficulty, force);
    }
}
