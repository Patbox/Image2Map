package space.essem.image2map.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class UploadProgressScreen extends Screen {
    private final Runnable cancel;
    private boolean resolved;
    private Component message = Component.translatable("image2map.upload.selecting");

    UploadProgressScreen(Runnable cancel) {
        super(Component.translatable("image2map.upload.title"));
        this.cancel = cancel;
    }

    void message(Component value) { message = value; }
    void resolve() { resolved = true; }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(width / 2 - 75, height / 2 + 25, 150, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, height / 2 - 35, 0xffffffff);
        graphics.centeredText(font, message, width / 2, height / 2 - 10, 0xffffffff);
    }

    @Override public void onClose() {
        if (!resolved) {
            resolved = true;
            cancel.run();
        }
    }
    @Override public void removed() { onClose(); super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
}
