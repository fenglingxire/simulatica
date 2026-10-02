package ml.pypals.simulatica.mixin.simulation;

import ml.pypals.simulatica.simulation.server.SimulationLevel;
import ml.pypals.simulatica.simulation.server.SimulationServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * [SIMULATICA-新增] 把模拟世界的自然刷怪限制在投影区域（chunk）内。
 *
 * <p>自然刷怪以附近玩家为中心、半径 128 格（{@code NaturalSpawner.SPAWN_DISTANCE_BLOCK}）发生，
 * 而投影通常只有几十格大——假人触发刷怪后，怪物会刷到投影外的模拟世界空地里，既不可见又白费
 * tick。本 mixin 在 {@code spawnForChunk} 入口拦截：只要目标 chunk 不属于任何投影区域，就跳过刷怪。</p>
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerLimitMixin {

    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void simulatica$limitSpawning(ServerLevel level, LevelChunk chunk,
                                                 NaturalSpawner.SpawnState spawnState,
                                                 List<MobCategory> categories, CallbackInfo ci) {
        if (!(level instanceof SimulationLevel simulationLevel)) {
            return;
        }
        SimulationServer server = SimulationServer.getRunning();
        if (server == null || !server.isSimulatedChunk(simulationLevel, chunk.getPos().x(), chunk.getPos().z())) {
            ci.cancel();
        }
    }
}
