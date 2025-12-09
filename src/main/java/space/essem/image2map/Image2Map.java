package space.essem.image2map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import eu.pb4.sgui.api.GuiHelpers;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.gui.PreviewGui;
import space.essem.image2map.renderer.MapRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;


public class Image2Map implements ModInitializer {
    public static final Logger LOGGER = LogUtils.getLogger();

    public static Image2MapConfig CONFIG = Image2MapConfig.loadOrCreateConfig();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("image2map")
                    .requires(Permissions.require("image2map.use", CONFIG.minPermLevel))
                    .then(literal("create")
                            .requires(Permissions.require("image2map.create", 0))
                            .then(argument("width", IntegerArgumentType.integer(1))
                                    .then(argument("height", IntegerArgumentType.integer(1))
                                            .then(argument("mode", StringArgumentType.word()).suggests(new DitherModeSuggestionProvider())
                                                    .then(argument("path", StringArgumentType.greedyString())
                                                            .executes(this::createMap))
                                            )
                                    )
                            )
                            .then(argument("mode", StringArgumentType.word()).suggests(new DitherModeSuggestionProvider())
                                    .then(argument("path", StringArgumentType.greedyString())
                                            .executes(this::createMap)
                                    )
                            )
                    )
                    .then(literal("create-folder")
                            .requires(Permissions.require("image2map.createfolder", 3).and(x -> CONFIG.allowLocalFiles))
                            .then(argument("width", IntegerArgumentType.integer(1))
                                    .then(argument("height", IntegerArgumentType.integer(1))
                                            .then(argument("mode", StringArgumentType.word()).suggests(new DitherModeSuggestionProvider())
                                                    .then(argument("path", StringArgumentType.greedyString())
                                                            .executes(this::createMapFromFolder))
                                            )
                                    )
                            )
                            .then(argument("mode", StringArgumentType.word()).suggests(new DitherModeSuggestionProvider())
                                    .then(argument("path", StringArgumentType.greedyString())
                                            .executes(this::createMapFromFolder)
                                    )
                            )
                    )
                    .then(literal("preview")
                            .requires(Permissions.require("image2map.preview", 0))
                            .then(argument("path", StringArgumentType.greedyString())
                                    .executes(this::openPreview)
                            )
                    )
            );
        });

        ServerLifecycleEvents.SERVER_STARTED.register((s) -> CardboardWarning.checkAndAnnounce());
    }

    private int openPreview(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String input = StringArgumentType.getString(context, "path");

        source.sendSuccess(() -> Component.literal("Getting image..."), false);

        getImage(input).orTimeout(20, TimeUnit.SECONDS).handleAsync((image, ex) -> {
            if (ex instanceof TimeoutException) {
                source.sendSuccess(() -> Component.literal("Downloading or reading of the image took too long!"), false);
                return null;
            } else if (ex != null) {
                if (ex instanceof RuntimeException ru && ru.getCause() != null) {
                    ex = ru.getCause();
                }

                Throwable finalEx = ex;
                source.sendSuccess(() -> Component.literal("The image isn't valid (hover for more info)!")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withHoverEvent(new HoverEvent.ShowText(Component.literal(finalEx.getMessage())))), false);
                return null;
            }

            if (image == null) {
                source.sendSuccess(() -> Component.literal("That doesn't seem to be a valid image (unknown reason)!"), false);
                return null;
            }

            if (GuiHelpers.getCurrentGui(source.getPlayer()) instanceof PreviewGui previewGui) {
                previewGui.close();
            }
            new PreviewGui(context.getSource().getPlayer(), image, input, DitherMode.NONE, image.getWidth(), image.getHeight());

            return null;
        }, source.getServer());

        return 1;
    }

    class DitherModeSuggestionProvider implements SuggestionProvider<CommandSourceStack> {

        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> context,
                                                             SuggestionsBuilder builder) throws CommandSyntaxException {
            builder.suggest("none");
            builder.suggest("dither");
            return builder.buildFuture();
        }

    }

    public enum DitherMode {
        NONE,
        FLOYD;

        public static DitherMode fromString(String string) {
            if (string.equalsIgnoreCase("NONE"))
                return DitherMode.NONE;
            else if (string.equalsIgnoreCase("DITHER") || string.equalsIgnoreCase("FLOYD"))
                return DitherMode.FLOYD;
            throw new IllegalArgumentException("invalid dither mode");
        }
    }

    private CompletableFuture<BufferedImage> getImage(String input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (isValid(input)) {
                    try(var client = HttpClient.newHttpClient()) {
                        var req = HttpRequest.newBuilder().GET().uri(URI.create(input)).timeout(Duration.ofSeconds(30))
                                .setHeader("User-Agent", "Image2Map mod").build();

                        var stream = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                        return ImageIO.read(stream.body());
                    }
                } else if (CONFIG.allowLocalFiles) {
                    var path = FabricLoader.getInstance().getGameDir().resolve(input);
                    if (Files.exists(path)) {
                        return ImageIO.read(Files.newInputStream(path));
                    }
                    return null;
                } else {
                    return null;
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<BufferedImage> getImageFromFolder(String input) {
        if (CONFIG.allowLocalFiles) {
            try {
                var arr = new ArrayList<BufferedImage>();
                var path = FabricLoader.getInstance().getGameDir().resolve(input);
                if (Files.exists(path) && Files.isDirectory(path)) {
                    Files.walkFileTree(path, new FileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            try {
                                var x = ImageIO.read(Files.newInputStream(file));
                                if (x != null) {
                                    arr.add(x);
                                }
                            }catch (Throwable e) {
                                e.printStackTrace();
                            }

                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
                return arr;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        return List.of();
    }

    private int createMap(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        Player player = source.getPlayer();
        DitherMode mode;
        String modeStr = StringArgumentType.getString(context, "mode");
        try {
            mode = DitherMode.fromString(modeStr);
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(() -> "Invalid dither mode '" + modeStr + "'").create();
        }

        String input = StringArgumentType.getString(context, "path");

        source.sendSuccess(() -> Component.literal("Getting image..."), false);

        getImage(input).orTimeout(20, TimeUnit.SECONDS).handleAsync((image, ex) -> {
            if (ex instanceof TimeoutException) {
                source.sendSuccess(() -> Component.literal("Downloading or reading of the image took too long!"), false);
                return null;
            } else if (ex != null) {
                if (ex instanceof RuntimeException ru && ru.getCause() != null) {
                    ex = ru.getCause();
                }

                Throwable finalEx = ex;
                source.sendSuccess(() -> Component.literal("The image isn't valid (hover for more info)!")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withHoverEvent(new HoverEvent.ShowText(Component.literal(finalEx.getMessage())))), false);
                return null;
            }

            if (image == null) {
                source.sendSuccess(() -> Component.literal("That doesn't seem to be a valid image (unknown reason)!"), false);
                return null;
            }

            int width;
            int height;

            try {
                width = IntegerArgumentType.getInteger(context, "width");
                height = IntegerArgumentType.getInteger(context, "height");
            } catch (Throwable e) {
                width = image.getWidth();
                height = image.getHeight();
            }

            int finalHeight = height;
            int finalWidth = width;
            source.sendSuccess(() -> Component.literal("Converting into maps..."), false);

            CompletableFuture.supplyAsync(() -> MapRenderer.render(image, mode, finalWidth, finalHeight)).thenAcceptAsync(mapImage -> {
                var items = MapRenderer.toVanillaItems(mapImage, source.getLevel(), input);
                giveToPlayer(player, items, input, finalWidth, finalHeight);
                source.sendSuccess(() -> Component.literal("Done!"), false);
            }, source.getServer());
            return null;
        }, source.getServer());

        return 1;
    }

    private int createMapFromFolder(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        Player player = source.getPlayer();
        DitherMode mode;
        String modeStr = StringArgumentType.getString(context, "mode");
        try {
            mode = DitherMode.fromString(modeStr);
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(() -> "Invalid dither mode '" + modeStr + "'").create();
        }

        String input = StringArgumentType.getString(context, "path");

        source.sendSuccess(() -> Component.literal("Getting image..."), false);

        var list = new ArrayList<ItemStack>();

        for (var image : getImageFromFolder(input)) {
            int width;
            int height;

            try {
                width = IntegerArgumentType.getInteger(context, "width");
                height = IntegerArgumentType.getInteger(context, "height");
            } catch (Throwable e) {
                width = image.getWidth();
                height = image.getHeight();
            }

            int finalHeight = height;
            int finalWidth = width;
            source.sendSuccess(() -> Component.literal("Converting into maps..."), false);

            var mapImage = MapRenderer.render(image, mode, finalWidth, finalHeight);
            var items = MapRenderer.toVanillaItems(mapImage, source.getLevel(), input);
            list.add(toSingleStack(items, input, width, height));
        }
        var bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(list));
        player.addItem(bundle);

        return 1;
    }

    public static void giveToPlayer(Player player, List<ItemStack> items, String input, int width, int height) {
        player.addItem(toSingleStack(items, input, width, height));
    }

    public static ItemStack toSingleStack(List<ItemStack> items, String input, int width, int height) {
        if (items.size() == 1) {
            return items.get(0);
        } else {
            var bundle = new ItemStack(Items.BUNDLE);
            bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
            bundle.set(DataComponents.CUSTOM_DATA, CustomData.of(ImageData.CODEC.codec().encodeStart(NbtOps.INSTANCE,
                    ImageData.ofBundle(Mth.ceil(width / 128d), Mth.ceil(height / 128d))).result().orElseThrow().asCompound().orElseThrow()));

            bundle.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(input))));
            bundle.set(DataComponents.ITEM_NAME, Component.literal("Maps").withStyle(ChatFormatting.GOLD));

            return bundle;
        }
    }

    public static boolean clickItemFrame(Player player, InteractionHand hand, ItemFrame itemFrameEntity) {
        var stack = player.getItemInHand(hand);
        var bundleData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().read(ImageData.CODEC);

        if (stack.is(Items.BUNDLE) && bundleData.isPresent() && bundleData.orElseThrow().quickPlace()) {
            var world = itemFrameEntity.level();
            var start = itemFrameEntity.blockPosition();
            var width = bundleData.orElseThrow().width();
            var height = bundleData.orElseThrow().height();

            var frames = new ItemFrame[width * height];

            var facing = itemFrameEntity.getDirection();
            Direction right;
            Direction down;

            int rot;

            if (facing.getAxis() != Direction.Axis.Y) {
                right = facing.getCounterClockWise();
                down = Direction.DOWN;
                rot = 0;
            } else {
                right = player.getDirection().getClockWise();
                if (facing.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
                    down = right.getClockWise();
                    rot = player.getDirection().getOpposite().get2DDataValue();
                } else {
                    down = right.getCounterClockWise();
                    rot = (right.getAxis() == Direction.Axis.Z ? player.getDirection() : player.getDirection().getOpposite()).get2DDataValue();
                }
            }

            var mut = start.mutable();

            for (var x = 0; x < width; x++) {
                for (var y = 0; y < height; y++) {
                    mut.set(start);
                    mut.move(right, x);
                    mut.move(down, y);
                    var entities = world.getEntitiesOfClass(ItemFrame.class, AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(mut)), (entity1) -> entity1.getDirection() == facing && entity1.blockPosition().equals(mut));
                    if (!entities.isEmpty()) {
                        frames[x + y * width] = entities.get(0);
                    }
                }
            }

            for (var map : stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).items()) {
                var mapData = map.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().read(ImageData.CODEC);

                if (mapData.isPresent() && mapData.orElseThrow().isReal()) {
                    map = map.copy();
                    var newData = mapData.orElseThrow().withDirection(right, down, facing);
                    map.set(DataComponents.CUSTOM_DATA, CustomData.of(ImageData.CODEC.codec().encodeStart(NbtOps.INSTANCE, newData).result().orElseThrow().asCompound().orElseThrow()));

                    var frame = frames[mapData.orElseThrow().x() + mapData.orElseThrow().y() * width];

                    if (frame != null && frame.getItem().isEmpty()) {
                        frame.setItem(map);
                        frame.setRotation(rot);
                        frame.setInvisible(true);
                    }
                }
            }

            stack.shrink(1);

            return true;
        }

        return false;
    }

    public static boolean destroyItemFrame(Entity player, ItemFrame itemFrameEntity) {
        var stack = itemFrameEntity.getItem();
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().read(ImageData.CODEC);


        if (stack.getItem() == Items.FILLED_MAP && tag.isPresent() && tag.orElseThrow().right().isPresent()
                && tag.orElseThrow().down().isPresent() && tag.orElseThrow().facing().isPresent()) {
            var xo = tag.orElseThrow().x();
            var yo = tag.orElseThrow().y();
            var width = tag.orElseThrow().width();
            var height = tag.orElseThrow().height();

            Direction right = tag.orElseThrow().right().get();
            Direction down = tag.orElseThrow().down().get();
            Direction facing = tag.orElseThrow().facing().get();

            var world = itemFrameEntity.level();
            var start = itemFrameEntity.blockPosition();

            var mut = start.mutable();

            mut.move(right, -xo);
            mut.move(down, -yo);

            start = mut.immutable();

            for (var x = 0; x < width; x++) {
                for (var y = 0; y < height; y++) {
                    mut.set(start);
                    mut.move(right, x);
                    mut.move(down, y);
                    var entities = world.getEntitiesOfClass(ItemFrame.class, AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(mut)),
                            (entity1) -> entity1.getDirection() == facing && entity1.blockPosition().equals(mut));
                    if (!entities.isEmpty()) {
                        var frame = entities.get(0);

                        // Only apply to frames that contain an image2map map
                        var frameStack = frame.getItem();
                        tag = frameStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().read(ImageData.CODEC);

                        if (frameStack.getItem() == Items.FILLED_MAP && tag.isPresent() && tag.orElseThrow().right().isPresent()
                                && tag.orElseThrow().down().isPresent() && tag.orElseThrow().facing().isPresent()) {
                            frame.setItem(ItemStack.EMPTY, true);
                            frame.setInvisible(false);
                        }
                    }
                }
            }

            return true;
        }

        return false;
    }

    private static boolean isValid(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
