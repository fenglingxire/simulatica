package ml.pypals.simulatica.carpet;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.pypals.simulatica.simulation.SimulationManager;
import ml.pypals.simulatica.simulation.server.ProjectionBridge;
import ml.pypals.simulatica.simulation.server.SimulationLevel;
import ml.pypals.simulatica.simulation.server.SimulationServer;
import ml.pypals.simulatica.simulation.server.SimulationViewer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [SIMULATICA-新增] 假人（bot）生命周期管理（按投影归类）。
 *
 * <p>假人是投放到模拟世界中的可操控玩家实体：用 Carpet 的动作包（ActionPack）驱动其行为，并可
 * 切换游戏模式、打开背包、传送到玩家位置。假人按投影放置（SchematicPlacement）归类，随该投影的
 * 停止一并回收，统一在单投影配置界面管理。</p>
 */
public final class BotManager {

    private static final Map<SchematicPlacement, List<Bot>> bots = new LinkedHashMap<>();

    /** 假人（ServerPlayer）→ 渲染镜像（ClientMannequin）。litematica 只渲染客户端实体，故额外挂一个镜像。 */
    private static final Map<ServerPlayer, ClientMannequin> avatars = new HashMap<>();

    private BotManager() {}

    /** 一个假人：游戏内玩家实体 + 显示名 + 渲染镜像。 */
    public static final class Bot {
        private final String name;
        private final ServerPlayer player;
        private final ClientMannequin avatar;

        Bot(String name, ServerPlayer player, ClientMannequin avatar) {
            this.name = name;
            this.player = player;
            this.avatar = avatar;
        }

        public String name() {
            return this.name;
        }

        public ServerPlayer player() {
            return this.player;
        }

        public ClientMannequin avatar() {
            return this.avatar;
        }
    }

    /** 在指定投影的模拟世界召唤一个假人，出生在玩家当前位置。 */
    @Nullable
    public static Bot spawn(SchematicPlacement placement, String name, GameType gameMode) {
        SimulationServer server = SimulationServer.getRunning();
        Minecraft mc = Minecraft.getInstance();
        if (server == null || mc.player == null) {
            return null;
        }

        Map<String, ProjectionBridge> bridges = SimulationManager.getInstance().getSimulations(placement);
        if (bridges == null || bridges.isEmpty()) {
            return null;
        }

        ProjectionBridge bridge = bridges.values().iterator().next();
        SimulationLevel level = bridge.level();

        // 出生点钳入模拟区域：玩家可能站在投影边界外侧召唤，若假人出生在区域外的未加载区块，
        // ServerPlayer.doTick 的 touchingUnloadedChunk() 会跳过物理 → 悬空不动，刷怪中心也跟着出区。
        AABB bounds = bridge.region().simBounds();
        Vec3 raw = mc.player.position();
        Vec3 pos = new Vec3(
                clampSpawn(raw.x, bounds.minX, bounds.maxX),
                clampSpawn(raw.y, bounds.minY, bounds.maxY),
                clampSpawn(raw.z, bounds.minZ, bounds.maxZ));

        ServerPlayer player = SimulationViewer.spawnBot(server, level, name, pos, gameMode);

        // 创造模式假人飞行：出生点离地面较远（下方超 2 格无实体方块）时开启飞行避免凭空掉落；
        // 玩家处于创造模式且正在飞行时，假人的飞行状态与玩家同步。
        if (gameMode == GameType.CREATIVE) {
            boolean fly = (mc.player.isCreative() && mc.player.getAbilities().flying)
                    || !hasGroundBelow(level, pos, 2);
            if (fly) {
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            }
        }

        // 渲染镜像：ClientMannequin 是客户端实体、默认 Steve 皮肤，litematica 的 prepareEntities 能直接
        // 渲染它（ServerPlayer 无法被 vanilla AvatarRenderer 渲染）。位置由 syncAvatar 每帧同步。
        // 用 BotAvatar（tick 为空）而非裸 ClientMannequin：后者 tick 会走物理，与 syncAvatar 拉扯导致抽搐。
        ClientMannequin avatar = new BotAvatar(level, mc.playerSkinRenderCache());
        avatar.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        level.addFreshEntity(avatar);
        avatars.put(player, avatar);

        Bot bot = new Bot(name, player, avatar);
        bots.computeIfAbsent(placement, p -> new ArrayList<>()).add(bot);
        return bot;
    }

    /** Half a block inside the region, or the region's center line when it is too thin. */
    private static double clampSpawn(double value, double min, double max) {
        double lo = min + 0.5;
        double hi = max - 0.5;
        if (lo > hi) {
            return (min + max) / 2.0;
        }
        return Mth.clamp(value, lo, hi);
    }

    /** 出生点下方 depth 格内是否存在可站立的实体方块。 */
    private static boolean hasGroundBelow(SimulationLevel level, Vec3 pos, int depth) {
        int px = Mth.floor(pos.x);
        int py = Mth.floor(pos.y);
        int pz = Mth.floor(pos.z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= depth; dy++) {
            cursor.set(px, py - dy, pz);
            if (level.getBlockState(cursor).blocksMotion()) {
                return true;
            }
        }
        return false;
    }

    /** 把假人传送到玩家当前位置，视角方向与玩家一致。 */
    public static void teleportToPlayer(Bot bot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        LocalPlayer p = mc.player;
        Vec3 pos = p.position();
        bot.player().snapTo(pos.x, pos.y, pos.z, p.getYRot(), p.getXRot());
        bot.player().setYHeadRot(p.getYRot());
        bot.player().setYBodyRot(p.getYRot());
        bot.player().setYRot(p.getYRot());

        // 更新假人在 ChunkMap 距离管理器里的位置，让刷怪中心跟随假人
        if (bot.player().level() instanceof ServerLevel botLevel) {
            botLevel.getChunkSource().chunkMap.move(bot.player());
        }
    }

    /** 移除单个假人。 */
    public static void remove(SchematicPlacement placement, Bot bot) {
        SimulationServer server = SimulationServer.getRunning();
        List<Bot> list = bots.get(placement);
        if (list != null && list.remove(bot)) {
            if (list.isEmpty()) {
                bots.remove(placement);
            }
            if (server != null) {
                SimulationViewer.removeBot(server, bot.player());
            }
            discardAvatar(bot);
        }
    }

    /** 移除渲染镜像（ClientMannequin）。 */
    private static void discardAvatar(Bot bot) {
        ClientMannequin avatar = avatars.remove(bot.player());
        if (avatar != null) {
            avatar.discard();
        }
    }

    /**
     * 同步渲染镜像到假人的当前位置/朝向。由 {@code ProjectionBridge.animate} 每帧调用，
     * 让假人移动（动作包驱动/传送）时镜像跟随。
     */
    public static void syncAvatar(ServerPlayer player) {
        ClientMannequin avatar = avatars.get(player);
        if (avatar == null || avatar.isRemoved()) {
            return;
        }
        if (player.isRemoved()) {
            discardAvatar(player);
            return;
        }
        avatar.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        // snapTo 只设 yRot；身体/头朝向与受击/死亡状态要显式同步，否则镜像的头部会朝旧方向、受击不消退。
        avatar.setYBodyRot(player.yBodyRot);
        avatar.setYHeadRot(player.getYHeadRot());
        avatar.hurtTime = player.hurtTime;
        avatar.hurtDuration = player.hurtDuration;
        avatar.deathTime = player.deathTime;
        // 手持物品与盔甲同步到镜像（否则镜像手上/身上是空的）
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            avatar.setItemSlot(slot, player.getItemBySlot(slot).copy());
        }
    }

    /** 按玩家实体移除渲染镜像（供死亡后清理使用）。 */
    public static void discardAvatar(ServerPlayer player) {
        ClientMannequin avatar = avatars.remove(player);
        if (avatar != null) {
            avatar.discard();
        }
    }

    /** 一键清理：移除某投影下的全部假人。 */
    public static void removeAll(SchematicPlacement placement) {
        SimulationServer server = SimulationServer.getRunning();
        List<Bot> list = bots.remove(placement);
        if (list == null) {
            return;
        }
        for (Bot bot : list) {
            if (server != null) {
                SimulationViewer.removeBot(server, bot.player());
            }
            discardAvatar(bot);
        }
    }

    /** 某投影下的假人列表（不可变）。 */
    public static List<Bot> botsOf(SchematicPlacement placement) {
        List<Bot> list = bots.get(placement);
        return list != null ? Collections.unmodifiableList(list) : List.of();
    }

    /** 全局同步动作：让某投影下所有假人执行同一动作。 */
    public static void actionAll(SchematicPlacement placement, String action, boolean continuous) {
        for (Bot bot : botsOf(placement)) {
            if (continuous) {
                CarpetIntegration.startContinuous(bot.player(), action);
            } else {
                CarpetIntegration.startOnce(bot.player(), action);
            }
        }
    }

    /** 停止某投影下所有假人的全部动作。 */
    public static void stopAllActions(SchematicPlacement placement) {
        for (Bot bot : botsOf(placement)) {
            CarpetIntegration.stopAll(bot.player());
        }
    }

    /** 让某投影下所有假人按指定间隔执行动作（负数=长按，0=不动作）。 */
    public static void actionAllInterval(SchematicPlacement placement, String action, int intervalTicks) {
        for (Bot bot : botsOf(placement)) {
            CarpetIntegration.startInterval(bot.player(), action, intervalTicks);
        }
    }

    /** 让某投影下所有假人潜行/取消潜行。 */
    public static void setSneakingAll(SchematicPlacement placement, boolean sneaking) {
        for (Bot bot : botsOf(placement)) {
            CarpetIntegration.setSneaking(bot.player(), sneaking);
        }
    }

    /** 让某投影下所有假人疾跑/取消疾跑。 */
    public static void setSprintingAll(SchematicPlacement placement, boolean sprinting) {
        for (Bot bot : botsOf(placement)) {
            CarpetIntegration.setSprinting(bot.player(), sprinting);
        }
    }

    /** 让某投影下所有假人前进/停止前进（forward 1.0/0.0）。 */
    public static void setForwardAll(SchematicPlacement placement, float forward) {
        for (Bot bot : botsOf(placement)) {
            CarpetIntegration.setForward(bot.player(), forward);
        }
    }

    /** 清空全部假人（断开连接/世界关闭时）。 */
    public static void clearAll() {
        SimulationServer server = SimulationServer.getRunning();
        for (List<Bot> list : bots.values()) {
            for (Bot bot : list) {
                if (server != null) {
                    SimulationViewer.removeBot(server, bot.player());
                }
                discardAvatar(bot);
            }
        }
        bots.clear();
    }

    /** 所有投影下的全部假人（跨投影，供全局操作使用）。 */
    public static List<Bot> allBots() {
        List<Bot> all = new ArrayList<>();
        for (List<Bot> list : bots.values()) {
            all.addAll(list);
        }
        return all;
    }

    /** 全局：把全部假人传送到玩家当前位置。 */
    public static int teleportAllToPlayer() {
        List<Bot> all = allBots();
        for (Bot bot : all) {
            teleportToPlayer(bot);
        }
        return all.size();
    }

    /** 全局：一键清理全部假人（跨投影）。 */
    public static int removeAllGlobally() {
        SimulationServer server = SimulationServer.getRunning();
        int count = 0;
        for (List<Bot> list : bots.values()) {
            for (Bot bot : list) {
                if (server != null) {
                    SimulationViewer.removeBot(server, bot.player());
                }
                discardAvatar(bot);
                count++;
            }
        }
        bots.clear();
        return count;
    }

    /** 全局：停止全部假人的全部动作。 */
    public static void stopAllActionsGlobally() {
        for (Bot bot : allBots()) {
            CarpetIntegration.stopAll(bot.player());
        }
    }
}
