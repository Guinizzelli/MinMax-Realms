package net.matte.drstandalone;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Locale;

public final class DrItemRollsFeature {
    public record TooltipSnapshot(List<Text> cleanedLines, DrRarityHelper.TooltipTheme rarity, DrStatsDatabase.TooltipAnalysis analysis) {
    }

    private DrItemRollsFeature() {
    }

    public static void init() {
        ItemTooltipCallback.EVENT.register(DrItemRollsFeature::onTooltip);
    }

    private static void onTooltip(ItemStack stack, Item.TooltipContext context, TooltipType type, List<Text> lines) {
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (lines.isEmpty()) return;

        DrRarityHelper.queue(stack, lines);
        if (!config.itemRollsEnabled) return;

        TooltipSnapshot snapshot = inspectTooltip(stack, lines, config);
        List<Text> cleaned = snapshot.cleanedLines();
        DrStatsDatabase.TooltipAnalysis analysis = snapshot.analysis();
        if (analysis == null) return;

        lines.clear();
        lines.addAll(cleaned);
        if (config.showOverallOnLevel) injectOverallRoll(lines, analysis);
        if (config.statBreakdown) injectStatBreakdown(lines, analysis, config);
    }

    public static TooltipSnapshot inspectTooltip(ItemStack stack, List<Text> sourceLines, DrStandaloneConfig config) {
        List<Text> cleaned = new java.util.ArrayList<>(sourceLines);
        cleanTooltip(cleaned, config);
        DrRarityHelper.TooltipTheme rarity = DrRarityHelper.resolve(stack, cleaned);
        DrStatsDatabase.TooltipAnalysis analysis = DrStatsDatabase.get().analyze(stack, cleaned, rarity);
        return new TooltipSnapshot(List.copyOf(cleaned), rarity, analysis);
    }

    private static void cleanTooltip(List<Text> lines, DrStandaloneConfig config) {
        lines.removeIf(line -> shouldHideLine(line.getString(), config));
    }

    private static boolean shouldHideLine(String raw, DrStandaloneConfig config) {
        String line = raw == null ? "" : raw.replace('\u00A0', ' ').trim();
        String upper = line.toUpperCase(Locale.ROOT);

        if (config.hideDurability && upper.startsWith("DURABILITY:")) return true;
        if (config.hideItemId && line.toLowerCase(Locale.ROOT).startsWith("minecraft:")) return true;
        if (config.hideComponentsLine && upper.matches("\\d+\\s+COMPONENT\\(S\\)")) return true;
        return false;
    }

    private static void injectOverallRoll(List<Text> lines, DrStatsDatabase.TooltipAnalysis analysis) {
        int levelIndex = findLevelLine(lines);
        if (levelIndex < 0) return;

        Text base = lines.get(levelIndex);
        MutableText updated = Text.empty().append(base)
            .append(Text.literal("  "))
            .append(Text.literal("[" + formatPercent(analysis.averagePercent) + "]").formatted(colorForPercent(analysis.averagePercent, false)));
        lines.set(levelIndex, updated);
    }

    private static void injectStatBreakdown(List<Text> lines, DrStatsDatabase.TooltipAnalysis analysis, DrStandaloneConfig config) {
        int shown = 0;
        for (DrStatsDatabase.StatMatch match : analysis.matches) {
            if (shown >= Math.max(1, config.maxStats)) break;

            int index = findStatLine(lines, match.valueText);
            if (index < 0) continue;

            Text base = lines.get(index);
            MutableText updated = Text.empty().append(base)
                .append(Text.literal("  "))
                .append(Text.literal(inlineTag(match, config)).formatted(colorForPercent(match.percent, match.overcap)));
            lines.set(index, updated);
            shown++;
        }
    }

    private static int findLevelLine(List<Text> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String current = sanitize(lines.get(i).getString());
            if (current.toUpperCase(Locale.ROOT).startsWith("LEVEL:")) return i;
        }
        return -1;
    }

    private static int findStatLine(List<Text> lines, String valueText) {
        String target = sanitize(valueText);
        for (int i = 0; i < lines.size(); i++) {
            String current = sanitize(lines.get(i).getString());
            if (current.equals(target)) return i;
        }
        return -1;
    }

    private static String inlineTag(DrStatsDatabase.StatMatch match, DrStandaloneConfig config) {
        if ("Range".equalsIgnoreCase(config.inlineStyle)) return "[" + match.rangeText + "]";
        if (match.overcap) return config.fancyMode ? "[OVERCAP]" : "[Overcap]";
        if (match.percent <= 0.0001) return "[MIN]";
        if (match.percent >= 99.9999) return "[MAX]";
        return "[" + formatPercent(match.percent) + "]";
    }

    private static String sanitize(String value) {
        return value
            .replace('\u00A0', ' ')
            .replaceAll("\\s+\\[(?:MIN|MAX|OVERCAP|ROLLS\\s+\\d+(?:\\.\\d+)?%|\\d+(?:\\.\\d+)?%|\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?)?)\\]\\s*$", "")
            .trim();
    }

    private static String formatPercent(double percent) {
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    private static Formatting colorForPercent(double percent, boolean overcap) {
        if (overcap) return Formatting.LIGHT_PURPLE;
        if (percent >= 85) return Formatting.GREEN;
        if (percent >= 65) return Formatting.YELLOW;
        if (percent >= 40) return Formatting.GOLD;
        return Formatting.RED;
    }
}
