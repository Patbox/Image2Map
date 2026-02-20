package space.essem.image2map;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import javax.imageio.ImageIO;

public class ImageIoBootstrap implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            ImageIO.scanForPlugins();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
