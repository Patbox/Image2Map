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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BundleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.Mutable;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
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

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;


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

    private int openPreview(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        String input = StringArgumentType.getString(context, "path");

        source.sendFeedback(() -> Text.literal("Getting image..."), false);

        getImage(input).orTimeout(20, TimeUnit.SECONDS).handleAsync((image, ex) -> {
            if (ex instanceof TimeoutException) {
                source.sendFeedback(() -> Text.literal("Downloading or reading of the image took too long!"), false);
                return null;
            } else if (ex != null) {
                if (ex instanceof RuntimeException ru && ru.getCause() != null) {
                    ex = ru.getCause();
                }

                Throwable finalEx = ex;
                source.sendFeedback(() -> Text.literal("The image isn't valid (hover for more info)!")
                        .setStyle(Style.EMPTY.withColor(Formatting.RED).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(finalEx.getMessage())))), false);
                return null;
            }

            if (image == null) {
                source.sendFeedback(() -> Text.literal("That doesn't seem to be a valid image (unknown reason)!"), false);
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

    class DitherModeSuggestionProvider implements SuggestionProvider<ServerCommandSource> {

        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context,
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

    private int createMap(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();

        PlayerEntity player = source.getPlayer();
        DitherMode mode;
        String modeStr = StringArgumentType.getString(context, "mode");
        try {
            mode = DitherMode.fromString(modeStr);
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(() -> "Invalid dither mode '" + modeStr + "'").create();
        }

        String input = StringArgumentType.getString(context, "path");

        source.sendFeedback(() -> Text.literal("Getting image..."), false);

        getImage(input).orTimeout(20, TimeUnit.SECONDS).handleAsync((image, ex) -> {
            if (ex instanceof TimeoutException) {
                source.sendFeedback(() -> Text.literal("Downloading or reading of the image took too long!"), false);
                return null;
            } else if (ex != null) {
                if (ex instanceof RuntimeException ru && ru.getCause() != null) {
                    ex = ru.getCause();
                }

                Throwable finalEx = ex;
                source.sendFeedback(() -> Text.literal("The image isn't valid (hover for more info)!")
                        .setStyle(Style.EMPTY.withColor(Formatting.RED).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(finalEx.getMessage())))), false);
                return null;
            }

            if (image == null) {
                source.sendFeedback(() -> Text.literal("That doesn't seem to be a valid image (unknown reason)!"), false);
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
            source.sendFeedback(() -> Text.literal("Converting into maps..."), false);

            CompletableFuture.supplyAsync(() -> MapRenderer.render(image, mode, finalWidth, finalHeight)).thenAcceptAsync(mapImage -> {
                var items = MapRenderer.toVanillaItems(mapImage, source.getWorld(), input);
                giveToPlayer(player, items, input, finalWidth, finalHeight);
                source.sendFeedback(() -> Text.literal("Done!"), false);
            }, source.getServer());
            return null;
        }, source.getServer());

        return 1;
    }

    private int createMapFromFolder(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();

        PlayerEntity player = source.getPlayer();
        DitherMode mode;
        String modeStr = StringArgumentType.getString(context, "mode");
        try {
            mode = DitherMode.fromString(modeStr);
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(() -> "Invalid dither mode '" + modeStr + "'").create();
        }

        String input = StringArgumentType.getString(context, "path");

        source.sendFeedback(() -> Text.literal("Getting image..."), false);

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
            source.sendFeedback(() -> Text.literal("Converting into maps..."), false);

            var mapImage = MapRenderer.render(image, mode, finalWidth, finalHeight);
            var items = MapRenderer.toVanillaItems(mapImage, source.getWorld(), input);
            list.add(toSingleStack(items, input, width, height));
        }
        var bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponentTypes.BUNDLE_CONTENTS, new BundleContentsComponent(list));
        player.giveItemStack(bundle);

        return 1;
    }

    public static void giveToPlayer(PlayerEntity player, List<ItemStack> items, String input, int width, int height) {
        player.giveItemStack(toSingleStack(items, input, width, height));
    }

    public static ItemStack toSingleStack(List<ItemStack> items, String input, int width, int height) {
        if (items.size() == 1) {
            return items.get(0);
        } else {
            var bundle = new ItemStack(Items.BUNDLE);
            bundle.set(DataComponentTypes.BUNDLE_CONTENTS, new BundleContentsComponent(items));
            bundle.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT.with(NbtOps.INSTANCE, ImageData.CODEC,
                    ImageData.ofBundle(MathHelper.ceil(width / 128d), MathHelper.ceil(height / 128d))).getOrThrow());

            bundle.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal(input))));
            bundle.set(DataComponentTypes.ITEM_NAME, Text.literal("Maps").formatted(Formatting.GOLD));

            return bundle;
        }
    }

    public static boolean clickItemFrame(PlayerEntity player, Hand hand, ItemFrameEntity itemFrameEntity) {
        var stack = player.getStackInHand(hand);
        var bundleData = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).get(ImageData.CODEC);

        if (stack.isOf(Items.BUNDLE) && bundleData.isSuccess() && bundleData.getOrThrow().quickPlace()) {
            var world = itemFrameEntity.getWorld();
            var start = itemFrameEntity.getBlockPos();
            var width = bundleData.getOrThrow().width();
            var height = bundleData.getOrThrow().height();

            var frames = new ItemFrameEntity[width * height];

            var facing = itemFrameEntity.getHorizontalFacing();
            Direction right;
            Direction down;

            int rot;

            if (facing.getAxis() != Direction.Axis.Y) {
                right = facing.rotateYCounterclockwise();
                down = Direction.DOWN;
                rot = 0;
            } else {
                right = player.getHorizontalFacing().rotateYClockwise();
                if (facing.getDirection() == Direction.AxisDirection.POSITIVE) {
                    down = right.rotateYClockwise();
                    rot = player.getHorizontalFacing().getOpposite().getHorizontal();
                } else {
                    down = right.rotateYCounterclockwise();
                    rot = (right.getAxis() == Direction.Axis.Z ? player.getHorizontalFacing() : player.getHorizontalFacing().getOpposite()).getHorizontal();
                }
            }

            var mut = start.mutableCopy();

            for (var x = 0; x < width; x++) {
                for (var y = 0; y < height; y++) {
                    mut.set(start);
                    mut.move(right, x);
                    mut.move(down, y);
                    var entities = world.getEntitiesByClass(ItemFrameEntity.class, Box.from(Vec3d.of(mut)), (entity1) -> entity1.getHorizontalFacing() == facing && entity1.getBlockPos().equals(mut));
                    if (!entities.isEmpty()) {
                        frames[x + y * width] = entities.get(0);
                    }
                }
            }

            for (var map : stack.getOrDefault(DataComponentTypes.BUNDLE_CONTENTS, BundleContentsComponent.DEFAULT).iterate()) {
                var mapData = map.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).get(ImageData.CODEC);

                if (mapData.isSuccess() && mapData.getOrThrow().isReal()) {
                    map = map.copy();
                    var newData = mapData.getOrThrow().withDirection(right, down, facing);
                    map.apply(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT, x -> x.with(NbtOps.INSTANCE, ImageData.CODEC, newData).getOrThrow());

                    var frame = frames[mapData.getOrThrow().x() + mapData.getOrThrow().y() * width];

                    if (frame != null && frame.getHeldItemStack().isEmpty()) {
                        frame.setHeldItemStack(map);
                        frame.setRotation(rot);
                        frame.setInvisible(true);
                    }
                }
            }

            stack.decrement(1);

            return true;
        }

        return false;
    }

    public static boolean destroyItemFrame(Entity player, ItemFrameEntity itemFrameEntity) {
        var stack = itemFrameEntity.getHeldItemStack();
        var tag = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).get(ImageData.CODEC);


        if (stack.getItem() == Items.FILLED_MAP && tag.isSuccess() && tag.getOrThrow().right().isPresent()
                && tag.getOrThrow().down().isPresent() && tag.getOrThrow().facing().isPresent()) {
            var xo = tag.getOrThrow().x();
            var yo = tag.getOrThrow().y();
            var width = tag.getOrThrow().width();
            var height = tag.getOrThrow().height();

            Direction right = tag.getOrThrow().right().get();
            Direction down = tag.getOrThrow().down().get();
            Direction facing = tag.getOrThrow().facing().get();

            var world = itemFrameEntity.getWorld();
            var start = itemFrameEntity.getBlockPos();

            var mut = start.mutableCopy();

            mut.move(right, -xo);
            mut.move(down, -yo);

            start = mut.toImmutable();

            for (var x = 0; x < width; x++) {
                for (var y = 0; y < height; y++) {
                    mut.set(start);
                    mut.move(right, x);
                    mut.move(down, y);
                    var entities = world.getEntitiesByClass(ItemFrameEntity.class, Box.from(Vec3d.of(mut)),
                            (entity1) -> entity1.getHorizontalFacing() == facing && entity1.getBlockPos().equals(mut));
                    if (!entities.isEmpty()) {
                        var frame = entities.get(0);

                        // Only apply to frames that contain an image2map map
                        var frameStack = frame.getHeldItemStack();
                        tag = frameStack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).get(ImageData.CODEC);

                        if (frameStack.getItem() == Items.FILLED_MAP && tag.isSuccess() && tag.getOrThrow().right().isPresent()
                                && tag.getOrThrow().down().isPresent() && tag.getOrThrow().facing().isPresent()) {
                            frame.setHeldItemStack(ItemStack.EMPTY, true);
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
