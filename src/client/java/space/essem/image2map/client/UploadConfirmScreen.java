package space.essem.image2map.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** ESC and replacement by another screen both cancel an unresolved confirmation. */
final class UploadConfirmScreen extends ConfirmScreen {
    private final AtomicBoolean answered;
    private final BooleanConsumer callback;

    UploadConfirmScreen(String path, BooleanConsumer callback) {
        this(path, callback, new AtomicBoolean());
    }

    private UploadConfirmScreen(String path, BooleanConsumer callback, AtomicBoolean answered) {
        super(value -> {
            if (!answered.getAndSet(true)) callback.accept(value);
        }, Component.translatable("image2map.upload.confirm.title"),
                Component.translatable("image2map.upload.confirm.message", path),
                Component.translatable("image2map.upload.confirm.yes"), Component.translatable("gui.cancel"));
        this.answered = answered;
        this.callback = callback;
    }

    @Override public void onClose() {
        if (!answered.getAndSet(true)) callback.accept(false);
    }

    @Override public void removed() {
        if (!answered.getAndSet(true)) callback.accept(false);
        super.removed();
    }

    @Override public boolean isPauseScreen() { return false; }
}
