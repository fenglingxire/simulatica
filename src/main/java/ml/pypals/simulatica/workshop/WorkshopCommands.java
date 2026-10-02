package ml.pypals.simulatica.workshop;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fi.dy.masa.litematica.data.DataManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.Locale;

public final class WorkshopCommands {
    private WorkshopCommands() {}

    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        return ClientCommands.literal("workshop")
                .then(ClientCommands.literal("enter").executes(context -> { WorkshopManager.enterSelected(); return 1; })
                        .then(ClientCommands.argument("placement_name", StringArgumentType.string())
                                .suggests((context, builder) -> {
                                    String prefix = builder.getRemainingLowerCase();
                                    for (var placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
                                        String name = placement.getName();
                                        String quoted = StringArgumentType.escapeIfRequired(name);
                                        if (name.toLowerCase(Locale.ROOT).startsWith(prefix) || quoted.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                            builder.suggest(quoted);
                                        }
                                    }
                                    return builder.buildFuture();
                                }).executes(context -> {
                                    WorkshopManager.enter(StringArgumentType.getString(context, "placement_name"));
                                    return 1;
                                })))
                .then(ClientCommands.literal("return").executes(context -> { WorkshopManager.requestReturn(); return 1; }));
    }
}
