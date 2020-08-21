package space.essem.image2map.renderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;

import net.minecraft.block.MaterialColor;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.server.world.ServerWorld;

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

  public static ItemStack render(File file, ServerWorld world, double x, double z, PlayerEntity player) {
    ItemStack stack = FilledMapItem.createMap(world, (int) x, (int) z, (byte) 3, false, false);
    MapState state = FilledMapItem.getMapState(stack, world);
    state.locked = true;
    try {
      BufferedImage image = ImageIO.read(file);
      Image resizedImage = image.getScaledInstance(128, 128, Image.SCALE_DEFAULT);
      BufferedImage resized = convertToBufferedImage(resizedImage);
      int width = resized.getWidth();
      int height = resized.getHeight();
      int[][] pixels = convertPixelArray(resized);
      MaterialColor[] colors = MaterialColor.COLORS;
      Color imageColor;
      colors = Arrays.stream(colors).filter(s -> s != null).toArray(MaterialColor[]::new);

      for (int i = 0; i < width; i++) {
        for (int j = 0; j < height; j++) {
          imageColor = new Color(pixels[j][i], true);
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
          state.colors[i + j * width] = (byte) best_color;
        }
      }
      state.update(player, stack);
    } catch (IOException e) {
      System.out.println(e);
    }
    return stack;
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