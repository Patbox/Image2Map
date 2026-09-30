package space.essem.image2map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import space.essem.image2map.upload.ServerImageTasks;
import space.essem.image2map.config.Image2MapConfig;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Commands remain server-side so vanilla and older clients retain URL support. */
public final class ImageCommands {
    private ImageCommands() { }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(root(Image2Map.CONFIG)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> root(Image2MapConfig config) {
        return literal("image2map")
                        .requires(FabricPermissionBridge.require(id("use"), PermissionLevel.byId(config.minPermLevel)))
                        .then(create("create", false, config))
                        .then(create("create-folder", true, config))
                        .then(literal("preview").requires(FabricPermissionBridge.require(id("preview"), true))
                                .executes(context -> run(context, true, false))
                                .then(argument("path", StringArgumentType.greedyString()).executes(context -> run(context, true, false))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> create(String name, boolean folder, Image2MapConfig config) {
        var command = literal(name);
        if (folder) command.requires(FabricPermissionBridge.require(id("createfolder"), PermissionLevel.ADMINS)
                .and(source -> config.allowServerLocalFiles));
        else command.requires(FabricPermissionBridge.require(id("create"), true))
                .executes(context -> run(context, false, false));
        command.then(argument("width", IntegerArgumentType.integer(1, config.imageMaxWidthHeight))
                .then(argument("height", IntegerArgumentType.integer(1, config.imageMaxWidthHeight)).then(mode(folder))));
        return command.then(mode(folder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> mode(boolean folder) {
        var mode = argument("mode", StringArgumentType.word()).suggests((context, builder) -> {
            builder.suggest("none");
            builder.suggest("dither");
            return builder.buildFuture();
        });
        if (!folder) mode.executes(context -> run(context, false, false));
        return mode.then(argument("path", StringArgumentType.greedyString()).executes(context -> run(context, false, folder)));
    }

    private static int run(CommandContext<CommandSourceStack> context, boolean preview, boolean folder) throws CommandSyntaxException {
        var source = context.getSource();
        var player = source.getPlayerOrException(); // Reject console before creating a job.
        Image2Map.DitherMode mode = Image2Map.DitherMode.NONE;
        if (!preview) {
            try { mode = Image2Map.DitherMode.fromString(optional(context, "mode", "none")); }
            catch (IllegalArgumentException exception) {
                throw new SimpleCommandExceptionType(() -> exception.getMessage()).create();
            }
        }
        int width = 0, height = 0;
        try {
            width = IntegerArgumentType.getInteger(context, "width");
            height = IntegerArgumentType.getInteger(context, "height");
        } catch (IllegalArgumentException ignored) { }
        return Image2Map.TASKS.start(source, player, optional(context, "path", ""),
                new ServerImageTasks.Options(mode, width, height, preview, folder));
    }

    private static String optional(CommandContext<CommandSourceStack> context, String name, String fallback) {
        try { return StringArgumentType.getString(context, name); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath("image2map", name); }
}
