package space.essem.image2map.renderer;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;
import eu.pb4.mapcanvas.api.core.CanvasColor;
import eu.pb4.mapcanvas.api.core.CanvasImage;
import eu.pb4.mapcanvas.api.utils.CanvasUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import space.essem.image2map.Image2Map.DitherMode;
import space.essem.image2map.ImageData;

public class MapRenderer {
    public static CanvasImage render(BufferedImage image, DitherMode mode, int width, int height) {
        Image resizedImage = image.getScaledInstance(width, height, Image.SCALE_DEFAULT);
        BufferedImage resized = convertToBufferedImage(resizedImage);
        int[][] pixels = convertPixelArray(resized);

        var state = new CanvasImage(width, height);

        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                if (mode.equals(DitherMode.FLOYD)) {
                    state.set(i, j, floydDither(pixels, i, j, pixels[j][i]));
                } else {
                    state.set(i, j, CanvasUtils.findClosestColorARGB(pixels[j][i]));
                }
            }
        }

        return state;
    }

    public static List<ItemStack> toVanillaItems(CanvasImage image, ServerLevel world, String url) {
        var xSections = Mth.ceil(image.getWidth() / 128d);
        var ySections = Mth.ceil(image.getHeight() / 128d);

        var xDelta = (xSections * 128 - image.getWidth()) / 2;
        var yDelta = (ySections * 128 - image.getHeight()) / 2;

        var items = new ArrayList<ItemStack>();

        for (int ys = 0; ys < ySections; ys++) {
            for (int xs = 0; xs < xSections; xs++) {
                var id = world.getFreeMapId();
                var state = MapItemSavedData.createFresh(0, 0, (byte) 0, false, false, ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("image2map", "generated")));

                for (int xl = 0; xl < 128; xl++) {
                    for (int yl = 0; yl < 128; yl++) {
                        var x = xl + xs * 128 - xDelta;
                        var y = yl + ys * 128 - yDelta;

                        if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
                            state.colors[xl + yl * 128] = image.getRaw(x, y);
                        }
                    }
                }

                world.setMapData(id, state);

                var stack = new ItemStack(Items.FILLED_MAP);
                stack.set(DataComponents.MAP_ID, id);
                var data = ImageData.ofSimple(xs, ys, xSections, ySections);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(ImageData.CODEC.codec().encodeStart(NbtOps.INSTANCE, data).result().orElseThrow().asCompound().orElseThrow()));
                stack.set(DataComponents.LORE, new ItemLore(List.of(
                        Component.literal(xs + " / " + ys).withStyle(ChatFormatting.GRAY),
                        Component.literal(url)
                )));
                items.add(stack);
            }
        }

        return items;
    }

    /*public static ItemStack render(BufferedImage image, DitherMode mode, ServerWorld world, int width, int height,
                                   PlayerEntity player) {
        // mojang removed the ability to set a map as locked via the "locked" field in
        // 1.17, so we create and apply our own MapState instead
        ItemStack stack = new ItemStack(Items.FILLED_MAP);
        int id = world.getNextMapId();
        NbtCompound nbt = new NbtCompound();

        nbt.putString("dimension", world.getRegistryKey().getValue().toString());
        nbt.putInt("xCenter", (int) 0);
        nbt.putInt("zCenter", (int) 0);
        nbt.putBoolean("locked", true);
        nbt.putBoolean("unlimitedTracking", false);
        nbt.putBoolean("trackingPosition", false);
        nbt.putByte("scale", (byte) 3);
        MapState state = MapState.fromNbt(nbt);
        world.putMapState(FilledMapItem.getMapName(id), state);
        stack.getOrCreateNbt().putInt("map", id);

        Image resizedImage = image.getScaledInstance(128, 128, Image.SCALE_DEFAULT);
        BufferedImage resized = convertToBufferedImage(resizedImage);
        int width = resized.getWidth();
        int height = resized.getHeight();
        int[][] pixels = convertPixelArray(resized);
        MapColor[] mapColors = MapColor.COLORS;
        Color imageColor;
        mapColors = Arrays.stream(mapColors).filter(Objects::nonNull).toArray(MapColor[]::new);

        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                imageColor = new Color(pixels[j][i], true);
                if (mode.equals(DitherMode.FLOYD))
                    state.colors[i + j * width] = (byte) floydDither(mapColors, pixels, i, j, imageColor);
                else
                    state.colors[i + j * width] = (byte) nearestColor(mapColors, imageColor);
            }
        }
        return stack;
    }*/

    private static CanvasColor floydDither(int[][] pixels, int x, int y, int imageColor) {
        var closestColor = CanvasUtils.findClosestColorARGB(imageColor);
        var palletedColor = closestColor.getRgbColor();

        var errorR = ARGB.red(imageColor) - ARGB.red(palletedColor);
        var errorG = ARGB.green(imageColor) - ARGB.green(palletedColor);
        var errorB = ARGB.blue(imageColor) - ARGB.blue(palletedColor);
        if (pixels[0].length > x + 1) {
            pixels[y][x + 1] = applyError(pixels[y][x + 1], errorR, errorG, errorB, 7.0 / 16.0);
        }
        if (pixels.length > y + 1) {
            if (x > 0) {
                pixels[y + 1][x - 1] = applyError(pixels[y + 1][x - 1], errorR, errorG, errorB, 3.0 / 16.0);
            }
            pixels[y + 1][x] = applyError(pixels[y + 1][x], errorR, errorG, errorB, 5.0 / 16.0);
            if (pixels[0].length > x + 1) {
                pixels[y + 1][x + 1] = applyError(pixels[y + 1][x + 1], errorR, errorG, errorB, 1.0 / 16.0);
            }
        }

        return closestColor;
    }

    private static int applyError(int pixelColor, int errorR, int errorG, int errorB, double quantConst) {
        int pR = clamp( ARGB.red(pixelColor) + (int) ((double) errorR * quantConst), 0, 255);
        int pG = clamp(ARGB.green(pixelColor) + (int) ((double) errorG * quantConst), 0, 255);
        int pB = clamp(ARGB.blue(pixelColor) + (int) ((double) errorB * quantConst), 0, 255);
        return ARGB.color(ARGB.alpha(pixelColor), pR, pG, pB);
    }

    private static int clamp(int i, int min, int max) {
        if (min > max)
            throw new IllegalArgumentException("max value cannot be less than min value");
        if (i < min)
            return min;
        if (i > max)
            return max;
        return i;
    }

    private static int[][] convertPixelArray(BufferedImage image) {

        final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        final int width = image.getWidth();
        final int height = image.getHeight();

        int[][] result = new int[height][width];
        final int pixelLength = 4;
        for (int pixel = 0, row = 0, col = 0; pixel + 3 < pixels.length; pixel += pixelLength) {
            int argb = 0;
            argb += (((int) pixels[pixel] & 0xff) << 24); // alpha
            argb += ((int) pixels[pixel + 1] & 0xff); // blue
            argb += (((int) pixels[pixel + 2] & 0xff) << 8); // green
            argb += (((int) pixels[pixel + 3] & 0xff) << 16); // red
            result[row][col] = argb;
            col++;
            if (col == width) {
                col = 0;
                row++;
            }
        }

        return result;
    }

    private static BufferedImage convertToBufferedImage(Image image) {
        BufferedImage newImage = new BufferedImage(image.getWidth(null), image.getHeight(null),
                BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = newImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return newImage;
    }
}