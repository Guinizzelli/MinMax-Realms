package net.matte.drstandalone;

import net.matte.drstandalone.autoorbing.AutoOrbingFeature;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Locale;

public class DrStandaloneConfigScreen extends Screen {
    private static final int PANEL_W = 380;
    private static final int PANEL_SIDE_PAD = 18;
    private static final int GRID_W = 344;
    private static final int GRID_GAP = 12;
    private static final int COL_W = 166;
    private static final int TOP_TAB_GAP = 6;
    private static final int PANEL_INNER = 0xFF1A1E23;
    private static final int HEADER_BG = 0xFF1A1E23;
    private static final int HEADER_TEXT = 0xFFFFE9B8;
    private static final int SUBTEXT = 0xFFD7C78F;
    private static final int PANEL_BORDER = 0xFFF0D08A;
    private static final int PANEL_BORDER_SHADOW = 0xFF6A5736;
    private static final int WIDGET_BORDER = 0xFFF0D08A;
    private static final int WIDGET_BORDER_DARK = 0xFF6A5736;
    private static final int WIDGET_FILL = 0xFF1A1E23;
    private static final int WIDGET_FILL_DISABLED = 0xFF15181D;
    private static final int WIDGET_HIGHLIGHT = 0x33FFF2C0;
    private static final int TEXT_PRIMARY = 0xFFF6F2E6;
    private static final int TEXT_MUTED = 0xFF9AA4AF;
    private static final int HOVER_BORDER = 0xFFB29663;

    private static Section selectedSection = Section.DpsMeter;
    private static DpsSubSection selectedDpsSubSection = DpsSubSection.General;
    private static ItemRollsSubSection selectedItemRollsSubSection = ItemRollsSubSection.General;
    private static GemSubSection selectedGemSubSection = GemSubSection.General;
    private static int selectedGemCustomRuleIndex = -1;

    private final Screen parent;
    private TextFieldWidget gemKeywordsField;
    private TextFieldWidget slimeKeywordsField;
    private TextFieldWidget chestKeywordsField;
    private TextFieldWidget customRuleTitleField;
    private TextFieldWidget customRuleKeywordsField;

    public DrStandaloneConfigScreen(Screen parent) {
        super(Text.literal("MinMax Realms"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearChildren();
        gemKeywordsField = null;
        slimeKeywordsField = null;
        chestKeywordsField = null;
        customRuleTitleField = null;
        customRuleKeywordsField = null;

        DrStandaloneConfig config = DrStandaloneMod.config();
        int panelLeft = this.width / 2 - (PANEL_W / 2);
        int panelTop = 14;
        int tabY = panelTop + 22;
        int contentTop = panelTop + 58;
        int topTabWidth = (GRID_W - (TOP_TAB_GAP * 5)) / 6;
        int contentLeft = panelLeft + PANEL_SIDE_PAD;

        addDrawableChild(sectionButton(contentLeft, tabY, topTabWidth, "DPS", Section.DpsMeter));
        addDrawableChild(sectionButton(contentLeft + (topTabWidth + TOP_TAB_GAP), tabY, topTabWidth, "Rolls", Section.ItemRolls));
        addDrawableChild(sectionButton(contentLeft + ((topTabWidth + TOP_TAB_GAP) * 2), tabY, topTabWidth, "Gems", Section.GemMeter));
        addDrawableChild(sectionButton(contentLeft + ((topTabWidth + TOP_TAB_GAP) * 3), tabY, topTabWidth, "Augment", Section.AutoAugment));
        addDrawableChild(sectionButton(contentLeft + ((topTabWidth + TOP_TAB_GAP) * 4), tabY, topTabWidth, "Orbing", Section.AutoOrbing));
        addDrawableChild(sectionButton(contentLeft + ((topTabWidth + TOP_TAB_GAP) * 5), tabY, topTabWidth, "Misc", Section.Miscellaneous));

        switch (selectedSection) {
            case DpsMeter -> buildDpsSection(config, panelLeft, contentTop);
            case ItemRolls -> buildItemRollsSection(config, panelLeft, contentTop);
            case GemMeter -> buildGemSection(config, panelLeft, contentTop);
            case AutoAugment -> buildAutoAugmentSection(config, panelLeft, contentTop);
            case AutoOrbing -> buildAutoOrbingSection(config, panelLeft, contentTop);
            case Miscellaneous -> buildMiscSection(config, panelLeft, contentTop);
        }

        int bottomY = this.height - 44;
        addDrawableChild(themedButton(Text.literal("Save & Close"), button -> {
            config.save();
            close();
        }, contentLeft, bottomY, GRID_W, 20));
    }

    private void buildDpsSection(DrStandaloneConfig config, int panelLeft, int top) {
        int subY = top;
        int left = panelLeft + PANEL_SIDE_PAD;
        int right = left + COL_W + GRID_GAP;
        addDrawableChild(dpsSubButton(left, subY, 110, "General", DpsSubSection.General));
        addDrawableChild(dpsSubButton(left + 116, subY, 110, "Class", DpsSubSection.Class));
        addDrawableChild(dpsSubButton(left + 232, subY, 112, "HUD", DpsSubSection.Hud));

        int y = top + 32;

        switch (selectedDpsSubSection) {
            case General -> {
                addDrawableChild(toggleButton(left, y, 166, "Enabled", () -> config.dpsMeterEnabled, v -> config.dpsMeterEnabled = v));
                addDrawableChild(cycleButton(right, y, 166, () -> "Class: " + config.classProfile, new String[]{"None", "Warrior", "Rogue", "Paladin"}, v -> {
                    config.classProfile = v;
                    refresh();
                }));
                y += 24;
                addDrawableChild(cycleButton(left, y, 166, () -> "Tier: " + config.targetTier, new String[]{"T0", "T1", "T2", "T3", "T4", "T5"}, v -> config.targetTier = v));
                addDrawableChild(stepButton(right, y, 166, () -> "Target HP: " + format(config.targetHpPercent) + "%", -10, 10, v -> config.targetHpPercent = clamp(config.targetHpPercent + v, 0, 100)));
                y += 24;
                addDrawableChild(stepButton(left, y, 166, () -> "Melee APS: " + format(config.meleeSessionAps), -0.1, 0.1, v -> config.meleeSessionAps = clamp(config.meleeSessionAps + v, 0.5, 8)));
                y += 24;
                addDrawableChild(stepButton(left, y, 166, () -> "Mob HP Scale: " + format(config.mobHealthScale), -0.1, 0.1, v -> config.mobHealthScale = clamp(config.mobHealthScale + v, 0.1, 3)));
                addDrawableChild(stepButton(right, y, 166, () -> "Mob Armor Scale: " + format(config.mobArmorScale), -0.1, 0.1, v -> config.mobArmorScale = clamp(config.mobArmorScale + v, 0, 3)));
            }
            case Class -> {
                String helper = "Class-specific tuning will come later. Keep using General for now.";
                addDrawableChild(labelButton(left, y, 344, helper));
            }
            case Hud -> {
                addDrawableChild(stepButton(left, y, 166, () -> "Scale: " + format(config.dpsHudScale), -0.1, 0.1, v -> config.dpsHudScale = clamp(config.dpsHudScale + v, 0.5, 4.0)));
                addDrawableChild(withTooltip(themedButton(Text.literal("Open HUD Preview"), button -> {
                    if (this.client != null) {
                        this.client.setScreen(new DrDpsHudPreviewScreen(this));
                    }
                }, right, y, 166, 20), Tooltip.of(Text.literal("Drag the DPS meter and press ESC or Return to come back"))));
            }
        }
    }

    private void buildItemRollsSection(DrStandaloneConfig config, int panelLeft, int top) {
        int left = panelLeft + PANEL_SIDE_PAD;
        int right = left + COL_W + GRID_GAP;
        int subY = top;
        addDrawableChild(itemRollsSubButton(left, subY, 166, "General", ItemRollsSubSection.General));
        addDrawableChild(itemRollsSubButton(right, subY, 166, "Debug", ItemRollsSubSection.Debug));

        int y = top + 32;

        switch (selectedItemRollsSubSection) {
            case General -> {
                addDrawableChild(toggleButton(left, y, 166, "Enabled", () -> config.itemRollsEnabled, v -> config.itemRollsEnabled = v));
                addDrawableChild(cycleButton(right, y, 166, () -> "Roll Style: " + config.inlineStyle, new String[]{"Percent", "Range"}, v -> config.inlineStyle = v));
                y += 24;
                addDrawableChild(toggleButton(left, y, 166, "Level % Tag", () -> config.showOverallOnLevel, v -> config.showOverallOnLevel = v));
            }
            case Debug -> {
                addDrawableChild(toggleButton(left, y, 166, "Compact Code", () -> config.showCompactEnchantCode, v -> config.showCompactEnchantCode = v));
                addDrawableChild(labelButton(right, y, 166, "Shows stat/enchant code"));
                y += 24;
                addDrawableChild(toggleButton(left, y, 166, "Debug Enchant", () -> config.debugEnchantColorParsing, v -> config.debugEnchantColorParsing = v));
                addDrawableChild(labelButton(right, y, 166, "Logs (+X) style data"));
                y += 24;
                addDrawableChild(toggleButton(left, y, 166, "Hide Durability", () -> config.hideDurability, v -> config.hideDurability = v));
                addDrawableChild(toggleButton(right, y, 166, "Hide Item Id", () -> config.hideItemId, v -> config.hideItemId = v));
                y += 24;
                addDrawableChild(toggleButton(left, y, 166, "Hide Components", () -> config.hideComponentsLine, v -> config.hideComponentsLine = v));
                addDrawableChild(stepButton(right, y, 166, () -> "Max Stats: " + config.maxStats, -1, 1, v -> config.maxStats = (int) clamp(config.maxStats + v, 1, 20)));
            }
        }
    }

    private void buildGemSection(DrStandaloneConfig config, int panelLeft, int top) {
        int left = panelLeft + PANEL_SIDE_PAD;
        int right = left + COL_W + GRID_GAP;
        int subY = top;
        int tabW = 64;
        int gap = 6;
        addDrawableChild(gemSubButton(left, subY, tabW, "General", GemSubSection.General));
        addDrawableChild(gemSubButton(left + (tabW + gap), subY, 52, "HUD", GemSubSection.Hud));
        addDrawableChild(gemSubButton(left + 128, subY, 52, "Gems", GemSubSection.Gems));
        addDrawableChild(gemSubButton(left + 186, subY, 64, "Slimes", GemSubSection.Slimes));
        addDrawableChild(gemSubButton(left + 256, subY, 64, "Chests", GemSubSection.Chests));

        int customTabY = top + 24;
        int customTabX = left;
        int customStart = selectedGemCustomRuleIndex < 0 ? 0 : Math.max(0, Math.min(selectedGemCustomRuleIndex - 1, Math.max(0, config.gemCustomRules.size() - 4)));
        addDrawableChild(themedButton(Text.literal("+ Rule"), button -> {
            DrStandaloneConfig.GemCustomRule rule = new DrStandaloneConfig.GemCustomRule();
            config.gemCustomRules.add(rule);
            selectedGemSubSection = GemSubSection.CustomRule;
            selectedGemCustomRuleIndex = config.gemCustomRules.size() - 1;
            refresh();
        }, customTabX, customTabY, 72, 20));

        if (config.gemCustomRules.size() > 4) {
            addDrawableChild(themedButton(Text.literal("<"), button -> {
                selectedGemSubSection = GemSubSection.CustomRule;
                selectedGemCustomRuleIndex = Math.max(0, selectedGemCustomRuleIndex - 1);
                refresh();
            }, customTabX + 78, customTabY, 20, 20));
        }

        int ruleX = customTabX + (config.gemCustomRules.size() > 4 ? 104 : 78);
        int visibleRules = config.gemCustomRules.size() > 4 ? 3 : 4;
        for (int offset = 0; offset < visibleRules; offset++) {
            int index = customStart + offset;
            if (index >= config.gemCustomRules.size()) break;
            boolean active = selectedGemSubSection == GemSubSection.CustomRule && selectedGemCustomRuleIndex == index;
            String label = (active ? "> " : "") + "Rule " + (index + 1);
            addDrawableChild(themedButton(Text.literal(label), button -> {
                selectedGemSubSection = GemSubSection.CustomRule;
                selectedGemCustomRuleIndex = index;
                refresh();
            }, ruleX + (offset * 74), customTabY, 68, 20));
        }

        if (config.gemCustomRules.size() > 4) {
            addDrawableChild(themedButton(Text.literal(">"), button -> {
                selectedGemSubSection = GemSubSection.CustomRule;
                selectedGemCustomRuleIndex = Math.min(config.gemCustomRules.size() - 1, Math.max(0, selectedGemCustomRuleIndex) + 1);
                refresh();
            }, left + 324, customTabY, 20, 20));
        }

        int y = top + 56;
        syncGemSourceMode(config);
        switch (selectedGemSubSection) {
            case General -> {
                addDrawableChild(toggleButton(left, y, 166, "Enabled", () -> config.gemMeterEnabled, v -> config.gemMeterEnabled = v));
                addDrawableChild(withTooltip(themedButton(Text.literal("Reset Gem Meter"), button -> {
                    GemMeterFeature.resetSession();
                    refresh();
                }, right, y, 166, 20), Tooltip.of(Text.literal("Reset gained gems, rates and session timer"))));
                y += 24;
                addDrawableChild(labelButton(left, y, 344, "Use the sub-tabs to pick how each counter is parsed."));
            }
            case Hud -> {
                addDrawableChild(stepButton(left, y, 166, () -> "Scale: " + format(config.gemHudScale), -0.1, 0.1, v -> config.gemHudScale = clamp(config.gemHudScale + v, 0.5, 4.0)));
                addDrawableChild(stepButton(right, y, 166, () -> "HUD X: " + config.gemHudX, -10, 10, v -> config.gemHudX += v));
                y += 24;
                addDrawableChild(stepButton(left, y, 166, () -> "HUD Y: " + config.gemHudY, -10, 10, v -> config.gemHudY += v));
            }
            case Gems -> {
                addDrawableChild(cycleButton(left, y, 166, () -> "Parse: " + config.gemSourceMode, new String[]{"Disabled", "Inventory", "Chat", "Hybrid"}, v -> {
                    config.gemSourceMode = v;
                    applyGemSourceMode(config);
                    refresh();
                }));
                y += 24;
                gemKeywordsField = new TextFieldWidget(this.textRenderer, left, y, 344, 18, Text.literal("Gem chat keywords"));
                gemKeywordsField.setText(config.gemChatKeywords == null ? "" : config.gemChatKeywords);
                gemKeywordsField.setPlaceholder(Text.literal("gem,gems,emerald,emeralds,pouch"));
                gemKeywordsField.setChangedListener(value -> config.gemChatKeywords = value);
                addDrawableChild(gemKeywordsField);
                y += 22;
                addDrawableChild(labelButton(left, y, 344, "Chat keywords are used when parse mode includes Chat."));
            }
            case Slimes -> {
                addDrawableChild(cycleButton(left, y, 166, () -> "Parse: " + config.slimeParseMode, new String[]{"Disabled", "Chat"}, v -> {
                    config.slimeParseMode = v;
                    refresh();
                }));
                y += 24;
                slimeKeywordsField = new TextFieldWidget(this.textRenderer, left, y, 344, 18, Text.literal("Slime chat keywords"));
                slimeKeywordsField.setText(config.slimeChatKeywords == null ? "" : config.slimeChatKeywords);
                slimeKeywordsField.setPlaceholder(Text.literal("slime,loot slime,fall from,portal shards"));
                slimeKeywordsField.setChangedListener(value -> config.slimeChatKeywords = value);
                addDrawableChild(slimeKeywordsField);
                y += 22;
                addDrawableChild(labelButton(left, y, 344, "Keywords are used when slime parse mode is Chat."));
            }
            case Chests -> {
                addDrawableChild(cycleButton(left, y, 166, () -> "Parse: " + config.chestParseMode, new String[]{"Disabled", "Chat"}, v -> {
                    config.chestParseMode = v;
                    refresh();
                }));
                y += 24;
                chestKeywordsField = new TextFieldWidget(this.textRenderer, left, y, 344, 18, Text.literal("Chest chat keywords"));
                chestKeywordsField.setText(config.chestChatKeywords == null ? "" : config.chestChatKeywords);
                chestKeywordsField.setPlaceholder(Text.literal("key unlocks,nearby chest,chest"));
                chestKeywordsField.setChangedListener(value -> config.chestChatKeywords = value);
                addDrawableChild(chestKeywordsField);
                y += 22;
                addDrawableChild(labelButton(left, y, 344, "Keywords are used when chest parse mode is Chat."));
            }
            case CustomRule -> {
                if (config.gemCustomRules.isEmpty()) {
                    addDrawableChild(labelButton(left, y, 344, "Create a custom rule with + Rule."));
                    return;
                }
                int ruleIndex = Math.max(0, Math.min(selectedGemCustomRuleIndex, config.gemCustomRules.size() - 1));
                selectedGemCustomRuleIndex = ruleIndex;
                DrStandaloneConfig.GemCustomRule rule = config.gemCustomRules.get(ruleIndex);

                customRuleTitleField = new TextFieldWidget(this.textRenderer, left, y, 344, 18, Text.literal("Rule title"));
                customRuleTitleField.setText(rule.title == null ? "" : rule.title);
                customRuleTitleField.setPlaceholder(Text.literal("Custom counter title"));
                customRuleTitleField.setChangedListener(value -> rule.title = value);
                addDrawableChild(customRuleTitleField);
                y += 22;
                addDrawableChild(labelButton(left, y, 344, "Title shown on the Gem Meter HUD."));
                y += 24;
                addDrawableChild(cycleButton(left, y, 166, () -> "Parse: " + rule.parseMode, new String[]{"Disabled", "Chat"}, v -> {
                    rule.parseMode = v;
                    refresh();
                }));
                addDrawableChild(themedButton(Text.literal("Remove Rule"), button -> {
                    config.gemCustomRules.remove(ruleIndex);
                    if (config.gemCustomRules.isEmpty()) {
                        selectedGemSubSection = GemSubSection.General;
                        selectedGemCustomRuleIndex = -1;
                    } else {
                        selectedGemCustomRuleIndex = Math.min(ruleIndex, config.gemCustomRules.size() - 1);
                    }
                    refresh();
                }, right, y, 166, 20));
                y += 24;
                if ("Chat".equalsIgnoreCase(rule.parseMode)) {
                    customRuleKeywordsField = new TextFieldWidget(this.textRenderer, left, y, 344, 18, Text.literal("Chat keywords"));
                    customRuleKeywordsField.setText(rule.chatKeywords == null ? "" : rule.chatKeywords);
                    customRuleKeywordsField.setPlaceholder(Text.literal("keyword1,keyword2"));
                    customRuleKeywordsField.setChangedListener(value -> rule.chatKeywords = value);
                    addDrawableChild(customRuleKeywordsField);
                    y += 22;
                    addDrawableChild(labelButton(left, y, 344, "Comma-separated chat keywords for the custom counter."));
                } else {
                    addDrawableChild(labelButton(left, y, 344, "Chat mode enables a custom counter based on chat keywords."));
                }
            }
        }
    }

    private void buildAutoAugmentSection(DrStandaloneConfig config, int panelLeft, int top) {
        int left = panelLeft + PANEL_SIDE_PAD;
        int right = left + COL_W + GRID_GAP;
        int y = top;

        addDrawableChild(labelButton(left, y, 344, "Preview and future controls for the AutoAugment workflow."));
        y += 28;
        addDrawableChild(withTooltip(themedButton(Text.literal("Preview AutoAugment UI"), button -> {
            if (this.client != null && this.client.player != null) {
                this.client.setScreen(new DrAutoAugmentPreviewScreen(this, this.client.player.getInventory()));
            }
        }, left, y, 344, 20), Tooltip.of(Text.literal("Open a fake Armorsmith screen to preview the AutoAugment UI"))));
        y += 24;
        addDrawableChild(labelButton(left, y, 166, "More AutoAugment controls"));
        addDrawableChild(labelButton(right, y, 166, "coming soon"));
    }

    private void buildAutoOrbingSection(DrStandaloneConfig config, int panelLeft, int top) {
        int left = panelLeft + PANEL_SIDE_PAD;
        int right = left + COL_W + GRID_GAP;
        int y = top;

        addDrawableChild(themedButton(Text.literal("Enabled: " + onOff(config.autoOrbingEnabled)), button -> {
            config.autoOrbingEnabled = !config.autoOrbingEnabled;
            AutoOrbingFeature.reloadFromConfig();
            button.setMessage(Text.literal("Enabled: " + onOff(config.autoOrbingEnabled)));
            refresh();
        }, left, y, 166, 20));
        addDrawableChild(themedButton(Text.literal("Placement Guide: " + onOff(config.autoOrbingPlacementGuideEnabled)), button -> {
            config.autoOrbingPlacementGuideEnabled = !config.autoOrbingPlacementGuideEnabled;
            AutoOrbingFeature.reloadFromConfig();
            button.setMessage(Text.literal("Placement Guide: " + onOff(config.autoOrbingPlacementGuideEnabled)));
            refresh();
        }, right, y, 166, 20));
        y += 24;
        addDrawableChild(stepButton(left, y, 166, () -> "Action Delay: " + config.autoOrbingActionDelayTicks, -1, 1, v -> config.autoOrbingActionDelayTicks = (int) clamp(config.autoOrbingActionDelayTicks + v, 0, 20)));
        addDrawableChild(cycleButton(right, y, 166, () -> "Stop: " + config.autoOrbingStopMode, new String[]{"Any", "All"}, v -> config.autoOrbingStopMode = v));
        y += 24;
        addDrawableChild(toggleButton(left, y, 166, "Log To Chat", () -> config.autoOrbingLogToChat, v -> config.autoOrbingLogToChat = v));
        y += 24;
        addDrawableChild(withTooltip(themedButton(Text.literal("Preview Auto-Orbing UI"), button -> {
            if (this.client != null && this.client.player != null) {
                this.client.setScreen(new DrAutoOrbingPreviewScreen(this, this.client.player.getInventory()));
            }
        }, left, y, 344, 20), Tooltip.of(Text.literal("Open a fake inventory screen to preview the Auto-Orbing UI"))));
        y += 24;
        addDrawableChild(labelButton(left, y, 344, "Fixed V1 mapping: colored orb lanes + yellow target slot."));
    }

    private void buildMiscSection(DrStandaloneConfig config, int panelLeft, int top) {
        int left = panelLeft + PANEL_SIDE_PAD;
        int right = left + COL_W + GRID_GAP;
        int y = top;

        addDrawableChild(labelButton(left, y, 344, "Utility actions and standalone tools."));
        y += 28;
        addDrawableChild(withTooltip(themedButton(Text.literal("Build Optimizer"), button -> this.client.setScreen(new DrBuildOptimizerScreen(this)), left, y, 344, 20), Tooltip.of(Text.literal("Analyze your gear and suggest DPS optimizations"))));
        y += 24;
        addDrawableChild(withTooltip(themedButton(Text.literal("Reset HUD"), button -> {
            resetHud(config);
            refresh();
        }, left, y, 166, 20), Tooltip.of(Text.literal("Reset DPS and Gem HUD positions"))));
        addDrawableChild(labelButton(right, y, 166, "DPS + Gem overlays"));
    }

    private ButtonWidget sectionButton(int x, int y, int width, String label, Section section) {
        boolean active = selectedSection == section;
        Text text = Text.literal((active ? "> " : "") + label);
        return themedButton(text, button -> {
            selectedSection = section;
            refresh();
        }, x, y, width, 22);
    }

    private ButtonWidget dpsSubButton(int x, int y, int width, String label, DpsSubSection section) {
        boolean active = selectedDpsSubSection == section;
        Text text = Text.literal((active ? "> " : "") + label);
        return themedButton(text, button -> {
            selectedDpsSubSection = section;
            refresh();
        }, x, y, width, 20);
    }

    private ButtonWidget itemRollsSubButton(int x, int y, int width, String label, ItemRollsSubSection section) {
        boolean active = selectedItemRollsSubSection == section;
        Text text = Text.literal((active ? "> " : "") + label);
        return themedButton(text, button -> {
            selectedItemRollsSubSection = section;
            refresh();
        }, x, y, width, 20);
    }

    private ButtonWidget gemSubButton(int x, int y, int width, String label, GemSubSection section) {
        boolean active = selectedGemSubSection == section;
        Text text = Text.literal((active ? "> " : "") + label);
        return themedButton(text, button -> {
            selectedGemSubSection = section;
            refresh();
        }, x, y, width, 20);
    }

    private ButtonWidget toggleButton(int x, int y, int width, String label, BoolGetter getter, BoolSetter setter) {
        return themedButton(Text.literal(label + ": " + onOff(getter.get())), button -> {
            setter.set(!getter.get());
            button.setMessage(Text.literal(label + ": " + onOff(getter.get())));
        }, x, y, width, 20);
    }

    private ButtonWidget cycleButton(int x, int y, int width, TextGetter getter, String[] values, StringSetter setter) {
        return themedButton(Text.literal(getter.get()), button -> {
            String current = currentValue(getter.get());
            int index = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(current)) {
                    index = i;
                    break;
                }
            }
            setter.set(values[(index + 1) % values.length]);
            button.setMessage(Text.literal(getter.get()));
        }, x, y, width, 20);
    }

    private ButtonWidget stepButton(int x, int y, int width, TextGetter getter, double minus, double plus, DoubleSetter setter) {
        return withTooltip(themedButton(Text.literal(getter.get()), button -> {
            setter.set(hasShiftDown() ? plus : minus);
            button.setMessage(Text.literal(getter.get()));
        }, x, y, width, 20), Tooltip.of(Text.literal("Click = -, Shift+Click = +")));
    }

    private ClickableWidget labelButton(int x, int y, int width, String text) {
        return new StaticLabelWidget(x, y, width, 20, Text.literal(text));
    }

    private ButtonWidget themedButton(Text text, ButtonWidget.PressAction action, int x, int y, int width, int height) {
        return new ThemedButtonWidget(x, y, width, height, text, action);
    }

    private ButtonWidget withTooltip(ButtonWidget widget, Tooltip tooltip) {
        widget.setTooltip(tooltip);
        return widget;
    }

    private final class ThemedButtonWidget extends ButtonWidget {
        private ThemedButtonWidget(int x, int y, int width, int height, Text message, PressAction onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            boolean hovered = this.visible && mouseX >= getX() && mouseX < getX() + getWidth() && mouseY >= getY() && mouseY < getY() + getHeight();
            int outer = hovered ? HOVER_BORDER : WIDGET_BORDER_DARK;
            int fill = WIDGET_FILL;
            int textColor = TEXT_PRIMARY;

            context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
            context.drawBorder(getX(), getY(), getWidth(), getHeight(), outer);
            context.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + 2, WIDGET_HIGHLIGHT);
            if (hovered) {
                context.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, 0x08E7D19A);
            }

            context.drawCenteredTextWithShadow(
                DrStandaloneConfigScreen.this.textRenderer,
                this.getMessage(),
                getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2,
                textColor
            );
        }
    }

    private final class StaticLabelWidget extends ClickableWidget {
        private StaticLabelWidget(int x, int y, int width, int height, Text message) {
            super(x, y, width, height, message);
            this.active = false;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            context.drawCenteredTextWithShadow(
                DrStandaloneConfigScreen.this.textRenderer,
                this.getMessage(),
                getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2,
                TEXT_MUTED
            );
        }

        @Override
        protected void appendClickableNarrations(net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
        }
    }

    private void refresh() {
        clearAndInit();
    }

    private static String currentValue(String label) {
        int idx = label.indexOf(':');
        return idx >= 0 ? label.substring(idx + 1).trim() : label;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private static void syncGemSourceMode(DrStandaloneConfig config) {
        if ("Disabled".equals(config.gemSourceMode) || "Inventory".equals(config.gemSourceMode) || "Chat".equals(config.gemSourceMode) || "Hybrid".equals(config.gemSourceMode)) {
            applyGemSourceMode(config);
            return;
        }

        if (config.gemInventorySource && config.gemChatSource) config.gemSourceMode = "Hybrid";
        else if (config.gemChatSource || config.gemActionBarSource) config.gemSourceMode = "Chat";
        else config.gemSourceMode = "Inventory";
        applyGemSourceMode(config);
    }

    private static void applyGemSourceMode(DrStandaloneConfig config) {
        switch (config.gemSourceMode) {
            case "Disabled" -> {
                config.gemInventorySource = false;
                config.gemChatSource = false;
                config.gemActionBarSource = false;
            }
            case "Inventory" -> {
                config.gemInventorySource = true;
                config.gemChatSource = false;
                config.gemActionBarSource = false;
            }
            case "Chat" -> {
                config.gemInventorySource = false;
                config.gemChatSource = true;
                config.gemActionBarSource = true;
            }
            default -> {
                config.gemSourceMode = "Hybrid";
                config.gemInventorySource = true;
                config.gemChatSource = true;
                config.gemActionBarSource = true;
            }
        }
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    @Override
    public void close() {
        DrStandaloneMod.config().save();
        this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int left = this.width / 2 - (PANEL_W / 2);
        int top = 14;
        int panelWidth = PANEL_W;
        int panelHeight = this.height - 28;
        context.fill(left, top, left + panelWidth, top + panelHeight, PANEL_INNER);
        context.drawBorder(left, top, panelWidth, panelHeight, PANEL_BORDER);
        context.drawTextWithShadow(this.textRenderer, Text.literal("MinMax Realms"), left + 12, top + 8, HEADER_TEXT);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Module config"), left + panelWidth - 92, top + 8, SUBTEXT);

        super.render(context, mouseX, mouseY, delta);
        drawTextFieldChrome(context, gemKeywordsField);
        drawTextFieldChrome(context, slimeKeywordsField);
        drawTextFieldChrome(context, chestKeywordsField);
        drawTextFieldChrome(context, customRuleTitleField);
        drawTextFieldChrome(context, customRuleKeywordsField);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Use Controls to change the open keybind."), this.width / 2, top + panelHeight - 16, TEXT_MUTED);
    }

    private void drawTextFieldChrome(DrawContext context, TextFieldWidget field) {
        if (field == null || !field.isVisible()) return;
        int x = field.getX();
        int y = field.getY();
        int w = field.getWidth();
        int h = field.getHeight();
        context.drawBorder(x - 1, y - 1, w + 2, h + 2, WIDGET_BORDER_DARK);
        context.fill(x, y, x + w, y + h, WIDGET_FILL);
        context.fill(x + 1, y + 1, x + w - 1, y + 2, WIDGET_HIGHLIGHT);
    }

    private enum Section {
        DpsMeter,
        ItemRolls,
        GemMeter,
        AutoAugment,
        AutoOrbing,
        Miscellaneous
    }

    private enum DpsSubSection {
        General,
        Class,
        Hud
    }

    private enum ItemRollsSubSection {
        General,
        Debug
    }

    private enum GemSubSection {
        General,
        Hud,
        Gems,
        Slimes,
        Chests,
        CustomRule
    }

    private interface BoolGetter { boolean get(); }
    private interface BoolSetter { void set(boolean value); }
    private interface DoubleSetter { void set(double value); }
    private interface StringSetter { void set(String value); }
    private interface TextGetter { String get(); }
}
