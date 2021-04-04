package space.essem.image2map.renderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Arrays;
import java.util.Objects;

import net.minecraft.block.MaterialColor;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;

import static space.essem.image2map.Image2Map.DitherMode;

public class MapRenderer {
    private static final double shadeCoeffs[] = { 0.71, 0.86, 1.0, 0.53 };

    private static double distance(double[] vectorA, double[] vectorB) {
        return Math.sqrt(Math.pow(vectorA[0] - vectorB[0], 2) + Math.pow(vectorA[1] - vectorB[1], 2)
                + Math.pow(vectorA[2] - vectorB[2], 2));
    }

    private static double[] applyShade(double[] color, int ind) {
        double coeff = shadeCoeffs[ind];
        return new double[] { color[0] * coeff, color[1] * coeff, color[2] * coeff };
    }

    public static ItemStack render(BufferedImage image, DitherMode mode, ServerWorld world, double x, double z) {
        ItemStack stack = FilledMapItem.createMap(world, (int) x, (int) z, (byte) 3, false, false);
        MapState state = FilledMapItem.getMapState(stack, world);
        assert state != null;
        state.locked = true;
        Image resizedImage = image.getScaledInstance(128, 128, Image.SCALE_DEFAULT);
        BufferedImage resized = convertToBufferedImage(resizedImage);
        int width = resized.getWidth();
        int height = resized.getHeight();
        int[][] pixels = convertPixelArray(resized);
        MaterialColor[] mapColors = MaterialColor.COLORS;
        Color imageColor;
        mapColors = Arrays.stream(mapColors).filter(Objects::nonNull).toArray(MaterialColor[]::new);

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
    }
    private static Color mapColorToRGBColor(MaterialColor[] colors, int color) {
        Color mcColor = new Color(colors[color >> 2].color);
        double[] mcColorVec = { 
            (double) mcColor.getRed(), 
            (double) mcColor.getGreen(),
            (double) mcColor.getBlue()
        };
        double coeff = shadeCoeffs[color & 3];
        return new Color((int)(mcColorVec[0] * coeff), (int)(mcColorVec[1] * coeff), (int)(mcColorVec[2] * coeff));
    }

    private static class NegatableColor {
        public final int r;
        public final int g;
        public final int b;
        public NegatableColor(int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }
    private static int floydDither(MaterialColor[] mapColors, int[][] pixels, int x, int y, Color imageColor) {
        // double[] imageVec = { (double) imageColor.getRed() / 255.0, (double) imageColor.getGreen() / 255.0,
        //         (double) imageColor.getBlue() / 255.0 };
        int colorIndex = nearestColor(mapColors, imageColor);
        Color palletedColor = mapColorToRGBColor(mapColors, colorIndex);
        NegatableColor error = new NegatableColor(
            imageColor.getRed() - palletedColor.getRed(),
            imageColor.getGreen() - palletedColor.getGreen(),
            imageColor.getBlue() - palletedColor.getBlue());
        if (pixels[0].length > x + 1) {
            Color pixelColor = new Color(pixels[y][x + 1], true);
            pixels[y][x + 1] = applyError(pixelColor, error, 7.0 / 16.0);
        }
        if (pixels.length > y + 1) {
            if (x > 0) {
                Color pixelColor = new Color(pixels[y + 1][x - 1], true);
                pixels[y + 1][x - 1] = applyError(pixelColor, error, 3.0 / 16.0);
            }
            Color pixelColor = new Color(pixels[y + 1][x], true);
            pixels[y + 1][x] = applyError(pixelColor, error, 5.0 / 16.0);
            if (pixels[0].length > x + 1) {
                pixelColor = new Color(pixels[y + 1][x + 1], true);
                pixels[y + 1][x + 1] = applyError(pixelColor, error, 1.0 / 16.0);
            }
        }
            
        
        return colorIndex;
    }

    private static int applyError(Color pixelColor, NegatableColor error, double quantConst) {
        int pR = clamp(pixelColor.getRed() + (int)((double)error.r * quantConst), 0, 255);
        int pG = clamp(pixelColor.getGreen() + (int)((double)error.g * quantConst), 0, 255);
        int pB = clamp(pixelColor.getBlue() + (int)((double)error.b * quantConst), 0, 255);
        return new Color(pR, pG, pB, pixelColor.getAlpha()).getRGB();
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

    private static int nearestColor(MaterialColor[] colors, Color imageColor) {
        double[] imageVec = { (double) imageColor.getRed() / 255.0, (double) imageColor.getGreen() / 255.0,
                (double) imageColor.getBlue() / 255.0 };
        int best_color = 0;
        double lowest_distance = 10000;
        for (int k = 0; k < colors.length; k++) {
            Color mcColor = new Color(colors[k].color);
            double[] mcColorVec = { (double) mcColor.getRed() / 255.0, (double) mcColor.getGreen() / 255.0,
                    (double) mcColor.getBlue() / 255.0 };
            for (int shadeInd = 0; shadeInd < shadeCoeffs.length; shadeInd++) {
                double distance = distance(imageVec, applyShade(mcColorVec, shadeInd));
                if (distance < lowest_distance) {
                    lowest_distance = distance;
                    // todo: handle shading with alpha values other than 255
                    if (k == 0 && imageColor.getAlpha() == 255) {
                        best_color = 119;
                    } else {
                        best_color = k * shadeCoeffs.length + shadeInd;
                    }
                }
            }
        }
        return best_color;
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

    public static BufferedImage convertToBufferedImage(Image image) {
        BufferedImage newImage = new BufferedImage(image.getWidth(null), image.getHeight(null),
                BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = newImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return newImage;
    }
}