package space.essem.image2map;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.network.UploadPayloads;
import space.essem.image2map.upload.ServerImageTasks;

import java.util.List;

public class Image2Map implements ModInitializer {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final Image2MapConfig CONFIG = Image2MapConfig.loadOrCreateConfig();
    public static final ServerImageTasks TASKS = new ServerImageTasks();

    @Override
    public void onInitialize() {
        UploadPayloads.register();
        TASKS.register();
        ImageCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> CardboardWarning.checkAndAnnounce());
    }

    public enum DitherMode {
        NONE, FLOYD;
        public static DitherMode fromString(String value) {
            if (value.equalsIgnoreCase("none")) return NONE;
            if (value.equalsIgnoreCase("dither") || value.equalsIgnoreCase("floyd")) return FLOYD;
            throw new IllegalArgumentException("Invalid dither mode: " + value);
        }
    }

    public static void giveToPlayer(Player player, List<ItemStackTemplate> items, String input, int width, int height) {
        player.addItem(toSingleStack(items, input, width, height).create());
    }

    public static ItemStackTemplate toSingleStack(List<ItemStackTemplate> items, String input, int width, int height) {
        if (items.size() == 1) {
            return items.get(0);
        } else {
            var bundle = DataComponentPatch.builder();
            bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
            bundle.set(DataComponents.CUSTOM_DATA, CustomData.of(ImageData.CODEC.codec().encodeStart(NbtOps.INSTANCE,
                    ImageData.ofBundle(Mth.ceil(width / 128d), Mth.ceil(height / 128d))).result().orElseThrow().asCompound().orElseThrow()));

            bundle.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(input))));
            bundle.set(DataComponents.ITEM_NAME, Component.literal("Maps").withStyle(ChatFormatting.GOLD));

            return new ItemStackTemplate(Items.BUNDLE, bundle.build());
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
                    var s = map.create();
                    var newData = mapData.orElseThrow().withDirection(right, down, facing);
                    s.set(DataComponents.CUSTOM_DATA, CustomData.of(ImageData.CODEC.codec().encodeStart(NbtOps.INSTANCE, newData).result().orElseThrow().asCompound().orElseThrow()));

                    var frame = frames[mapData.orElseThrow().x() + mapData.orElseThrow().y() * width];

                    if (frame != null && frame.getItem().isEmpty()) {
                        frame.setItem(s);
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

}
