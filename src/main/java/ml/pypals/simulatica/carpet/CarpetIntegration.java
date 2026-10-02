package ml.pypals.simulatica.carpet;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import ml.pypals.simulatica.Simulatica;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * [SIMULATICA-新增] 与地毯模组（Carpet）的反射桥接。
 *
 * <p>Carpet 是 server-side mod，未发布到标准 maven，Simulatica 编译期不依赖它。这里通过反射
 * 调用 Carpet 的 {@code ServerPlayerInterface#getActionPack()}（由 Carpet 的
 * {@code ServerPlayer_actionPackMixin} 注入到所有 ServerPlayer 上，并在每次 tick 执行动作包），
 * 从而把 Carpet 的"假人动作"能力复用到模拟世界中的玩家实体上。</p>
 *
 * <p>未安装 Carpet 时所有方法安全降级为 no-op，UI 侧用 {@link #isLoaded()} 隐藏联动入口。</p>
 */
public final class CarpetIntegration {

    private static final String MOD_ID = "carpet";

    // --- 反射句柄（懒加载）---
    private static Class<?> actionPackClass;
    private static Class<?> actionTypeClass;
    private static Class<?> actionClass;
    private static Method getActionPack;
    private static Method actionPackStart;
    private static Method actionPackStopAll;
    private static Method actionOnce;
    private static Method actionContinuous;
    private static Method actionInterval;
    private static Method actionPackSetSneaking;
    private static Method actionPackSetSprinting;
    private static Method actionPackSetForward;
    private static Method actionPackSetStrafing;
    private static Method actionPackStopMovement;
    private static boolean resolved;

    private CarpetIntegration() {}

    public static boolean isLoaded() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /**
     * 解析 Carpet 的 ActionPack 反射句柄。失败（未安装或版本不符）时置为不可用。
     */
    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!isLoaded()) {
            return;
        }
        try {
            ClassLoader loader = CarpetIntegration.class.getClassLoader();
            actionPackClass = Class.forName("carpet.helpers.EntityPlayerActionPack", false, loader);
            actionTypeClass = Class.forName("carpet.helpers.EntityPlayerActionPack$ActionType", false, loader);
            actionClass = Class.forName("carpet.helpers.EntityPlayerActionPack$Action", false, loader);

            // ServerPlayer 通过 mixin 实现 ServerPlayerInterface，方法直接落在 ServerPlayer 上。
            getActionPack = ServerPlayer.class.getMethod("getActionPack");
            actionPackStart = actionPackClass.getMethod("start", actionTypeClass, actionClass);
            actionPackStopAll = actionPackClass.getMethod("stopAll");
            actionOnce = actionClass.getMethod("once");
            actionContinuous = actionClass.getMethod("continuous");
            actionInterval = actionClass.getMethod("interval", int.class);
            actionPackSetSneaking = actionPackClass.getMethod("setSneaking", boolean.class);
            actionPackSetSprinting = actionPackClass.getMethod("setSprinting", boolean.class);
            actionPackSetForward = actionPackClass.getMethod("setForward", float.class);
            actionPackSetStrafing = actionPackClass.getMethod("setStrafing", float.class);
            actionPackStopMovement = actionPackClass.getMethod("stopMovement");
        } catch (Throwable t) {
            Simulatica.LOGGER.warn("[Simulatica] Carpet integration unavailable: {}", t.getMessage());
            actionPackClass = null;
            actionTypeClass = null;
            actionClass = null;
            getActionPack = null;
        }
    }

    /** Carpet 联动是否可用（已加载且反射成功）。 */
    public static boolean isAvailable() {
        resolve();
        return actionPackClass != null && getActionPack != null;
    }

    /** 动作包类型名集合，供配置界面枚举。 */
    public static String[] actionNames() {
        return new String[] {"USE", "ATTACK", "JUMP", "DROP_ITEM", "DROP_STACK", "SWAP_HANDS"};
    }

    /**
     * 让玩家实体开始执行某个连续动作（如持续攻击/使用/跳跃）。
     *
     * @param action Carpet 动作名（见 {@link #actionNames()}）
     */
    public static void startContinuous(ServerPlayer player, String action) {
        resolve();
        if (getActionPack == null || actionPackStart == null) {
            return;
        }
        try {
            Object pack = getActionPack.invoke(player);
            Object type = enumConstant(action);
            Object continuous = actionContinuous.invoke(null);
            actionPackStart.invoke(pack, type, continuous);
        } catch (Throwable t) {
            Simulatica.LOGGER.warn("[Simulatica] Could not start bot action '{}': {}", action, t.getMessage());
        }
    }

    /** 让玩家实体执行一次动作（如丢一个物品、交换左右手）。 */
    public static void startOnce(ServerPlayer player, String action) {
        resolve();
        if (getActionPack == null || actionPackStart == null) {
            return;
        }
        try {
            Object pack = getActionPack.invoke(player);
            Object type = enumConstant(action);
            Object once = actionOnce.invoke(null);
            actionPackStart.invoke(pack, type, once);
        } catch (Throwable t) {
            Simulatica.LOGGER.warn("[Simulatica] Could not start bot action '{}': {}", action, t.getMessage());
        }
    }

    /**
     * 让玩家实体按指定间隔执行动作（参考 GCA 的 setActionInterval）。
     *
     * @param intervalTicks 间隔（游戏 tick）。正数 = 每隔 N tick 执行一次；负数 = 持续长按（continuous）。
     */
    public static void startInterval(ServerPlayer player, String action, int intervalTicks) {
        resolve();
        if (getActionPack == null || actionPackStart == null) {
            return;
        }
        try {
            Object pack = getActionPack.invoke(player);
            Object type = enumConstant(action);
            Object actionSpec;
            if (intervalTicks == 0) {
                actionSpec = null; // Carpet start(type, null) stops only this action.
            } else if (intervalTicks < 0) {
                actionSpec = actionContinuous.invoke(null);
            } else {
                actionSpec = actionInterval.invoke(null, intervalTicks);
            }
            actionPackStart.invoke(pack, type, actionSpec);
        } catch (Throwable t) {
            Simulatica.LOGGER.warn("[Simulatica] Could not start bot action '{}': {}", action, t.getMessage());
        }
    }

    public static void setSneaking(ServerPlayer player, boolean sneaking) {
        invokePackBoolean(player, actionPackSetSneaking, sneaking);
    }

    public static void setSprinting(ServerPlayer player, boolean sprinting) {
        invokePackBoolean(player, actionPackSetSprinting, sprinting);
    }

    public static void setForward(ServerPlayer player, float forward) {
        invokePackFloat(player, actionPackSetForward, forward);
    }

    public static void setStrafing(ServerPlayer player, float strafing) {
        invokePackFloat(player, actionPackSetStrafing, strafing);
    }

    public static void stopMovement(ServerPlayer player) {
        resolve();
        if (getActionPack == null || actionPackStopMovement == null) {
            return;
        }
        try {
            Object pack = getActionPack.invoke(player);
            actionPackStopMovement.invoke(pack);
        } catch (Throwable t) {
            Simulatica.LOGGER.warn("[Simulatica] Could not stop bot movement: {}", t.getMessage());
        }
    }

    private static void invokePackBoolean(ServerPlayer player, Method method, boolean value) {
        resolve();
        if (getActionPack == null || method == null) {
            return;
        }
        try {
            Object pack = getActionPack.invoke(player);
            method.invoke(pack, value);
        } catch (Throwable t) {
            Simulatica.LOGGER.warn("[Simulatica] Could not update bot state: {}", t.getMessage());
        }
    }

    private static void invokePackFloat(ServerPlayer player, Method method, float value) {
        resolve();
        if (getActionPack == null || method == null) {
            return;
        }
        try {
            Object pack = getActionPack.invoke(player);
            method.invoke(pack, value);
        } catch (Throwable t) {
            Simulatica.LOGGER.warn("[Simulatica] Could not update bot state: {}", t.getMessage());
        }
    }

    /** 按名字查找 ActionType 枚举常量。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(String action) {
        return Enum.valueOf((Class) actionTypeClass, action);
    }

    /** 停止玩家实体的全部动作。 */
    public static void stopAll(ServerPlayer player) {
        resolve();
        if (getActionPack == null || actionPackStopAll == null) {
            return;
        }
        try {
            Object pack = getActionPack.invoke(player);
            actionPackStopAll.invoke(pack);
        } catch (Throwable t) {
            Simulatica.LOGGER.warn("[Simulatica] Could not stop bot actions: {}", t.getMessage());
        }
    }
}
