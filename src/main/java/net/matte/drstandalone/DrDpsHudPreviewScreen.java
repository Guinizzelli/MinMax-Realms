package net.matte.drstandalone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class DrDpsHudPreviewScreen extends Screen {
    private static final int BUTTON_W = 140;
    private static final int BUTTON_H = 20;

    private final Screen parent;
    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    public DrDpsHudPreviewScreen(Screen parent) {
        super(Text.literal("DPS HUD Preview"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonX = this.width / 2 - BUTTON_W / 2;
        int buttonY = this.height - 34;
        addDrawableChild(ButtonWidget.builder(Text.literal("Return"), button -> close())
            .dimensions(buttonX, buttonY, BUTTON_W, BUTTON_H)
            .build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;

        DrStandaloneConfig config = DrStandaloneMod.config();
        int previewWidth = scaledWidth(config.dpsHudScale);
        int previewHeight = scaledHeight(config.dpsHudScale);
        if (isInside(mouseX, mouseY, config.dpsHudX, config.dpsHudY, previewWidth, previewHeight)) {
            dragging = true;
            dragOffsetX = (int) mouseX - config.dpsHudX;
            dragOffsetY = (int) mouseY - config.dpsHudY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!dragging || button != 0) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);

        DrStandaloneConfig config = DrStandaloneMod.config();
        int previewWidth = scaledWidth(config.dpsHudScale);
        int previewHeight = scaledHeight(config.dpsHudScale);
        int maxX = Math.max(0, this.width - previewWidth);
        int maxY = Math.max(0, this.height - previewHeight - 44);
        config.dpsHudX = clamp((int) mouseX - dragOffsetX, 0, maxX);
        config.dpsHudY = clamp((int) mouseY - dragOffsetY, 0, maxY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        DrStandaloneConfig config = DrStandaloneMod.config();

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Drag the DPS meter to place it"), this.width / 2, 14, 0xFFE7D39D);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("ESC or Return goes back to config"), this.width / 2, 28, 0xFFB8C1CB);
        DpsMeterFeature.renderPreview(context, config.dpsHudX, config.dpsHudY, config.dpsHudScale);

        super.render(context, mouseX, mouseY, delta);
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int scaledWidth(double scale) {
        return Math.max(1, (int) Math.round(DpsMeterFeature.PREVIEW_WIDTH * scale));
    }

    private static int scaledHeight(double scale) {
        return Math.max(1, (int) Math.round(DpsMeterFeature.PREVIEW_HEIGHT * scale));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
