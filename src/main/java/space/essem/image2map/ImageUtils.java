package space.essem.image2map;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

public class ImageUtils {
    private static BufferedImage scaleImage(int width, int height, BufferedImage input, boolean fitAll) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics g = output.createGraphics();
        double imgAspect = (double) input.getHeight() / input.getWidth();

        int outputWidth = output.getWidth();
        int outputHeight = output.getHeight();

        double canvasAspect = (double) outputHeight / outputWidth;

        int x1 = 0;
        int y1 = 0;

        // XOR conditionally negates the IF statement
        // (A XOR true == !A, A XOR false == A)
        if (canvasAspect > imgAspect ^ !fitAll) {
            y1 = outputHeight;
            outputHeight = (int) (outputWidth * imgAspect);
            y1 = (y1 - outputHeight) / 2;
        } else {
            x1 = outputWidth;
            outputWidth = (int) (outputHeight / imgAspect);
            x1 = (x1 - outputWidth) / 2;
        }
        int x2 = outputWidth + x1;
        int y2 = outputHeight + y1;

        g.drawImage(input, x1, y1, x2, y2, 0, 0, input.getWidth(), input.getHeight(), null);
        return output;
    }

    @Nullable
    static BufferedImage getImage(String urlStr, ServerCommandSource source) {
        BufferedImage image;
        try {
            if (isValid(urlStr)) {
                URL url = new URL(urlStr);
                URLConnection connection = url.openConnection();
                connection.setRequestProperty("User-Agent", "Image2Map mod");
                connection.connect();
                image = ImageIO.read(connection.getInputStream());
            } else if (Image2Map.CONFIG.allowLocalFiles) {
                File file = new File(urlStr);
                image = ImageIO.read(file);
            } else {
                image = null;
            }
        } catch (IOException e) {
            source.sendFeedback(new LiteralText("That doesn't seem to be a valid image."), false);
            return null;
        }

        if (image == null) {
            source.sendFeedback(new LiteralText("That doesn't seem to be a valid image."), false);
            return null;
        }
        return image;
    }

    public static String getImageName(String path) {
        if (ImageUtils.isValid(path)) {
            String fileName;
            try {
                URL url = new URL(path);
                fileName = url.getFile();
                int start = fileName.lastIndexOf('/');
                if (start > 0 && start + 1 < fileName.length())
                    fileName = fileName.substring(start + 1);
                int end = fileName.indexOf('?');
                if (end > 0)
                    fileName = fileName.substring(0, end);
                return fileName;
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        } else {
            int index = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
            if (index > 0)
                return path.substring(index);
            else
                return path;
        }
        return null;
    }

    static boolean isValid(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static BufferedImage scaleImage(Image2Map.ScaleMode scale, int countX, int countY, BufferedImage sourceImage) {
        BufferedImage img = new BufferedImage(countX * 128, countY * 128, BufferedImage.TYPE_INT_ARGB);
        switch (scale) {
            case STRETCH:
                img.createGraphics().drawImage(sourceImage, 0, 0, img.getWidth(), img.getHeight(), null);
                break;
            case FIT:
                return scaleImage(countX * 128, countY * 128, sourceImage, true);
            case FILL:
                return scaleImage(countX * 128, countY * 128, sourceImage, false);
            default:
                throw new RuntimeException("impossible scale mode!");
        }
        img.flush();
        return img;
    }
}
