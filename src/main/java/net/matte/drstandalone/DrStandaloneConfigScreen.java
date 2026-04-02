package net.matte.drstandalone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.Locale;

public class DrStandaloneConfigScreen extends Screen {
    private final Screen parent;

    public DrStandaloneConfigScreen(Screen parent) {
        super(Text.literal("DR Standalone Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        DrStandaloneConfig config = DrStandaloneMod.config();
        int left = this.width / 2 - 155;
        int right = this.width / 2 + 5;
        int y = 42;

        addDrawableChild(new TextWidget(left, 18, 220, 14, Text.literal("DR Standalone"), this.textRenderer));

        addDrawableChild(toggleButton(left, y, 150, "DR Item Rolls", () -> config.itemRollsEnabled, value -> config.itemRollsEnabled = value));
        addDrawableChild(toggleButton(right, y, 150, "DPS Meter", () -> config.dpsMeterEnabled, value -> config.dpsMeterEnabled = value));
        y += 24;
        addDrawableChild(toggleButton(left, y, 150, "Gem Meter", () -> config.gemMeterEnabled, value -> config.gemMeterEnabled = value));
        addDrawableChild(toggleButton(right, y, 150, "Show % on level", () -> config.showOverallOnLevel, value -> config.showOverallOnLevel = value));
        y += 24;
        addDrawableChild(toggleButton(left, y, 150, "Hide durability", () -> config.hideDurability, value -> config.hideDurability = value));
        addDrawableChild(toggleButton(right, y, 150, "Hide item id", () -> config.hideItemId, value -> config.hideItemId = value));
        y += 24;
        addDrawableChild(toggleButton(left, y, 150, "Hide components", () -> config.hideComponentsLine, value -> config.hideComponentsLine = value));
        addDrawableChild(toggleButton(right, y, 150, "Breakdown", () -> config.statBreakdown, value -> config.statBreakdown = value));
        y += 30;

        addDrawableChild(stepButton(left, y, 150, () -> "DPS X: " + config.dpsHudX, -10, 10, value -> config.dpsHudX += value));
        addDrawableChild(stepButton(right, y, 150, () -> "DPS Y: " + config.dpsHudY, -10, 10, value -> config.dpsHudY += value));
        y += 24;
        addDrawableChild(stepButton(left, y, 150, () -> "DPS Scale: " + format(config.dpsHudScale), -0.1, 0.1, value -> config.dpsHudScale = clamp(config.dpsHudScale + value, 0.5, 4.0)));
        addDrawableChild(cycleButton(right, y, 150, () -> "Class: " + config.classProfile, new String[]{"None", "Warrior", "Rogue", "Paladin"}, value -> config.classProfile = value, config.classProfile));
        y += 24;
        addDrawableChild(cycleButton(left, y, 150, () -> "Tier: " + config.targetTier, new String[]{"T1", "T2", "T3", "T4", "T5"}, value -> config.targetTier = value, config.targetTier));
        addDrawableChild(stepButton(right, y, 150, () -> "Target HP: " + format(config.targetHpPercent) + "%", -10, 10, value -> config.targetHpPercent = clamp(config.targetHpPercent + value, 0, 100)));
        y += 24;
        addDrawableChild(stepButton(left, y, 150, () -> "Gem X: " + config.gemHudX, -10, 10, value -> config.gemHudX += value));
        addDrawableChild(stepButton(right, y, 150, () -> "Gem Y: " + config.gemHudY, -10, 10, value -> config.gemHudY += value));
        y += 24;
        addDrawableChild(stepButton(left, y, 150, () -> "Gem Scale: " + format(config.gemHudScale), -0.1, 0.1, value -> config.gemHudScale = clamp(config.gemHudScale + value, 0.5, 4.0)));
        addDrawableChild(cycleButton(right, y, 150, () -> "Roll Style: " + config.inlineStyle, new String[]{"Percent", "Range"}, value -> config.inlineStyle = value, config.inlineStyle));
        y += 34;

        addDrawableChild(ButtonWidget.builder(Text.literal("Save & Close"), button -> {
            config.save();
            close();
        }).dimensions(this.width / 2 - 75, y, 150, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Reset HUD"), button -> {
            resetHud(config);
            this.clearAndInit();
        }).dimensions(this.width / 2 - 75, y + 24, 150, 20).tooltip(Tooltip.of(Text.literal("Reset HUD positions and scale"))).build());
    }

    private ButtonWidget toggleButton(int x, int y, int width, String label, BoolGetter getter, BoolSetter setter) {
        return ButtonWidget.builder(Text.literal(label + ": " + onOff(getter.get())), button -> {
            setter.set(!getter.get());
            button.setMessage(Text.literal(label + ": " + onOff(getter.get())));
        }).dimensions(x, y, width, 20).build();
    }

    private ButtonWidget cycleButton(int x, int y, int width, TextGetter getter, String[] values, StringSetter setter, String current) {
        return ButtonWidget.builder(Text.literal(getter.get()), button -> {
            int index = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(currentValue(getter.get()))) {
                    index = i;
                    break;
                }
            }
            setter.set(values[(index + 1) % values.length]);
            button.setMessage(Text.literal(getter.get()));
        }).dimensions(x, y, width, 20).build();
    }

    private ButtonWidget stepButton(int x, int y, int width, TextGetter getter, double minus, double plus, DoubleSetter setter) {
        return ButtonWidget.builder(Text.literal(getter.get()), button -> {
            if (hasShiftDown()) setter.set(plus);
            else setter.set(minus);
            button.setMessage(Text.literal(getter.get()));
        }).dimensions(x, y, width, 20).tooltip(Tooltip.of(Text.literal("Click = - , Shift+Click = +"))).build();
    }

    private static String currentValue(String label) {
        int idx = label.indexOf(':');
        return idx >= 0 ? label.substring(idx + 1).trim() : label;
    }

    private static void resetHud(DrStandaloneConfig config) {
        config.dpsHudX = 8;
        config.dpsHudY = 90;
        config.dpsHudScale = 1.0;
        config.gemHudX = 8;
        config.gemHudY = 8;
        config.gemHudScale = 1.0;
        config.save();
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() {
        DrStandaloneMod.config().save();
        this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Open this screen with the configurable Controls keybind."), this.width / 2, this.height - 16, 0xAAAAAA);
    }

    private interface BoolGetter { boolean get(); }
    private interface BoolSetter { void set(boolean value); }
    private interface DoubleSetter { void set(double value); }
    private interface StringSetter { void set(String value); }
    private interface TextGetter { String get(); }
}
