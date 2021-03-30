package space.essem.image2map;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

public class ImageUtils {
    static void scaleImage(BufferedImage output, BufferedImage input, boolean fitAll) {
        Graphics g = output.createGraphics();
        double imgAspect = (double) input.getHeight() / input.getWidth();

        int outputWidth = output.getWidth();
        int outputHeight = output.getHeight();

        double canvasAspect = (double) outputHeight / outputWidth;

        int x1 = 0;
        int y1 = 0;

        // XOR conditionally negates the IF statement (A XOR true == !A, A XOR false == A)
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

    static boolean isValid(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
