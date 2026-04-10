package net.matte.drstandalone.mixin;

import net.matte.drstandalone.autoaugment.AutoAugmentFeature;
import net.matte.drstandalone.autoorbing.AutoOrbingFeature;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> extends Screen {
    @Unique private static final int EDGE_MARGIN = 4;
    @Unique private static final int OUTER_GAP = 8;
    @Unique private static final int PANEL_INNER_PAD = 10;
    @Unique private static final int PANEL_HEADER_H = 18;
    @Unique private static final int CTRL_PANEL_W = 218;
    @Unique private static final int CTRL_PANEL_H = 332;
    @Unique private static final int ROLL_PANEL_W = 190;
    @Unique private static final int ROLL_PANEL_H = 204;
    @Unique private static final int CTRL_CONTENT_W = CTRL_PANEL_W - (PANEL_INNER_PAD * 2);
    @Unique private static final int ROLL_CONTENT_W = ROLL_PANEL_W - (PANEL_INNER_PAD * 2);
    @Unique private static final int BTN_H = 20;
    @Unique private static final int CHIP_H = 16;
    @Unique private static final int TAB_GAP = 6;
    @Unique private static final int RULE_BTN_W = 92;
    @Unique private static final int RULE_BTN_GAP = 6;
    @Unique private static final int BOTTOM_GAP = 4;
    @Unique private static final int RULE_SCROLL_STEP = 18;

    @Unique private static final int PANEL_BG = 0xF0121317;
    @Unique private static final int PANEL_BORDER = 0xFFF0D08A;
    @Unique private static final int PANEL_BORDER_SHADOW = 0xFF3B2F20;
    @Unique private static final int PANEL_INNER = 0xFF1A1E23;
    @Unique private static final int HEADER_BG = 0xFF232830;
    @Unique private static final int HEADER_TEXT = 0xFFFFE9B8;
    @Unique private static final int SUBTEXT = 0xFFD7C78F;
    @Unique private static final int ACCENT_LINE = 0xFFF0D08A;
    @Unique private static final int DISABLED_TEXT = 0xFF95A0AB;
    @Unique private static final int FOOTER_BG = 0xD6161A20;
    @Unique private static final int WIDGET_BORDER = 0xFFF0D08A;
    @Unique private static final int WIDGET_BORDER_DARK = 0xFF392D1F;
    @Unique private static final int WIDGET_HIGHLIGHT = 0x33FFF2C0;
    @Unique private static final int HOVER_GLOW = 0x5AF7D98C;
    @Unique private static final int HOVER_GLOW_BRIGHT = 0xA8FFF0B8;

    @Unique private static final int TAB_MAIN = 0;
    @Unique private static final int TAB_RULES = 1;
    @Unique private static final int TAB_PAUSE = 2;
    @Unique private static final int TAB_ORB_MAIN = 0;
    @Unique private static final int TAB_ORB_RULES = 1;

    @Unique private static int autoAugmentLogOffsetX;
    @Unique private static int autoAugmentLogOffsetY;
    @Unique private boolean autoAugmentDraggingLogPanel;
    @Unique private int autoAugmentDragGrabX;
    @Unique private int autoAugmentDragGrabY;
    @Unique private int autoAugmentTab = TAB_MAIN;

    @Unique private ButtonWidget tabMainButton;
    @Unique private ButtonWidget tabRulesButton;
    @Unique private ButtonWidget tabPauseButton;
    @Unique private ButtonWidget autoAugmentButton;
    @Unique private ButtonWidget autoAugmentAugmentOnceButton;
    @Unique private ButtonWidget autoAugmentRunModeButton;
    @Unique private ButtonWidget autoAugmentStopModeButton;
    @Unique private ButtonWidget autoAugmentStatusButton;
    @Unique private ButtonWidget autoAugmentStopNowButton;
    @Unique private ButtonWidget autoAugmentConfigRescanButton;
    @Unique private ButtonWidget autoAugmentPausePlaceholderA;
    @Unique private ButtonWidget autoAugmentPausePlaceholderB;
    @Unique private SliderWidget autoAugmentSpeedSlider;
    @Unique private SliderWidget autoAugmentAttemptsSlider;
    @Unique private final ButtonWidget[] autoAugmentRuleButtons = new ButtonWidget[8];
    @Unique private int autoOrbingTab = TAB_ORB_MAIN;
    @Unique private ButtonWidget autoOrbingMainTabButton;
    @Unique private ButtonWidget autoOrbingRulesTabButton;
    @Unique private ButtonWidget autoOrbingEnabledButton;
    @Unique private ButtonWidget autoOrbingGuideButton;
    @Unique private ButtonWidget autoOrbingScanButton;
    @Unique private ButtonWidget autoOrbingStartButton;
    @Unique private ButtonWidget autoOrbingAutoButton;
    @Unique private ButtonWidget autoOrbingStopModeButton;
    @Unique private ButtonWidget autoOrbingMinStatsButton;
    @Unique private ButtonWidget autoOrbingMinStatsRuleButton;
    @Unique private ButtonWidget autoOrbingStatusButton;
    @Unique private ButtonWidget autoOrbingStopNowButton;
    @Unique private final ButtonWidget[] autoOrbingRuleButtons = new ButtonWidget[16];
    @Unique private final SliderWidget[] autoOrbingRuleSliders = new SliderWidget[16];
    @Unique private int autoOrbingRulesScrollOffset;
    @Unique private int autoOrbingRulesMaxScroll;
    @Unique private boolean autoOrbingDraggingScrollbar;
    @Unique private int autoOrbingScrollbarGrabOffset;

    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;
    @Shadow protected int backgroundHeight;
    @Shadow public abstract T getScreenHandler();

    protected HandledScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void minmaxrealms$autoaugmentInit(CallbackInfo info) {
        if (!AutoAugmentFeature.shouldAddScreenButton(title.getString())) return;

        int tabWidth = (CTRL_CONTENT_W - (TAB_GAP * 2)) / 3;
        tabMainButton = addDrawableChild(ButtonWidget.builder(Text.literal("Main"), button -> autoAugmentTab = TAB_MAIN).dimensions(0, 0, tabWidth, BTN_H).build());
        tabRulesButton = addDrawableChild(ButtonWidget.builder(Text.literal("Rules"), button -> autoAugmentTab = TAB_RULES).dimensions(0, 0, tabWidth, BTN_H).build());
        tabPauseButton = addDrawableChild(ButtonWidget.builder(Text.literal("Pause"), button -> autoAugmentTab = TAB_PAUSE).dimensions(0, 0, tabWidth, BTN_H).build());

        autoAugmentButton = addDrawableChild(
            ButtonWidget.builder(Text.literal(AutoAugmentFeature.getScreenButtonText()), button -> AutoAugmentFeature.toggleFromScreenButton())
                .dimensions(0, 0, 92, BTN_H)
                .build()
        );

        autoAugmentAugmentOnceButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Augment x1"), button -> AutoAugmentFeature.augmentOnceFromScreen())
                .dimensions(0, 0, 92, BTN_H)
                .build()
        );

        autoAugmentStatusButton = addDrawableChild(
            ButtonWidget.builder(Text.literal(AutoAugmentFeature.getScreenStatusText()), button -> {})
                .dimensions(0, 0, CTRL_CONTENT_W, CHIP_H)
                .build()
        );
        autoAugmentStatusButton.active = false;

        autoAugmentRunModeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal(AutoAugmentFeature.getRunModeButtonText()), button -> AutoAugmentFeature.cycleRunModeFromScreen())
                .dimensions(0, 0, CTRL_CONTENT_W, BTN_H)
                .build()
        );

        autoAugmentStopModeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal(AutoAugmentFeature.getStopModeButtonText()), button -> AutoAugmentFeature.cycleStopModeFromScreen())
                .dimensions(0, 0, CTRL_CONTENT_W, BTN_H)
                .build()
        );

        autoAugmentAttemptsSlider = addDrawableChild(new SliderWidget(0, 0, CTRL_CONTENT_W, BTN_H, Text.literal("Attempts"), AutoAugmentFeature.getUiAttemptsSliderValue()) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("Rounds: " + AutoAugmentFeature.getUiAttemptsCount()));
            }

            @Override
            protected void applyValue() {
                AutoAugmentFeature.setUiAttemptsSliderValue(value);
                updateMessage();
            }
        });
        autoAugmentAttemptsSlider.setMessage(Text.literal("Rounds: " + AutoAugmentFeature.getUiAttemptsCount()));

        autoAugmentConfigRescanButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Scan"), button -> AutoAugmentFeature.rescanConfigFromScreen())
                .dimensions(0, 0, CTRL_CONTENT_W, BTN_H)
                .build()
        );

        autoAugmentPausePlaceholderA = addDrawableChild(
            ButtonWidget.builder(Text.literal("Pause preset (soon)"), button -> {})
                .dimensions(0, 0, CTRL_CONTENT_W, BTN_H)
                .build()
        );
        autoAugmentPausePlaceholderA.active = false;

        autoAugmentPausePlaceholderB = addDrawableChild(
            ButtonWidget.builder(Text.literal("Pause exceptions (soon)"), button -> {})
                .dimensions(0, 0, CTRL_CONTENT_W, BTN_H)
                .build()
        );
        autoAugmentPausePlaceholderB.active = false;

        for (int i = 0; i < autoAugmentRuleButtons.length; i++) {
            final int index = i;
            autoAugmentRuleButtons[i] = addDrawableChild(
                ButtonWidget.builder(Text.literal(AutoAugmentFeature.getConfigRuleButtonText(index)), button -> AutoAugmentFeature.cycleConfigRuleFromScreen(index))
                    .dimensions(0, 0, RULE_BTN_W, BTN_H)
                    .build()
            );
        }

        autoAugmentStopNowButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Stop now"), button -> AutoAugmentFeature.stopNowFromScreen())
                .dimensions(0, 0, ROLL_CONTENT_W, BTN_H)
                .build()
        );

        autoAugmentSpeedSlider = addDrawableChild(new SliderWidget(0, 0, ROLL_CONTENT_W, BTN_H, Text.literal("Speed"), AutoAugmentFeature.getUiSpeedSliderValue()) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("Speed: " + AutoAugmentFeature.getUiSpeedPercent() + "%"));
            }

            @Override
            protected void applyValue() {
                AutoAugmentFeature.setUiSpeedSliderValue(value);
                updateMessage();
            }
        });
        autoAugmentSpeedSlider.setMessage(Text.literal("Speed: " + AutoAugmentFeature.getUiSpeedPercent() + "%"));

        minmaxrealms$layoutAutoAugmentWidgets();
    }

    @Inject(method = "handledScreenTick", at = @At("TAIL"))
    private void minmaxrealms$autoaugmentTick(CallbackInfo info) {
        if (!AutoAugmentFeature.shouldAddScreenButton(title.getString())) return;

        if (autoAugmentButton != null) autoAugmentButton.setMessage(Text.literal(AutoAugmentFeature.getScreenButtonText()));
        if (autoAugmentStatusButton != null) autoAugmentStatusButton.setMessage(Text.literal(AutoAugmentFeature.getScreenStatusText()));
        if (autoAugmentStopNowButton != null) autoAugmentStopNowButton.active = AutoAugmentFeature.isActiveFromScreen();
        if (autoAugmentSpeedSlider != null) autoAugmentSpeedSlider.setMessage(Text.literal("Speed: " + AutoAugmentFeature.getUiSpeedPercent() + "%"));
        if (autoAugmentAttemptsSlider != null) autoAugmentAttemptsSlider.setMessage(Text.literal("Rounds: " + AutoAugmentFeature.getUiAttemptsCount()));
        if (autoAugmentAttemptsSlider != null) autoAugmentAttemptsSlider.active = AutoAugmentFeature.shouldShowAttemptsButton();
        if (autoAugmentRunModeButton != null) autoAugmentRunModeButton.setMessage(Text.literal(AutoAugmentFeature.getRunModeButtonText()));
        if (autoAugmentStopModeButton != null) autoAugmentStopModeButton.setMessage(Text.literal(AutoAugmentFeature.getStopModeButtonText()));

        int visibleRules = AutoAugmentFeature.getConfigRuleButtonCount();
        for (int i = 0; i < autoAugmentRuleButtons.length; i++) {
            if (autoAugmentRuleButtons[i] == null) continue;
            autoAugmentRuleButtons[i].setMessage(Text.literal(AutoAugmentFeature.getConfigRuleButtonText(i)));
            autoAugmentRuleButtons[i].visible = autoAugmentTab == TAB_RULES && i < visibleRules;
        }

        minmaxrealms$layoutAutoAugmentWidgets();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void minmaxrealms$autoorbingInit(CallbackInfo info) {
        if (!AutoOrbingFeature.shouldRenderOverlay((Screen) (Object) this, title.getString())) return;

        int tabWidth = (CTRL_CONTENT_W - TAB_GAP) / 2;
        autoOrbingMainTabButton = addDrawableChild(ButtonWidget.builder(Text.literal("Main"), button -> autoOrbingTab = TAB_ORB_MAIN).dimensions(0, 0, tabWidth, BTN_H).build());
        autoOrbingRulesTabButton = addDrawableChild(ButtonWidget.builder(Text.literal("Rules"), button -> autoOrbingTab = TAB_ORB_RULES).dimensions(0, 0, tabWidth, BTN_H).build());

        autoOrbingGuideButton = addDrawableChild(ButtonWidget.builder(Text.literal(AutoOrbingFeature.getGuideButtonText()), button -> AutoOrbingFeature.togglePlacementGuideFromScreen()).dimensions(0, 0, 92, BTN_H).build());
        autoOrbingScanButton = addDrawableChild(ButtonWidget.builder(Text.literal("Scan"), button -> AutoOrbingFeature.scanFromScreen()).dimensions(0, 0, CTRL_CONTENT_W, BTN_H).build());
        autoOrbingStartButton = addDrawableChild(ButtonWidget.builder(Text.literal(AutoOrbingFeature.getStartButtonText()), button -> AutoOrbingFeature.startOnceFromScreen()).dimensions(0, 0, 92, BTN_H).build());
        autoOrbingAutoButton = addDrawableChild(ButtonWidget.builder(Text.literal(AutoOrbingFeature.getAutoButtonText()), button -> AutoOrbingFeature.startStopFromScreen()).dimensions(0, 0, 92, BTN_H).build());
        autoOrbingStopModeButton = addDrawableChild(ButtonWidget.builder(Text.literal(AutoOrbingFeature.getStopModeButtonText()), button -> AutoOrbingFeature.cycleStopModeFromScreen()).dimensions(0, 0, CTRL_CONTENT_W, BTN_H).build());
        autoOrbingMinStatsButton = addDrawableChild(ButtonWidget.builder(Text.literal(AutoOrbingFeature.getMinStatsButtonText()), button -> AutoOrbingFeature.cycleMinStatsFromScreen()).dimensions(0, 0, CTRL_CONTENT_W, BTN_H).build());
        autoOrbingMinStatsRuleButton = addDrawableChild(ButtonWidget.builder(Text.literal(AutoOrbingFeature.getMinStatsRuleButtonText()), button -> AutoOrbingFeature.toggleMinStatsRuleFromScreen()).dimensions(0, 0, CTRL_CONTENT_W, BTN_H).build());
        autoOrbingStatusButton = addDrawableChild(ButtonWidget.builder(Text.literal(AutoOrbingFeature.getStatusText()), button -> {}).dimensions(0, 0, CTRL_CONTENT_W, CHIP_H).build());
        autoOrbingStatusButton.active = false;
        autoOrbingStopNowButton = addDrawableChild(ButtonWidget.builder(Text.literal("Stop now"), button -> AutoOrbingFeature.stopNowFromScreen()).dimensions(0, 0, ROLL_CONTENT_W, BTN_H).build());

        for (int i = 0; i < autoOrbingRuleButtons.length; i++) {
            final int index = i;
            autoOrbingRuleButtons[i] = addDrawableChild(
                ButtonWidget.builder(Text.literal(AutoOrbingFeature.getConfigRuleButtonText(index)), button -> AutoOrbingFeature.cycleConfigRuleFromScreen(index))
                    .dimensions(0, 0, RULE_BTN_W, BTN_H)
                    .build()
            );
            autoOrbingRuleSliders[i] = addDrawableChild(new SliderWidget(0, 0, RULE_BTN_W, BTN_H, Text.literal("Roll %"), AutoOrbingFeature.getRuleSliderValue(index)) {
                @Override
                protected void updateMessage() {
                    setMessage(Text.literal(AutoOrbingFeature.getRuleSliderText(index)));
                }

                @Override
                protected void applyValue() {
                    AutoOrbingFeature.setRuleSliderValue(index, value);
                }
            });
            autoOrbingRuleSliders[i].setMessage(Text.literal(AutoOrbingFeature.getRuleSliderText(index)));
        }

        minmaxrealms$layoutAutoOrbingWidgets();
    }

    @Inject(method = "handledScreenTick", at = @At("TAIL"))
    private void minmaxrealms$autoorbingTick(CallbackInfo info) {
        if (!AutoOrbingFeature.shouldRenderOverlay((Screen) (Object) this, title.getString())) return;

        if (autoOrbingGuideButton != null) autoOrbingGuideButton.setMessage(Text.literal(AutoOrbingFeature.getGuideButtonText()));
        if (autoOrbingStartButton != null) autoOrbingStartButton.setMessage(Text.literal(AutoOrbingFeature.getStartButtonText()));
        if (autoOrbingAutoButton != null) autoOrbingAutoButton.setMessage(Text.literal(AutoOrbingFeature.getAutoButtonText()));
        if (autoOrbingStopModeButton != null) autoOrbingStopModeButton.setMessage(Text.literal(AutoOrbingFeature.getStopModeButtonText()));
        if (autoOrbingMinStatsButton != null) autoOrbingMinStatsButton.setMessage(Text.literal(AutoOrbingFeature.getMinStatsButtonText()));
        if (autoOrbingMinStatsRuleButton != null) autoOrbingMinStatsRuleButton.setMessage(Text.literal(AutoOrbingFeature.getMinStatsRuleButtonText()));
        if (autoOrbingStatusButton != null) autoOrbingStatusButton.setMessage(Text.literal(AutoOrbingFeature.getStatusText()));
        if (autoOrbingStopNowButton != null) autoOrbingStopNowButton.active = AutoOrbingFeature.isRunning();

        int visibleRules = AutoOrbingFeature.getConfigRuleButtonCount();
        for (int i = 0; i < autoOrbingRuleButtons.length; i++) {
            if (autoOrbingRuleButtons[i] == null) continue;
            autoOrbingRuleButtons[i].setMessage(Text.literal(AutoOrbingFeature.getConfigRuleButtonText(i)));
            autoOrbingRuleButtons[i].visible = autoOrbingTab == TAB_ORB_RULES && i < visibleRules;
            if (autoOrbingRuleSliders[i] != null) {
                autoOrbingRuleSliders[i].setMessage(Text.literal(AutoOrbingFeature.getRuleSliderText(i)));
                autoOrbingRuleSliders[i].visible = autoOrbingTab == TAB_ORB_RULES && i < visibleRules && AutoOrbingFeature.isRuleSliderVisible(i);
            }
        }

        minmaxrealms$layoutAutoOrbingWidgets();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void minmaxrealms$autoaugmentRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!AutoAugmentFeature.shouldAddScreenButton(title.getString())) return;

        minmaxrealms$layoutRollPanel();
        int controlX = minmaxrealms$getControlPanelX();
        int controlY = minmaxrealms$getControlPanelY();
        minmaxrealms$drawPanelChrome(context, controlX, controlY, CTRL_PANEL_W, CTRL_PANEL_H, "Auto Augment");
        context.drawTextWithShadow(textRenderer, Text.literal("DungeonRealms Fusion"), controlX + PANEL_INNER_PAD, controlY + PANEL_HEADER_H + 3, SUBTEXT);
        context.fill(controlX + PANEL_INNER_PAD, controlY + PANEL_HEADER_H + 14, controlX + CTRL_PANEL_W - PANEL_INNER_PAD, controlY + PANEL_HEADER_H + 15, ACCENT_LINE);

        int rollX = getAugmentLogPanelX();
        int rollY = getAugmentLogPanelY();
        minmaxrealms$drawPanelChrome(context, rollX, rollY, ROLL_PANEL_W, ROLL_PANEL_H, "Current Roll");
        context.drawTextWithShadow(textRenderer, Text.literal("Live snapshot only"), rollX + PANEL_INNER_PAD, rollY + PANEL_HEADER_H + 3, DISABLED_TEXT);
        context.fill(rollX + PANEL_INNER_PAD, rollY + PANEL_HEADER_H + 14, rollX + ROLL_PANEL_W - PANEL_INNER_PAD, rollY + PANEL_HEADER_H + 15, ACCENT_LINE);

        var lines = AutoAugmentFeature.getRecentLogLines();
        int yCursor = rollY + PANEL_HEADER_H + 20;
        int reservedBottom = (BTN_H * 2) + BOTTOM_GAP + 8;
        int logBottom = rollY + ROLL_PANEL_H - PANEL_INNER_PAD - reservedBottom;
        int footerTop = logBottom + 2;
        context.fill(rollX + 2, footerTop, rollX + ROLL_PANEL_W - 2, rollY + ROLL_PANEL_H - 2, FOOTER_BG);
        context.fill(rollX + 2, footerTop, rollX + ROLL_PANEL_W - 2, footerTop + 1, ACCENT_LINE);
        int maxLines = Math.min(8, Math.max(0, (logBottom - yCursor) / 11));
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            String line = lines.get(i);
            if (line.length() > 33) line = line.substring(0, 30) + "...";
            context.drawTextWithShadow(textRenderer, Text.literal(line), rollX + PANEL_INNER_PAD, yCursor, AutoAugmentFeature.getRecentLogColor(i));
            yCursor += 11;
        }

        minmaxrealms$drawAutoAugmentWidgetContours(context, mouseX, mouseY);

        if (autoAugmentTab == TAB_MAIN) {
            int infoX = minmaxrealms$getContentX();
            int infoY = minmaxrealms$getMainInfoY(controlY);
            context.drawTextWithShadow(textRenderer, Text.literal(AutoAugmentFeature.getConfigItemButtonText()), infoX, infoY + 3, DISABLED_TEXT);
        }

        if (autoAugmentTab == TAB_PAUSE) {
            int x0 = minmaxrealms$getContentX();
            int y0 = minmaxrealms$getPauseHeaderY();
            context.drawTextWithShadow(textRenderer, Text.literal("Pause settings"), x0, y0, HEADER_TEXT);
            context.drawTextWithShadow(textRenderer, Text.literal("Placeholder for advanced pause logic."), x0, y0 + 12, DISABLED_TEXT);
        } else if (autoAugmentTab == TAB_RULES) {
            int x0 = minmaxrealms$getContentX();
            int y0 = minmaxrealms$getRulesHeaderY();
            context.drawTextWithShadow(textRenderer, Text.literal("Rules by stat"), x0, y0, HEADER_TEXT);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void minmaxrealms$autoorbingRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!AutoOrbingFeature.shouldRenderOverlay((Screen) (Object) this, title.getString())) return;

        minmaxrealms$drawAutoOrbingGuides(context);

        int controlX = minmaxrealms$getControlPanelX();
        int controlY = minmaxrealms$getControlPanelY();
        minmaxrealms$drawPanelChrome(context, controlX, controlY, CTRL_PANEL_W, CTRL_PANEL_H, "Auto Orbing");
        context.drawTextWithShadow(textRenderer, Text.literal("Inventory orb lanes"), controlX + PANEL_INNER_PAD, controlY + PANEL_HEADER_H + 3, SUBTEXT);
        context.fill(controlX + PANEL_INNER_PAD, controlY + PANEL_HEADER_H + 14, controlX + CTRL_PANEL_W - PANEL_INNER_PAD, controlY + PANEL_HEADER_H + 15, ACCENT_LINE);

        int rollX = getAugmentLogPanelX();
        int rollY = getAugmentLogPanelY();
        minmaxrealms$drawPanelChrome(context, rollX, rollY, ROLL_PANEL_W, ROLL_PANEL_H, "Orb Log");
        context.drawTextWithShadow(textRenderer, Text.literal("Yellow = target item"), rollX + PANEL_INNER_PAD, rollY + PANEL_HEADER_H + 3, DISABLED_TEXT);
        context.fill(rollX + PANEL_INNER_PAD, rollY + PANEL_HEADER_H + 14, rollX + ROLL_PANEL_W - PANEL_INNER_PAD, rollY + PANEL_HEADER_H + 15, ACCENT_LINE);

        var lines = AutoOrbingFeature.getRecentLogLines();
        int yCursor = rollY + PANEL_HEADER_H + 20;
        int reservedBottom = BTN_H + 8;
        int logBottom = rollY + ROLL_PANEL_H - PANEL_INNER_PAD - reservedBottom;
        for (int i = 0; i < Math.min(8, lines.size()); i++) {
            if (yCursor > logBottom) break;
            String line = lines.get(i);
            if (line.length() > 33) line = line.substring(0, 30) + "...";
            context.drawTextWithShadow(textRenderer, Text.literal(line), rollX + PANEL_INNER_PAD, yCursor, AutoOrbingFeature.getRecentLogColor(i));
            yCursor += 11;
        }

        minmaxrealms$drawAutoOrbingWidgetContours(context, mouseX, mouseY);

        if (autoOrbingTab == TAB_ORB_MAIN) {
            context.drawTextWithShadow(textRenderer, Text.literal(AutoOrbingFeature.getConfigItemText()), minmaxrealms$getContentX(), minmaxrealms$getMainInfoY(controlY) + 3, DISABLED_TEXT);
        } else {
            context.drawTextWithShadow(textRenderer, Text.literal(AutoOrbingFeature.getRuleFamilyLabel()), minmaxrealms$getContentX(), minmaxrealms$getRulesHeaderY(), HEADER_TEXT);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void minmaxrealms$autoaugmentMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
            && AutoOrbingFeature.shouldRenderOverlay((Screen) (Object) this, title.getString())
            && autoOrbingTab == TAB_ORB_RULES
            && autoOrbingRulesMaxScroll > 0
            && minmaxrealms$isOverAutoOrbingScrollThumb(mouseX, mouseY)) {
            autoOrbingDraggingScrollbar = true;
            autoOrbingScrollbarGrabOffset = (int) mouseY - minmaxrealms$getAutoOrbingScrollThumbY();
            cir.setReturnValue(true);
            return;
        }

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (!AutoAugmentFeature.shouldAddScreenButton(title.getString())) return;

        int panelX = getAugmentLogPanelX();
        int panelY = getAugmentLogPanelY();
        boolean inHeader = mouseX >= panelX && mouseX <= panelX + ROLL_PANEL_W && mouseY >= panelY && mouseY <= panelY + PANEL_HEADER_H;
        if (!inHeader) return;

        autoAugmentDraggingLogPanel = true;
        autoAugmentDragGrabX = (int) mouseX - panelX;
        autoAugmentDragGrabY = (int) mouseY - panelY;
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void minmaxrealms$autoaugmentMouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) autoOrbingDraggingScrollbar = false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) autoAugmentDraggingLogPanel = false;
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"))
    private void minmaxrealms$autoaugmentMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && autoOrbingDraggingScrollbar) {
            minmaxrealms$setAutoOrbingScrollFromMouse(mouseY - autoOrbingScrollbarGrabOffset);
            minmaxrealms$layoutAutoOrbingWidgets();
            cir.setReturnValue(true);
            return;
        }

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !autoAugmentDraggingLogPanel) return;
        if (!AutoAugmentFeature.shouldAddScreenButton(title.getString())) return;

        int baseX = minmaxrealms$getRollPanelAnchorX();
        int baseY = minmaxrealms$getRollPanelAnchorY();
        autoAugmentLogOffsetX = (int) mouseX - autoAugmentDragGrabX - baseX;
        autoAugmentLogOffsetY = (int) mouseY - autoAugmentDragGrabY - baseY;
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void minmaxrealms$autoorbingMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
        if (!AutoOrbingFeature.shouldRenderOverlay((Screen) (Object) this, title.getString())) return;
        if (autoOrbingTab != TAB_ORB_RULES) return;
        if (autoOrbingRulesMaxScroll <= 0) return;
        if (!minmaxrealms$isInAutoOrbingRulesViewport(mouseX, mouseY)) return;

        int delta = (int) Math.round(-verticalAmount * RULE_SCROLL_STEP);
        if (delta == 0) delta = verticalAmount > 0 ? -RULE_SCROLL_STEP : RULE_SCROLL_STEP;
        autoOrbingRulesScrollOffset = minmaxrealms$clamp(autoOrbingRulesScrollOffset + delta, 0, autoOrbingRulesMaxScroll);
        minmaxrealms$layoutAutoOrbingWidgets();
        cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void minmaxrealms$autoaugmentKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (keyCode != GLFW.GLFW_KEY_BACKSPACE) return;

        if (AutoAugmentFeature.shouldAddScreenButton(title.getString()) && AutoAugmentFeature.isActiveFromScreen()) {
            AutoAugmentFeature.stopNowSilentlyFromScreen();
            cir.setReturnValue(true);
            return;
        }

        if (AutoOrbingFeature.shouldRenderOverlay((Screen) (Object) this, title.getString()) && AutoOrbingFeature.isRunning()) {
            AutoOrbingFeature.stopNowFromScreen();
            cir.setReturnValue(true);
        }
    }

    @Unique
    private void minmaxrealms$layoutAutoAugmentWidgets() {
        minmaxrealms$layoutTabBar();
        minmaxrealms$hideTabWidgets();
        minmaxrealms$layoutMainTab();
        minmaxrealms$layoutRulesTab();
        minmaxrealms$layoutPauseTab();
        minmaxrealms$layoutRollPanel();
    }

    @Unique
    private void minmaxrealms$layoutAutoOrbingWidgets() {
        minmaxrealms$layoutAutoOrbingTabBar();
        minmaxrealms$hideAutoOrbingWidgets();
        autoOrbingRulesScrollOffset = minmaxrealms$clamp(autoOrbingRulesScrollOffset, 0, autoOrbingRulesMaxScroll);
        minmaxrealms$layoutAutoOrbingMainTab();
        minmaxrealms$layoutAutoOrbingRulesTab();
        minmaxrealms$layoutAutoOrbingLogPanel();
    }

    @Unique
    private void minmaxrealms$hideAutoOrbingWidgets() {
        if (autoOrbingMainTabButton != null) autoOrbingMainTabButton.visible = true;
        if (autoOrbingRulesTabButton != null) autoOrbingRulesTabButton.visible = true;
        if (autoOrbingGuideButton != null) autoOrbingGuideButton.visible = false;
        if (autoOrbingScanButton != null) autoOrbingScanButton.visible = false;
        if (autoOrbingStartButton != null) autoOrbingStartButton.visible = false;
        if (autoOrbingAutoButton != null) autoOrbingAutoButton.visible = false;
        if (autoOrbingStopModeButton != null) autoOrbingStopModeButton.visible = false;
        if (autoOrbingMinStatsButton != null) autoOrbingMinStatsButton.visible = false;
        if (autoOrbingMinStatsRuleButton != null) autoOrbingMinStatsRuleButton.visible = false;
        if (autoOrbingStatusButton != null) autoOrbingStatusButton.visible = false;
        if (autoOrbingStopNowButton != null) autoOrbingStopNowButton.visible = true;
        for (ButtonWidget ruleButton : autoOrbingRuleButtons) {
            if (ruleButton != null) ruleButton.visible = false;
        }
        for (SliderWidget ruleSlider : autoOrbingRuleSliders) {
            if (ruleSlider != null) ruleSlider.visible = false;
        }
    }

    @Unique
    private void minmaxrealms$layoutAutoOrbingTabBar() {
        int tabWidth = (CTRL_CONTENT_W - TAB_GAP) / 2;
        int tabX = minmaxrealms$getContentX();
        int tabY = minmaxrealms$getTabBarY();

        if (autoOrbingMainTabButton != null) {
            autoOrbingMainTabButton.setPosition(tabX, tabY);
            autoOrbingMainTabButton.setDimensions(tabWidth, BTN_H);
            autoOrbingMainTabButton.setMessage(Text.literal(autoOrbingTab == TAB_ORB_MAIN ? "> Main" : "Main"));
        }
        if (autoOrbingRulesTabButton != null) {
            autoOrbingRulesTabButton.setPosition(tabX + tabWidth + TAB_GAP, tabY);
            autoOrbingRulesTabButton.setDimensions(tabWidth, BTN_H);
            autoOrbingRulesTabButton.setMessage(Text.literal(autoOrbingTab == TAB_ORB_RULES ? "> Rules" : "Rules"));
        }
    }

    @Unique
    private void minmaxrealms$layoutAutoOrbingMainTab() {
        if (autoOrbingTab != TAB_ORB_MAIN) return;
        int controlY = minmaxrealms$getControlPanelY();
        int innerX = minmaxrealms$getContentX();
        int rowY = minmaxrealms$getMainContentTopY();

        if (autoOrbingGuideButton != null) {
            autoOrbingGuideButton.visible = true;
            autoOrbingGuideButton.setPosition(innerX, rowY);
        }
        if (autoOrbingStartButton != null) {
            autoOrbingStartButton.visible = true;
            autoOrbingStartButton.setPosition(innerX + 98, rowY);
        }
        rowY += BTN_H + 4;

        if (autoOrbingScanButton != null) {
            autoOrbingScanButton.visible = true;
            autoOrbingScanButton.setPosition(innerX, rowY);
        }
        rowY += BTN_H + 4;

        if (autoOrbingAutoButton != null) {
            autoOrbingAutoButton.visible = true;
            autoOrbingAutoButton.setPosition(innerX, rowY);
            autoOrbingAutoButton.setDimensions(CTRL_CONTENT_W, BTN_H);
        }

        if (autoOrbingStatusButton != null) {
            autoOrbingStatusButton.visible = true;
            autoOrbingStatusButton.setPosition(innerX, minmaxrealms$getMainStatusY(controlY));
        }
    }

    @Unique
    private void minmaxrealms$layoutAutoOrbingRulesTab() {
        autoOrbingRulesMaxScroll = 0;
        if (autoOrbingTab != TAB_ORB_RULES) return;
        int innerX = minmaxrealms$getContentX();
        int rowY = minmaxrealms$getRulesGridTopY();
        int viewportTop = rowY;
        int viewportBottom = minmaxrealms$getControlPanelY() + CTRL_PANEL_H - PANEL_INNER_PAD - 6;
        int visibleRules = AutoOrbingFeature.getConfigRuleButtonCount();
        int[] contentColumnY = {rowY, rowY};

        for (int i = 0; i < autoOrbingRuleButtons.length; i++) {
            if (autoOrbingRuleButtons[i] == null) continue;
            int col = i % 2;
            int xPos = innerX + col * (RULE_BTN_W + RULE_BTN_GAP);
            autoOrbingRuleButtons[i].visible = false;
            if (i < visibleRules) {
                int buttonY = contentColumnY[col] - autoOrbingRulesScrollOffset;
                autoOrbingRuleButtons[i].setPosition(xPos, buttonY);
                autoOrbingRuleButtons[i].visible = minmaxrealms$isWithinViewport(buttonY, BTN_H, viewportTop, viewportBottom);
                contentColumnY[col] += BTN_H + 2;
                if (autoOrbingRuleSliders[i] != null && AutoOrbingFeature.isRuleSliderVisible(i)) {
                    int sliderY = contentColumnY[col] - autoOrbingRulesScrollOffset;
                    autoOrbingRuleSliders[i].setPosition(xPos, sliderY);
                    autoOrbingRuleSliders[i].visible = minmaxrealms$isWithinViewport(sliderY, BTN_H, viewportTop, viewportBottom);
                    contentColumnY[col] += BTN_H + 4;
                } else if (autoOrbingRuleSliders[i] != null) {
                    autoOrbingRuleSliders[i].visible = false;
                }
            } else if (autoOrbingRuleSliders[i] != null) {
                autoOrbingRuleSliders[i].visible = false;
            }
        }

        int footerContentY = Math.max(contentColumnY[0], contentColumnY[1]) + 4;
        int footerY = footerContentY - autoOrbingRulesScrollOffset;
        int contentBottom = footerContentY + (BTN_H + 4) * 3 - 4;
        autoOrbingRulesMaxScroll = Math.max(0, contentBottom - viewportBottom);
        autoOrbingRulesScrollOffset = minmaxrealms$clamp(autoOrbingRulesScrollOffset, 0, autoOrbingRulesMaxScroll);
        footerY = footerContentY - autoOrbingRulesScrollOffset;

        if (autoOrbingMinStatsButton != null) {
            autoOrbingMinStatsButton.visible = minmaxrealms$isWithinViewport(footerY, BTN_H, viewportTop, viewportBottom);
            autoOrbingMinStatsButton.setPosition(innerX, footerY);
        }
        if (autoOrbingMinStatsRuleButton != null) {
            int minStatsRuleY = footerY + BTN_H + 4;
            autoOrbingMinStatsRuleButton.visible = minmaxrealms$isWithinViewport(minStatsRuleY, BTN_H, viewportTop, viewportBottom);
            autoOrbingMinStatsRuleButton.setPosition(innerX, minStatsRuleY);
        }
        if (autoOrbingStopModeButton != null) {
            int stopModeY = footerY + (BTN_H + 4) * 2;
            autoOrbingStopModeButton.visible = minmaxrealms$isWithinViewport(stopModeY, BTN_H, viewportTop, viewportBottom);
            autoOrbingStopModeButton.setPosition(innerX, stopModeY);
        }
    }

    @Unique
    private void minmaxrealms$layoutAutoOrbingLogPanel() {
        if (autoOrbingStopNowButton != null) {
            autoOrbingStopNowButton.visible = true;
            autoOrbingStopNowButton.setPosition(getAugmentLogPanelX() + PANEL_INNER_PAD, getAugmentLogPanelY() + ROLL_PANEL_H - PANEL_INNER_PAD - BTN_H);
        }
    }

    @Unique
    private void minmaxrealms$hideTabWidgets() {
        if (autoAugmentButton != null) autoAugmentButton.visible = false;
        if (autoAugmentAugmentOnceButton != null) autoAugmentAugmentOnceButton.visible = false;
        if (autoAugmentRunModeButton != null) autoAugmentRunModeButton.visible = false;
        if (autoAugmentStopModeButton != null) autoAugmentStopModeButton.visible = false;
        if (autoAugmentStatusButton != null) autoAugmentStatusButton.visible = false;
        if (autoAugmentConfigRescanButton != null) autoAugmentConfigRescanButton.visible = false;
        if (autoAugmentAttemptsSlider != null) autoAugmentAttemptsSlider.visible = false;
        if (autoAugmentPausePlaceholderA != null) autoAugmentPausePlaceholderA.visible = false;
        if (autoAugmentPausePlaceholderB != null) autoAugmentPausePlaceholderB.visible = false;
        for (ButtonWidget ruleButton : autoAugmentRuleButtons) {
            if (ruleButton != null) ruleButton.visible = false;
        }
    }

    @Unique
    private void minmaxrealms$layoutTabBar() {
        int controlX = minmaxrealms$getControlPanelX();
        int tabX = controlX + PANEL_INNER_PAD;
        int tabY = minmaxrealms$getTabBarY();
        int tabWidth = (CTRL_CONTENT_W - (TAB_GAP * 2)) / 3;

        if (tabMainButton != null) {
            tabMainButton.setPosition(tabX, tabY);
            tabMainButton.setDimensions(tabWidth, BTN_H);
            tabMainButton.setMessage(Text.literal(autoAugmentTab == TAB_MAIN ? "> Main" : "Main"));
        }

        if (tabRulesButton != null) {
            tabRulesButton.setPosition(tabX + tabWidth + TAB_GAP, tabY);
            tabRulesButton.setDimensions(tabWidth, BTN_H);
            tabRulesButton.setMessage(Text.literal(autoAugmentTab == TAB_RULES ? "> Rules" : "Rules"));
        }

        if (tabPauseButton != null) {
            tabPauseButton.setPosition(tabX + ((tabWidth + TAB_GAP) * 2), tabY);
            tabPauseButton.setDimensions(tabWidth, BTN_H);
            tabPauseButton.setMessage(Text.literal(autoAugmentTab == TAB_PAUSE ? "> Pause" : "Pause"));
        }
    }

    @Unique
    private void minmaxrealms$layoutMainTab() {
        if (autoAugmentTab != TAB_MAIN) return;
        int controlY = minmaxrealms$getControlPanelY();
        int innerX = minmaxrealms$getContentX();
        int rowY = minmaxrealms$getMainContentTopY();

        if (autoAugmentButton != null) {
            autoAugmentButton.visible = true;
            autoAugmentButton.setPosition(innerX, rowY);
        }
        if (autoAugmentAugmentOnceButton != null) {
            autoAugmentAugmentOnceButton.visible = true;
            autoAugmentAugmentOnceButton.setPosition(innerX + 98, rowY);
        }
        rowY += BTN_H + 4;

        if (autoAugmentConfigRescanButton != null) {
            autoAugmentConfigRescanButton.visible = true;
            autoAugmentConfigRescanButton.setPosition(innerX, rowY);
        }
        rowY += BTN_H + 4;

        if (autoAugmentRunModeButton != null) {
            autoAugmentRunModeButton.visible = true;
            autoAugmentRunModeButton.setPosition(innerX, rowY);
        }
        rowY += BTN_H + 4;

        if (autoAugmentAttemptsSlider != null) {
            autoAugmentAttemptsSlider.visible = true;
            autoAugmentAttemptsSlider.setPosition(innerX, rowY);
        }

        if (autoAugmentStatusButton != null) {
            autoAugmentStatusButton.visible = true;
            autoAugmentStatusButton.setPosition(innerX, minmaxrealms$getMainStatusY(controlY));
        }
    }

    @Unique
    private void minmaxrealms$layoutRulesTab() {
        if (autoAugmentTab != TAB_RULES) return;
        int innerX = minmaxrealms$getContentX();
        int rowY = minmaxrealms$getRulesGridTopY();
        int visibleRules = AutoAugmentFeature.getConfigRuleButtonCount();
        int renderedRows = Math.max(1, (visibleRules + 1) / 2);

        for (int i = 0; i < autoAugmentRuleButtons.length; i++) {
            if (autoAugmentRuleButtons[i] == null) continue;
            int col = i % 2;
            int row = i / 2;
            autoAugmentRuleButtons[i].visible = i < visibleRules;
            autoAugmentRuleButtons[i].setPosition(innerX + col * (RULE_BTN_W + RULE_BTN_GAP), rowY + row * (BTN_H + 2));
        }

        if (autoAugmentStopModeButton != null) {
            autoAugmentStopModeButton.visible = true;
            autoAugmentStopModeButton.setPosition(innerX, rowY + (renderedRows * (BTN_H + 2)) + 6);
        }
    }

    @Unique
    private void minmaxrealms$layoutPauseTab() {
        if (autoAugmentTab != TAB_PAUSE) return;
        int innerX = minmaxrealms$getContentX();
        int rowY = minmaxrealms$getPauseContentTopY();

        if (autoAugmentPausePlaceholderA != null) {
            autoAugmentPausePlaceholderA.visible = true;
            autoAugmentPausePlaceholderA.setPosition(innerX, rowY);
        }
        rowY += BTN_H + 4;

        if (autoAugmentPausePlaceholderB != null) {
            autoAugmentPausePlaceholderB.visible = true;
            autoAugmentPausePlaceholderB.setPosition(innerX, rowY);
        }
    }

    @Unique
    private void minmaxrealms$layoutRollPanel() {
        if (autoAugmentStopNowButton != null) {
            autoAugmentStopNowButton.visible = true;
            int rollX = getAugmentLogPanelX() + PANEL_INNER_PAD;
            int stopY = getAugmentLogPanelY() + ROLL_PANEL_H - PANEL_INNER_PAD - BTN_H - (BTN_H + BOTTOM_GAP);
            autoAugmentStopNowButton.setPosition(rollX, stopY);
        }

        if (autoAugmentSpeedSlider != null) {
            autoAugmentSpeedSlider.visible = true;
            int rollX = getAugmentLogPanelX() + PANEL_INNER_PAD;
            int speedY = getAugmentLogPanelY() + ROLL_PANEL_H - PANEL_INNER_PAD - BTN_H;
            autoAugmentSpeedSlider.setPosition(rollX, speedY);
        }
    }

    @Unique
    private int getAugmentLogPanelX() {
        return minmaxrealms$clamp(
            minmaxrealms$getRollPanelAnchorX() + autoAugmentLogOffsetX,
            EDGE_MARGIN,
            width - ROLL_PANEL_W - EDGE_MARGIN
        );
    }

    @Unique
    private int getAugmentLogPanelY() {
        return minmaxrealms$clamp(
            minmaxrealms$getRollPanelAnchorY() + autoAugmentLogOffsetY,
            EDGE_MARGIN,
            height - ROLL_PANEL_H - EDGE_MARGIN
        );
    }

    @Unique
    private int minmaxrealms$getSmithLeft() {
        return (width - backgroundWidth) / 2;
    }

    @Unique
    private int minmaxrealms$getSmithTop() {
        return (height - backgroundHeight) / 2;
    }

    @Unique
    private int minmaxrealms$getControlPanelX() {
        int smithLeft = minmaxrealms$getSmithLeft();
        int preferredLeft = smithLeft - CTRL_PANEL_W - OUTER_GAP;
        if (preferredLeft >= EDGE_MARGIN) return preferredLeft;

        int fallbackRight = smithLeft + backgroundWidth + OUTER_GAP;
        return minmaxrealms$clamp(fallbackRight, EDGE_MARGIN, width - CTRL_PANEL_W - EDGE_MARGIN);
    }

    @Unique
    private int minmaxrealms$getControlPanelY() {
        return minmaxrealms$clamp(minmaxrealms$getSmithTop(), EDGE_MARGIN, height - CTRL_PANEL_H - EDGE_MARGIN);
    }

    @Unique
    private int minmaxrealms$getRollPanelAnchorX() {
        return minmaxrealms$getSmithLeft() + backgroundWidth + OUTER_GAP;
    }

    @Unique
    private int minmaxrealms$getRollPanelAnchorY() {
        return minmaxrealms$getSmithTop() + 52;
    }

    @Unique
    private int minmaxrealms$getContentX() {
        return minmaxrealms$getControlPanelX() + PANEL_INNER_PAD;
    }

    @Unique
    private int minmaxrealms$getTabBarY() {
        return minmaxrealms$getControlPanelY() + PANEL_HEADER_H + 20;
    }

    @Unique
    private int minmaxrealms$getMainContentTopY() {
        return minmaxrealms$getTabBarY() + BTN_H + 8;
    }

    @Unique
    private int minmaxrealms$getRulesHeaderY() {
        return minmaxrealms$getTabBarY() + BTN_H + 16;
    }

    @Unique
    private int minmaxrealms$getRulesGridTopY() {
        return minmaxrealms$getRulesHeaderY() + 18;
    }

    @Unique
    private int minmaxrealms$getPauseHeaderY() {
        return minmaxrealms$getTabBarY() + BTN_H + 16;
    }

    @Unique
    private int minmaxrealms$getPauseContentTopY() {
        return minmaxrealms$getPauseHeaderY() + 26;
    }

    @Unique
    private void minmaxrealms$drawPanelChrome(DrawContext context, int x, int y, int w, int h, String titleText) {
        context.fill(x, y, x + w, y + h, PANEL_BG);
        context.fill(x + 2, y + 2, x + w - 2, y + h - 2, PANEL_INNER);
        context.fill(x + 1, y + 1, x + w - 1, y + PANEL_HEADER_H, HEADER_BG);
        context.drawBorder(x, y, w, h, PANEL_BORDER);
        context.drawBorder(x + 1, y + 1, w - 2, h - 2, PANEL_BORDER_SHADOW);
        context.fill(x + 2, y + 2, x + w - 2, y + 3, 0x33FFFFFF);
        context.fill(x + 1, y + PANEL_HEADER_H, x + w - 1, y + PANEL_HEADER_H + 1, ACCENT_LINE);
        context.drawTextWithShadow(textRenderer, Text.literal(titleText), x + PANEL_INNER_PAD, y + 5, HEADER_TEXT);
    }

    @Unique
    private void minmaxrealms$drawAutoAugmentWidgetContours(DrawContext context, int mouseX, int mouseY) {
        minmaxrealms$drawWidgetFrame(context, tabMainButton, autoAugmentTab == TAB_MAIN, mouseX, mouseY, true);
        minmaxrealms$drawWidgetFrame(context, tabRulesButton, autoAugmentTab == TAB_RULES, mouseX, mouseY, true);
        minmaxrealms$drawWidgetFrame(context, tabPauseButton, autoAugmentTab == TAB_PAUSE, mouseX, mouseY, true);

        if (autoAugmentTab == TAB_MAIN) {
            minmaxrealms$drawWidgetFrame(context, autoAugmentButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoAugmentAugmentOnceButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoAugmentConfigRescanButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoAugmentRunModeButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoAugmentAttemptsSlider, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoAugmentStatusButton, false, mouseX, mouseY, false);
        } else if (autoAugmentTab == TAB_RULES) {
            for (int i = 0; i < autoAugmentRuleButtons.length; i++) {
                minmaxrealms$drawWidgetFrame(context, autoAugmentRuleButtons[i], false, mouseX, mouseY, true);
            }
            minmaxrealms$drawWidgetFrame(context, autoAugmentStopModeButton, false, mouseX, mouseY, true);
        } else if (autoAugmentTab == TAB_PAUSE) {
            minmaxrealms$drawWidgetFrame(context, autoAugmentPausePlaceholderA, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoAugmentPausePlaceholderB, false, mouseX, mouseY, true);
        }

        minmaxrealms$drawWidgetFrame(context, autoAugmentStopNowButton, false, mouseX, mouseY, true);
        minmaxrealms$drawWidgetFrame(context, autoAugmentSpeedSlider, false, mouseX, mouseY, true);
    }

    @Unique
    private void minmaxrealms$drawAutoOrbingWidgetContours(DrawContext context, int mouseX, int mouseY) {
        minmaxrealms$drawWidgetFrame(context, autoOrbingMainTabButton, autoOrbingTab == TAB_ORB_MAIN, mouseX, mouseY, true);
        minmaxrealms$drawWidgetFrame(context, autoOrbingRulesTabButton, autoOrbingTab == TAB_ORB_RULES, mouseX, mouseY, true);

        if (autoOrbingTab == TAB_ORB_MAIN) {
            minmaxrealms$drawWidgetFrame(context, autoOrbingGuideButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoOrbingScanButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoOrbingStartButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoOrbingAutoButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoOrbingStatusButton, false, mouseX, mouseY, false);
        } else {
            for (int i = 0; i < autoOrbingRuleButtons.length; i++) {
                minmaxrealms$drawWidgetFrame(context, autoOrbingRuleButtons[i], false, mouseX, mouseY, true);
                if (autoOrbingRuleSliders[i] != null && autoOrbingRuleSliders[i].visible) {
                    minmaxrealms$drawAutoOrbingSliderAccent(
                        context,
                        autoOrbingRuleSliders[i].getX(),
                        autoOrbingRuleSliders[i].getY(),
                        autoOrbingRuleSliders[i].getWidth(),
                        autoOrbingRuleSliders[i].getHeight(),
                        AutoOrbingFeature.getRuleSliderValue(i)
                    );
                }
            }
            minmaxrealms$drawWidgetFrame(context, autoOrbingMinStatsButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoOrbingMinStatsRuleButton, false, mouseX, mouseY, true);
            minmaxrealms$drawWidgetFrame(context, autoOrbingStopModeButton, false, mouseX, mouseY, true);
            minmaxrealms$drawAutoOrbingRulesScrollHint(context);
        }

        minmaxrealms$drawWidgetFrame(context, autoOrbingStopNowButton, false, mouseX, mouseY, true);
    }

    @Unique
    private void minmaxrealms$drawAutoOrbingGuides(DrawContext context) {
        if (!AutoOrbingFeature.shouldRenderPlacementGuide((Screen) (Object) this, title.getString())) return;

        for (int i = 0; i < getScreenHandler().slots.size(); i++) {
            Slot slot = getScreenHandler().getSlot(i);
            int left = x + slot.x;
            int top = y + slot.y;
            Integer color = AutoOrbingFeature.getGuideColorForSlot((HandledScreen<?>) (Object) this, i);
            if (color != null) {
                context.fill(left + 1, top + 1, left + 17, top + 17, color & 0x33FFFFFF | (color & 0xFF000000));
                context.drawBorder(left, top, 18, 18, color | 0xFF000000);
            }
            if (AutoOrbingFeature.isTargetGuideSlot((HandledScreen<?>) (Object) this, i)) {
                int targetColor = 0xFFFFD84D;
                context.drawBorder(left, top, 18, 18, targetColor);
                context.fill(left + 4, top + 4, left + 6, top + 14, targetColor);
                context.fill(left + 12, top + 4, left + 14, top + 14, targetColor);
                context.fill(left + 4, top + 4, left + 14, top + 6, targetColor);
                context.fill(left + 4, top + 12, left + 14, top + 14, targetColor);
            }
        }
    }

    @Unique
    private void minmaxrealms$drawWidgetFrame(DrawContext context, ClickableWidget widget, boolean selected, int mouseX, int mouseY, boolean allowHover) {
        if (widget == null || !widget.visible) return;
        minmaxrealms$drawWidgetFrame(
            context,
            widget.getX(),
            widget.getY(),
            widget.getWidth(),
            widget.getHeight(),
            selected,
            allowHover && minmaxrealms$isHovered(mouseX, mouseY, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight())
        );
    }

    @Unique
    private void minmaxrealms$drawWidgetFrame(DrawContext context, int x, int y, int w, int h, boolean selected, boolean hovered) {
        int outer = selected ? WIDGET_BORDER : WIDGET_BORDER_DARK;
        int inner = selected ? 0xFF5E4A2F : 0xFF12151A;
        context.fill(x, y, x + w, y + h, 0x12000000);
        context.drawBorder(x, y, w, h, outer);
        context.drawBorder(x + 1, y + 1, w - 2, h - 2, inner);
        context.fill(x + 2, y + 2, x + w - 2, y + 3, WIDGET_HIGHLIGHT);
        context.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, 0x22000000);

        if (hovered) {
            int hoverFill = selected ? 0x12FFF1C1 : 0x10E7D19A;
            context.fill(x + 1, y + 1, x + w - 1, y + h - 1, hoverFill);
            context.drawBorder(x, y, w, h, selected ? 0xFFF7E4AF : 0xFFB29663);
        }
    }

    @Unique
    private void minmaxrealms$drawAutoOrbingSliderAccent(DrawContext context, int x, int y, int w, int h, double value) {
        int trackY = y + h - 8;
        int left = x + 10;
        int right = x + w - 10;
        int knobX = left + (int) Math.round((right - left) * Math.max(0d, Math.min(1d, value)));

        context.fill(left - 1, trackY - 1, right + 1, trackY + 5, 0xCC0B0D10);
        context.fill(left, trackY, right, trackY + 4, 0xFF3A4048);
        context.fill(left, trackY, knobX, trackY + 4, 0xFFF0D08A);

        context.fill(knobX - 6, trackY - 4, knobX + 6, trackY + 8, 0x55000000);
        context.fill(knobX - 5, trackY - 3, knobX + 5, trackY + 7, 0xFFFFE7A3);
        context.fill(knobX - 3, trackY - 2, knobX + 3, trackY + 6, 0xFFFFFFFF);
        context.fill(knobX - 1, y + 4, knobX + 1, y + h - 2, 0xFFFFF6D0);
    }

    @Unique
    private void minmaxrealms$drawAutoOrbingRulesScrollHint(DrawContext context) {
        if (autoOrbingRulesMaxScroll <= 0) return;
        int trackX = minmaxrealms$getAutoOrbingScrollTrackX();
        int trackTop = minmaxrealms$getAutoOrbingScrollTrackTop();
        int trackBottom = minmaxrealms$getAutoOrbingScrollTrackBottom();
        int thumbY = minmaxrealms$getAutoOrbingScrollThumbY();
        int thumbHeight = minmaxrealms$getAutoOrbingScrollThumbHeight();

        context.fill(trackX, trackTop, trackX + 2, trackBottom, 0x55392D1F);
        context.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, PANEL_BORDER);
    }

    @Unique
    private boolean minmaxrealms$isInAutoOrbingRulesViewport(double mouseX, double mouseY) {
        int left = minmaxrealms$getContentX();
        int top = minmaxrealms$getRulesGridTopY();
        int right = left + CTRL_CONTENT_W;
        int bottom = minmaxrealms$getControlPanelY() + CTRL_PANEL_H - PANEL_INNER_PAD - 6;
        return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
    }

    @Unique
    private int minmaxrealms$getAutoOrbingScrollTrackX() {
        return minmaxrealms$getControlPanelX() + CTRL_PANEL_W - 6;
    }

    @Unique
    private int minmaxrealms$getAutoOrbingScrollTrackTop() {
        return minmaxrealms$getRulesGridTopY();
    }

    @Unique
    private int minmaxrealms$getAutoOrbingScrollTrackBottom() {
        return minmaxrealms$getControlPanelY() + CTRL_PANEL_H - PANEL_INNER_PAD - 6;
    }

    @Unique
    private int minmaxrealms$getAutoOrbingScrollThumbHeight() {
        int trackHeight = Math.max(24, minmaxrealms$getAutoOrbingScrollTrackBottom() - minmaxrealms$getAutoOrbingScrollTrackTop());
        return Math.max(18, (int) Math.round((double) trackHeight * ((double) trackHeight / (trackHeight + autoOrbingRulesMaxScroll))));
    }

    @Unique
    private int minmaxrealms$getAutoOrbingScrollThumbY() {
        int trackTop = minmaxrealms$getAutoOrbingScrollTrackTop();
        int trackBottom = minmaxrealms$getAutoOrbingScrollTrackBottom();
        int trackHeight = Math.max(24, trackBottom - trackTop);
        int thumbHeight = minmaxrealms$getAutoOrbingScrollThumbHeight();
        int thumbTravel = Math.max(0, trackHeight - thumbHeight);
        return trackTop + (autoOrbingRulesMaxScroll <= 0 ? 0 : (int) Math.round((double) autoOrbingRulesScrollOffset / (double) autoOrbingRulesMaxScroll * thumbTravel));
    }

    @Unique
    private boolean minmaxrealms$isOverAutoOrbingScrollThumb(double mouseX, double mouseY) {
        if (autoOrbingRulesMaxScroll <= 0) return false;
        int thumbX = minmaxrealms$getAutoOrbingScrollTrackX() - 2;
        int thumbY = minmaxrealms$getAutoOrbingScrollThumbY();
        int thumbHeight = minmaxrealms$getAutoOrbingScrollThumbHeight();
        return mouseX >= thumbX && mouseX < thumbX + 8 && mouseY >= thumbY && mouseY < thumbY + thumbHeight;
    }

    @Unique
    private void minmaxrealms$setAutoOrbingScrollFromMouse(double mouseY) {
        int trackTop = minmaxrealms$getAutoOrbingScrollTrackTop();
        int trackBottom = minmaxrealms$getAutoOrbingScrollTrackBottom();
        int trackHeight = Math.max(24, trackBottom - trackTop);
        int thumbHeight = minmaxrealms$getAutoOrbingScrollThumbHeight();
        int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        int clampedThumbY = minmaxrealms$clamp((int) Math.round(mouseY), trackTop, trackTop + thumbTravel);
        double ratio = (double) (clampedThumbY - trackTop) / (double) thumbTravel;
        autoOrbingRulesScrollOffset = minmaxrealms$clamp((int) Math.round(ratio * autoOrbingRulesMaxScroll), 0, autoOrbingRulesMaxScroll);
    }

    @Unique
    private boolean minmaxrealms$isWithinViewport(int y, int height, int viewportTop, int viewportBottom) {
        return y + height > viewportTop && y < viewportBottom;
    }

    @Unique
    private int minmaxrealms$getMainStatusY(int controlY) {
        return controlY + CTRL_PANEL_H - PANEL_INNER_PAD - BTN_H - CHIP_H - 8;
    }

    @Unique
    private int minmaxrealms$getMainInfoY(int controlY) {
        return controlY + CTRL_PANEL_H - PANEL_INNER_PAD - CHIP_H;
    }

    @Unique
    private boolean minmaxrealms$isHovered(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Unique
    private static int minmaxrealms$clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
