package ml.pypals.simulatica.simulation.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import ml.pypals.simulatica.Simulatica;
import ml.pypals.simulatica.simulation.SimulationManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.phys.Vec2;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * [SIMULATICA-修改] 与原版模组（1.21.11）的差异：
 * - Player.displayClientMessage → sendSystemMessage
 * - 补全改为「整行解析 + 光标位置」，不再只把当前词丢给 Brigadier（见 {@link #suggestLine}）
 * - 没有正在运行的模拟时补全仍然可用（{@link #suggestionSource} 回退到服务器主世界）
 * - 模拟服务器还没启动时退回客户端自己的命令表
 * - 不再静默吞异常
 */
public final class SimulationCommands {

    /** 本 mod 注册的客户端指令名。 */
    private static final String MOD_ROOT = "simulatica";

    private SimulationCommands() {}

    /**
     * 控制面板指令框的补全：<b>只列本 mod 自己注册的客户端指令</b>（{@code /simulatica ...}）。
     *
     * <p>候选来自 {@code ClientCommandInternals.getActiveDispatcher()}——那是 fabric-command-api
     * 专门给客户端指令用的命令表，原版那 80 多条服务器指令不在里面。再把它裁剪成只保留
     * {@code simulatica} 子树，别的 mod 的客户端指令也不会混进来。</p>
     *
     * <p>允许直接输子指令：写 {@code start} 会按 {@code simulatica start} 补全，候选范围再平移回
     * 用户实际输入的那段文本上。</p>
     */
    public static CompletableFuture<Suggestions> suggestModCommands(String line, int cursor) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return Suggestions.empty();
        }
        CommandDispatcher<FabricClientCommandSource> clientCommands = ClientCommandInternals.getActiveDispatcher();
        if (clientCommands == null) {
            return Suggestions.empty();
        }
        CommandNode<FabricClientCommandSource> modRoot = clientCommands.getRoot().getChild(MOD_ROOT);
        if (modRoot == null) {
            return Suggestions.empty();
        }

        // 只留下本 mod 的子树
        CommandDispatcher<FabricClientCommandSource> only = new CommandDispatcher<>();
        only.getRoot().addChild(modRoot);

        boolean slash = line.startsWith("/");
        int adjust = slash ? 1 : 0;
        String text = slash ? line.substring(1) : line;
        int caret = Math.max(0, Math.min(cursor - adjust, text.length()));
        boolean explicit = text.toLowerCase(Locale.ROOT).startsWith(MOD_ROOT);
        String parsed = explicit ? text : MOD_ROOT + " " + text;
        int parseCaret = explicit ? caret : MOD_ROOT.length() + 1 + caret;
        int offset = adjust + (explicit ? 0 : -(MOD_ROOT.length() + 1));

        try {
            FabricClientCommandSource source = (FabricClientCommandSource) connection.getSuggestionsProvider();
            ParseResults<FabricClientCommandSource> parse = only.parse(parsed, source);
            return only.getCompletionSuggestions(parse, parseCaret)
                    .thenApply(suggestions -> shift(suggestions, line, offset));
        } catch (Exception e) {
            Simulatica.LOGGER.warn("[Simulatica] 指令补全失败: {}", e.toString());
            return Suggestions.empty();
        }
    }

    /**
     * 模拟世界指令的补全（{@code /simulatica execute <command>} 的参数走这里）。
     *
     * <p>Brigadier 的 {@code getCompletionSuggestions(parse, cursor)} 需要**整行**文本加上
     * 光标位置：它内部用 {@code findSuggestionContext(cursor)} 定位光标所在的命令节点，
     * 再用 {@code SuggestionsBuilder(text.substring(0, cursor), startPos)} 生成候选。
     * 只把「当前词」丢进去会导致命令名后面的参数补全错位（例如 {@code time } 会给出根命令
     * 而不是 {@code set/add/query}）。</p>
     *
     * @param line   光标之前的完整输入，可以带前导 {@code /}
     * @param cursor 光标在 {@code line} 中的位置，通常是 {@code line.length()}
     * @return 候选；其中每个 Suggestion 的范围与 {@code line} 同坐标系
     */
    private static CompletableFuture<Suggestions> suggestWorldCommands(String line, int cursor) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            return Suggestions.empty();
        }

        boolean slash = line.startsWith("/");
        int adjust = slash ? 1 : 0;
        String command = slash ? line.substring(1) : line;
        int caret = Math.max(0, Math.min(cursor - adjust, command.length()));

        try {
            SimulationServer server = SimulationServer.getRunning();
            if (server != null) {
                CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
                ParseResults<CommandSourceStack> parse =
                        dispatcher.parse(command, suggestionSource(server, player));
                return dispatcher.getCompletionSuggestions(parse, caret)
                        .thenApply(suggestions -> shift(suggestions, line, adjust));
            }
            // 模拟服务器还没起来时退回客户端自己的命令表，免得补全整个不可用
            ClientPacketListener connection = client.getConnection();
            if (connection == null) {
                return Suggestions.empty();
            }
            CommandDispatcher<ClientSuggestionProvider> dispatcher = connection.getCommands();
            ParseResults<ClientSuggestionProvider> parse =
                    dispatcher.parse(command, connection.getSuggestionsProvider());
            return dispatcher.getCompletionSuggestions(parse, caret)
                    .thenApply(suggestions -> shift(suggestions, line, adjust));
        } catch (Exception e) {
            Simulatica.LOGGER.warn("[Simulatica] 指令补全失败: {}", e.toString());
            return Suggestions.empty();
        }
    }

    /**
     * 原版命令系统用（{@code /simulatica execute <command>} 的参数补全）。
     * 这里 builder 的坐标已经相对于整行，所以先取出参数部分补全，再把范围平移回去。
     */
    public static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder) {
        String input = builder.getInput();
        int start = Math.max(0, Math.min(builder.getStart(), input.length()));
        return suggestWorldCommands(input.substring(start), input.length() - start)
                .thenApply(suggestions -> shift(suggestions, input, start));
    }

    private static Suggestions shift(Suggestions suggestions, String input, int offset) {
        List<Suggestion> moved = new ArrayList<>();
        for (Suggestion suggestion : suggestions.getList()) {
            StringRange range = suggestion.getRange();
            moved.add(new Suggestion(
                    StringRange.between(range.getStart() + offset, range.getEnd() + offset),
                    suggestion.getText(),
                    suggestion.getTooltip()));
        }
        return Suggestions.create(input, moved);
    }

    public static void execute(String command) {
        String trimmed = command == null ? "" : command.trim();
        String bare = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;

        // 与补全一致：本 mod 的子指令允许省略 simulatica，世界指令保持原样。
        var dispatcher = ClientCommandInternals.getActiveDispatcher();
        var modRoot = dispatcher == null ? null : dispatcher.getRoot().getChild(MOD_ROOT);
        if (modRoot != null && modRoot.getChild(bare.split(" ", 2)[0]) != null) {
            bare = MOD_ROOT + " " + bare;
        }

        // 本 mod 自己的指令（/simulatica ...）是客户端指令，模拟服务器上没有，
        // 必须交给 fabric-command-api 的客户端指令通道执行。
        if (bare.equalsIgnoreCase(MOD_ROOT) || bare.toLowerCase(Locale.ROOT).startsWith(MOD_ROOT + " ")) {
            if (executeModCommand(bare)) {
                return;
            }
        }

        SimulationServer server = SimulationServer.getRunning();
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;

        if (server == null || player == null) {
            feedback(player, Component.literal("The simulation server is not running.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        ServerLevel level;
        try {
            level = SimulationManager.getInstance().commandLevel();
        } catch (RuntimeException e) {
            feedback(player, Component.literal("没有正在运行的模拟 — 先 /simulatica start 启动一个投影。")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        try {
            server.getCommands().performPrefixedCommand(sourceFor(server, player, level), command);
        } catch (Exception e) {
            feedback(player, Component.literal("Command failed: " + e).withStyle(ChatFormatting.RED));
        }
    }

    /** 走 fabric-command-api 的客户端指令通道，等价于玩家在聊天栏里敲这条指令。 */
    private static boolean executeModCommand(String bare) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection == null || client.player == null) {
            return false;
        }
        if (!(connection.getSuggestionsProvider() instanceof FabricClientCommandSource source)) {
            return false;
        }
        try {
            return ClientCommandInternals.executeCommand(bare, source, source);
        } catch (Throwable t) {
            Simulatica.LOGGER.warn("[Simulatica] 客户端指令执行失败: {}", t.toString());
            return false;
        }
    }

    /**
     * 补全用的命令源：只需要一个能通过 {@code requires} 谓词的 source，不必真的落在模拟世界里，
     * 所以没有正在模拟的投影时退回到服务器主世界，而不是让 {@code commandLevel()} 抛异常。
     */
    private static CommandSourceStack suggestionSource(SimulationServer server, LocalPlayer player) {
        ServerLevel level = null;
        try {
            level = SimulationManager.getInstance().commandLevel();
        } catch (RuntimeException ignored) {
            // 没有正在运行的模拟，走下面的回退
        }
        if (level == null) {
            level = server.overworld();
        }
        return sourceFor(server, player, level);
    }

    private static CommandSourceStack sourceFor(SimulationServer server, LocalPlayer player,
                                                @Nullable ServerLevel level) {
        Component name = Component.literal("Simulatica");
        return new CommandSourceStack(
                sink(player),
                player.position(),
                new Vec2(player.getXRot(), player.getYRot()),
                level,
                LevelBasedPermissionSet.OWNER,
                name.getString(),
                name,
                server,
                null);
    }

    private static CommandSource sink(LocalPlayer player) {
        return new CommandSource() {
            @Override
            public void sendSystemMessage(@NonNull Component message) {
                feedback(player, message);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
    }

    private static void feedback(LocalPlayer player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }
}
