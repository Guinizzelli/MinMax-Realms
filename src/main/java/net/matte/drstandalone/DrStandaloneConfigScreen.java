package net.matte.drstandalone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;

public class DrStandaloneConfigScreen extends Screen {
    private static Section selectedSection = Section.DpsMeter;
    private static DpsSubSection selectedDpsSubSection = DpsSubSection.General;

    private final Screen parent;

    public DrStandaloneConfigScreen(Screen parent) {
        super(Text.literal("MinMax Realms"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearChildren();

        DrStandaloneConfig config = DrStandaloneMod.config();
        int panelLeft = this.width / 2 - 190;
        int panelTop = 14;
        int tabY = panelTop + 22;
        int contentTop = panelTop + 58;

        addDrawableChild(sectionButton(panelLeft + 12, tabY, 112, "DPS Meter", Section.DpsMeter));
        addDrawableChild(sectionButton(panelLeft + 134, tabY, 112, "Item Rolls", Section.ItemRolls));
        addDrawableChild(sectionButton(panelLeft + 256, tabY, 112, "Gem Meter", Section.GemMeter));

        switch (selectedSection) {
            case DpsMeter -> buildDpsSection(config, panelLeft, contentTop);
            case ItemRolls -> buildItemRollsSection(config, panelLeft, contentTop);
            case GemMeter -> buildGemSection(config, panelLeft, contentTop);
        }

        int bottomY = this.height - 44;
        addDrawableChild(ButtonWidget.builder(Text.literal("Build Optimizer"), button -> this.client.setScreen(new DrBuildOptimizerScreen(this)))
            .dimensions(this.width / 2 - 170, bottomY - 24, 340, 20)
            .tooltip(Tooltip.of(Text.literal("Analyze your gear and suggest DPS optimizations")))
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Reset HUD"), button -> {
            resetHud(config);
            refresh();
        }).dimensions(this.width / 2 - 170, bottomY, 160, 20).tooltip(Tooltip.of(Text.literal("Reset DPS and Gem HUD positions"))).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Save & Close"), button -> {
            config.save();
            close();
        }).dimensions(this.width / 2 + 10, bottomY, 160, 20).build());
    }

    private void buildDpsSection(DrStandaloneConfig config, int panelLeft, int top) {
        int subY = top;
        addDrawableChild(dpsSubButton(panelLeft + 12, subY, 110, "General", DpsSubSection.General));
        addDrawableChild(dpsSubButton(panelLeft + 128, subY, 110, "Class", DpsSubSection.Class));
        addDrawableChild(dpsSubButton(panelLeft + 244, subY, 124, "HUD", DpsSubSection.Hud));

        int left = panelLeft + 12;
        int right = panelLeft + 190;
        int y = top + 32;

        switch (selectedDpsSubSection) {
            case General -> {
                addDrawableChild(toggleButton(left, y, 166, "Enabled", () -> config.dpsMeterEnabled, v -> config.dpsMeterEnabled = v));
                addDrawableChild(cycleButton(right, y, 166, () -> "Class: " + config.classProfile, new String[]{"None", "Warrior", "Rogue", "Paladin"}, v -> {
                    config.classProfile = v;
                    refresh();
                }));
                y += 24;
                addDrawableChild(cycleButton(left, y, 166, () -> "Tier: " + config.targetTier, new String[]{"T1", "T2", "T3", "T4", "T5"}, v -> config.targetTier = v));
                addDrawableChild(stepButton(right, y, 166, () -> "Target HP: " + format(config.targetHpPercent) + "%", -10, 10, v -> config.targetHpPercent = clamp(config.targetHpPercent + v, 0, 100)));
                y += 24;
                addDrawableChild(stepButton(left, y, 166, () -> "Passive Regen: " + format(config.basePassiveEnergyRegen), -0.5, 0.5, v -> config.basePassiveEnergyRegen = clamp(config.basePassiveEnergyRegen + v, 0, 20)));
                addDrawableChild(stepButton(right, y, 166, () -> "Attack Cap: " + format(config.practicalAttackCap), -0.25, 0.25, v -> config.practicalAttackCap = clamp(config.practicalAttackCap + v, 0.5, 8)));
                y += 24;
                addDrawableChild(stepButton(left, y, 166, () -> "Mob HP Scale: " + format(config.mobHealthScale), -0.1, 0.1, v -> config.mobHealthScale = clamp(config.mobHealthScale + v, 0.1, 3)));
                addDrawableChild(stepButton(right, y, 166, () -> "Mob Armor Scale: " + format(config.mobArmorScale), -0.1, 0.1, v -> config.mobArmorScale = clamp(config.mobArmorScale + v, 0, 3)));
            }
            case Class -> {
                String helper = switch (config.classProfile) {
                    case "Warrior" -> "Axe shatter, berserk crit, armor check";
                    case "Rogue" -> "Poison conversion, opening buffs, ability mods";
                    case "Paladin" -> "Pure conversion, mob damage, ability buff";
                    default -> "Choose a class first in General.";
                };
                addDrawableChild(labelButton(left, y, 344, helper));
                y += 28;

                switch (config.classProfile) {
                    case "Warrior" -> {
                        addDrawableChild(toggleButton(left, y, 166, "Berserk Crit", () -> config.warriorBerserk, v -> config.warriorBerserk = v));
                        addDrawableChild(stepButton(right, y, 166, () -> "Combat Stacks: " + config.warriorCombatStacks, -1, 1, v -> config.warriorCombatStacks = (int) clamp(config.warriorCombatStacks + v, 0, 10)));
                        y += 24;
                        addDrawableChild(toggleButton(left, y, 166, "Armor Condition", () -> config.warriorArmorCondition, v -> config.warriorArmorCondition = v));
                    }
                    case "Rogue" -> {
                        addDrawableChild(toggleButton(left, y, 166, "Target Poisoned", () -> config.rogueTargetPoisoned, v -> config.rogueTargetPoisoned = v));
                        addDrawableChild(toggleButton(right, y, 166, "Opening Buffs", () -> config.rogueFirstSeconds, v -> config.rogueFirstSeconds = v));
                        y += 24;
                        addDrawableChild(cycleButton(left, y, 166, () -> "Opening DPS: " + config.rogueOpeningDpsModel, new String[]{"Flat", "Percent"}, v -> config.rogueOpeningDpsModel = v));
                        addDrawableChild(toggleButton(right, y, 166, "Dash Bonus", () -> config.rogueDashBonus, v -> config.rogueDashBonus = v));
                        y += 24;
                        addDrawableChild(toggleButton(left, y, 166, "Ability Active", () -> config.rogueAbility, v -> {
                            config.rogueAbility = v;
                            refresh();
                        }));
                        if (config.rogueAbility) {
                            addDrawableChild(stepButton(right, y, 166, () -> "Ability Stacks: " + config.rogueAbilityStacks, -1, 1, v -> config.rogueAbilityStacks = (int) clamp(config.rogueAbilityStacks + v, 0, 10)));
                            y += 24;
                            addDrawableChild(toggleButton(left, y, 166, "Execute Mod", () -> config.rogueExecuteMod, v -> config.rogueExecuteMod = v));
                            addDrawableChild(toggleButton(right, y, 166, "Piercing Mod", () -> config.roguePiercingMod, v -> config.roguePiercingMod = v));
                        }
                    }
                    case "Paladin" -> {
                        addDrawableChild(toggleButton(left, y, 166, "Ability Active", () -> config.paladinAbility, v -> {
                            config.paladinAbility = v;
                            refresh();
                        }));
                        addDrawableChild(toggleButton(right, y, 166, "Pure Buff", () -> config.paladinPureMod, v -> config.paladinPureMod = v));
                    }
                }
            }
            case Hud -> {
                addDrawableChild(stepButton(left, y, 166, () -> "HUD X: " + config.dpsHudX, -10, 10, v -> config.dpsHudX += v));
                addDrawableChild(stepButton(right, y, 166, () -> "HUD Y: " + config.dpsHudY, -10, 10, v -> config.dpsHudY += v));
                y += 24;
                addDrawableChild(stepButton(left, y, 166, () -> "Scale: " + format(config.dpsHudScale), -0.1, 0.1, v -> config.dpsHudScale = clamp(config.dpsHudScale + v, 0.5, 4.0)));
                addDrawableChild(labelButton(right, y, 166, "Use Shift+Click to increase"));
            }
        }
    }

    private void buildItemRollsSection(DrStandaloneConfig config, int panelLeft, int top) {
        int left = panelLeft + 12;
        int right = panelLeft + 190;
        int y = top;

        addDrawableChild(toggleButton(left, y, 166, "Enabled", () -> config.itemRollsEnabled, v -> config.itemRollsEnabled = v));
        addDrawableChild(cycleButton(right, y, 166, () -> "Roll Style: " + config.inlineStyle, new String[]{"Percent", "Range"}, v -> config.inlineStyle = v));
        y += 24;
        addDrawableChild(toggleButton(left, y, 166, "Breakdown", () -> config.statBreakdown, v -> config.statBreakdown = v));
        addDrawableChild(toggleButton(right, y, 166, "Level % Tag", () -> config.showOverallOnLevel, v -> config.showOverallOnLevel = v));
        y += 24;
        addDrawableChild(toggleButton(left, y, 166, "Hide Durability", () -> config.hideDurability, v -> config.hideDurability = v));
        addDrawableChild(toggleButton(right, y, 166, "Hide Item Id", () -> config.hideItemId, v -> config.hideItemId = v));
        y += 24;
        addDrawableChild(toggleButton(left, y, 166, "Hide Components", () -> config.hideComponentsLine, v -> config.hideComponentsLine = v));
        addDrawableChild(stepButton(right, y, 166, () -> "Max Stats: " + config.maxStats, -1, 1, v -> config.maxStats = (int) clamp(config.maxStats + v, 1, 20)));
        y += 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Open Item Codex"), button -> this.client.setScreen(new DrCodexWikiScreen(this, DrCodexWikiScreen.View.Codex, 0, 0)))
            .dimensions(left, y, 166, 20)
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Open Stats Wiki"), button -> this.client.setScreen(new DrCodexWikiScreen(this, DrCodexWikiScreen.View.Wiki, 0, 0)))
            .dimensions(right, y, 166, 20)
            .build());
    }

    private void buildGemSection(DrStandaloneConfig config, int panelLeft, int top) {
        int left = panelLeft + 12;
        int right = panelLeft + 190;
        int y = top;

        addDrawableChild(toggleButton(left, y, 166, "Enabled", () -> config.gemMeterEnabled, v -> config.gemMeterEnabled = v));
        addDrawableChild(stepButton(right, y, 166, () -> "Scale: " + format(config.gemHudScale), -0.1, 0.1, v -> config.gemHudScale = clamp(config.gemHudScale + v, 0.5, 4.0)));
        y += 24;
        addDrawableChild(stepButton(left, y, 166, () -> "HUD X: " + config.gemHudX, -10, 10, v -> config.gemHudX += v));
        addDrawableChild(stepButton(right, y, 166, () -> "HUD Y: " + config.gemHudY, -10, 10, v -> config.gemHudY += v));
        y += 24;
        addDrawableChild(toggleButton(left, y, 166, "Inventory Source", () -> config.gemInventorySource, v -> config.gemInventorySource = v));
        addDrawableChild(toggleButton(right, y, 166, "Chat Source", () -> config.gemChatSource, v -> config.gemChatSource = v));
        y += 24;
        addDrawableChild(toggleButton(left, y, 166, "ActionBar Source", () -> config.gemActionBarSource, v -> config.gemActionBarSource = v));
    }

    private ButtonWidget sectionButton(int x, int y, int width, String label, Section section) {
        boolean active = selectedSection == section;
        Text text = Text.literal((active ? "> " : "") + label);
        return ButtonWidget.builder(text, button -> {
            selectedSection = section;
            refresh();
        }).dimensions(x, y, width, 22).build();
    }

    private ButtonWidget dpsSubButton(int x, int y, int width, String label, DpsSubSection section) {
        boolean active = selectedDpsSubSection == section;
        Text text = Text.literal((active ? "> " : "") + label);
        return ButtonWidget.builder(text, button -> {
            selectedDpsSubSection = section;
            refresh();
        }).dimensions(x, y, width, 20).build();
    }

    private ButtonWidget toggleButton(int x, int y, int width, String label, BoolGetter getter, BoolSetter setter) {
        return ButtonWidget.builder(Text.literal(label + ": " + onOff(getter.get())), button -> {
            setter.set(!getter.get());
            button.setMessage(Text.literal(label + ": " + onOff(getter.get())));
        }).dimensions(x, y, width, 20).build();
    }

    private ButtonWidget cycleButton(int x, int y, int width, TextGetter getter, String[] values, StringSetter setter) {
        return ButtonWidget.builder(Text.literal(getter.get()), button -> {
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
        }).dimensions(x, y, width, 20).build();
    }

    private ButtonWidget stepButton(int x, int y, int width, TextGetter getter, double minus, double plus, DoubleSetter setter) {
        return ButtonWidget.builder(Text.literal(getter.get()), button -> {
            setter.set(hasShiftDown() ? plus : minus);
            button.setMessage(Text.literal(getter.get()));
        }).dimensions(x, y, width, 20).tooltip(Tooltip.of(Text.literal("Click = -, Shift+Click = +"))).build();
    }

    private ButtonWidget labelButton(int x, int y, int width, String text) {
        ButtonWidget widget = ButtonWidget.builder(Text.literal(text), button -> {}).dimensions(x, y, width, 20).build();
        widget.active = false;
        return widget;
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
        renderBackground(context, mouseX, mouseY, delta);

        int left = this.width / 2 - 190;
        int top = 14;
        int panelWidth = 380;
        int panelHeight = this.height - 28;
        context.fill(left, top, left + panelWidth, top + panelHeight, 0xD0151A22);
        context.drawBorder(left, top, panelWidth, panelHeight, 0xFF4A4F59);
        context.fill(left + 1, top + 1, left + panelWidth - 1, top + 26, 0xAA1F2631);
        context.drawTextWithShadow(this.textRenderer, Text.literal("MinMax Realms"), left + 12, top + 8, 0xFFF1E6B8);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Module config"), left + panelWidth - 92, top + 8, 0xFF97A7BA);

        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Use Controls to change the open keybind."), this.width / 2, this.height - 18, 0xFF9AA0AA);
    }

    private enum Section {
        DpsMeter,
        ItemRolls,
        GemMeter
    }

    private enum DpsSubSection {
        General,
        Class,
        Hud
    }

    private interface BoolGetter { boolean get(); }
    private interface BoolSetter { void set(boolean value); }
    private interface DoubleSetter { void set(double value); }
    private interface StringSetter { void set(String value); }
    private interface TextGetter { String get(); }
}
