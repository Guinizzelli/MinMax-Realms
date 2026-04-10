package net.matte.drstandalone;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrItemRollsFeature {
    private static final Pattern ENCHANT_BONUS_SUFFIX = Pattern.compile("\\((\\+[+-]?\\d+(?:\\.\\d+)?%?)\\)");
    private static final Pattern ENCHANT_BONUS_PART = Pattern.compile("^(\\+[+-]?\\d+(?:\\.\\d+)?%?)$");
    private static final Pattern STAT_LABEL_PATTERN = Pattern.compile("^\\s*([^:]+):");
    private static final Set<String> DEBUGGED_ENCHANT_LINES = new HashSet<>();

    public record TooltipSnapshot(List<Text> cleanedLines, DrRarityHelper.TooltipTheme rarity, boolean transmuted, DrStatsDatabase.TooltipAnalysis analysis) {
    }

    private record StatEnchantCode(String statCode, String enchantValue, DrRarityHelper.TooltipTheme theme) {
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
        if (config.showOverallOnLevel) injectOverallRoll(stack, lines, snapshot, config);
        injectStatBreakdown(lines, analysis, config);
    }

    public static TooltipSnapshot inspectTooltip(ItemStack stack, List<Text> sourceLines, DrStandaloneConfig config) {
        List<Text> cleaned = new java.util.ArrayList<>(sourceLines);
        cleanTooltip(cleaned, config);
        DrRarityHelper.TooltipTheme rarity = DrRarityHelper.resolve(stack, cleaned);
        boolean transmuted = DrRarityHelper.isTransmuted(cleaned);
        DrStatsDatabase.TooltipAnalysis analysis = DrStatsDatabase.get().analyze(stack, cleaned, rarity);
        return new TooltipSnapshot(List.copyOf(cleaned), rarity, transmuted, analysis);
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

    private static void injectOverallRoll(ItemStack stack, List<Text> lines, TooltipSnapshot snapshot, DrStandaloneConfig config) {
        int levelIndex = findLevelLine(lines);
        if (levelIndex < 0) return;

        Text base = lines.get(levelIndex);
        DrStatsDatabase.TooltipAnalysis analysis = snapshot.analysis();
        MutableText updated = Text.empty().append(base)
            .append(Text.literal("  "))
            .append(Text.literal("[" + formatPercent(analysis.averagePercent) + "]").formatted(colorForPercent(analysis.averagePercent, false)));
        if (config.showCompactEnchantCode) {
            updated.append(Text.literal("  "));
            updated.append(buildCompactEnchantCode(lines, analysis));
        }
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

    private static MutableText buildCompactEnchantCode(List<Text> lines, DrStatsDatabase.TooltipAnalysis analysis) {
        String statCode = buildStatLetterCode(analysis);
        List<StatEnchantCode> enchants = extractStatEnchantCodes(lines);

        MutableText out = Text.literal("{").formatted(Formatting.DARK_GRAY);
        out.append(Text.literal(statCode.isBlank() ? "-" : statCode).formatted(Formatting.GRAY));
        out.append(Text.literal("|").formatted(Formatting.DARK_GRAY));
        if (enchants.isEmpty()) {
            out.append(Text.literal("-").formatted(Formatting.DARK_GRAY));
        } else {
            for (int i = 0; i < enchants.size(); i++) {
                if (i > 0) out.append(Text.literal(",").formatted(Formatting.DARK_GRAY));
                StatEnchantCode enchant = enchants.get(i);
                Formatting formatting = upgradeFormatting(enchant.theme());
                out.append(Text.literal(enchant.statCode()).formatted(Formatting.GRAY));
                out.append(Text.literal(enchant.enchantValue()).formatted(formatting));
                out.append(Text.literal(themeCode(enchant.theme())).formatted(formatting));
            }
        }
        out.append(Text.literal("}").formatted(Formatting.DARK_GRAY));
        return out;
    }

    private static List<StatEnchantCode> extractStatEnchantCodes(List<Text> lines) {
        List<StatEnchantCode> result = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        for (Text line : lines) {
            String raw = line == null ? "" : line.getString();
            if (raw == null || raw.isBlank()) continue;
            if (raw.toUpperCase(Locale.ROOT).startsWith("PASSIVE:")) continue;

            String label = extractStatLabel(raw);
            if (label.isBlank()) continue;

            List<EnchantPart> parts = extractEnchantParts(line);
            if (parts.isEmpty()) continue;

            String statCode = statLetterCode(label);
            if (statCode.isBlank() || "?".equals(statCode)) continue;

            for (EnchantPart part : parts) {
                String key = statCode + "|" + part.value();
                if (!dedupe.add(key)) continue;
                result.add(new StatEnchantCode(statCode, part.value(), part.theme()));
            }
        }
        return result;
    }

    private static String extractStatLabel(String raw) {
        Matcher matcher = STAT_LABEL_PATTERN.matcher(raw == null ? "" : raw);
        if (!matcher.find()) return "";
        return normalizeCodeLabel(matcher.group(1));
    }

    private static List<EnchantPart> extractEnchantParts(Text line) {
        List<EnchantPart> result = new ArrayList<>();
        final String[] fullLine = {line == null ? "" : line.getString()};
        debugEnchantLine(line);
        if (line != null) {
            line.visit((style, part) -> {
                collectEnchantPartsFromSegment(part, style, result);
                return Optional.empty();
            }, line.getStyle());
        }

        if (result.isEmpty()) {
            Matcher matcher = ENCHANT_BONUS_SUFFIX.matcher(fullLine[0] == null ? "" : fullLine[0]);
            while (matcher.find()) {
                result.add(new EnchantPart(matcher.group(1), null));
            }
        }
        return result;
    }

    private static void debugEnchantLine(Text line) {
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (config == null || !config.debugEnchantColorParsing || line == null) return;

        String raw = line.getString();
        if (raw == null || !ENCHANT_BONUS_SUFFIX.matcher(raw).find()) return;
        if (!DEBUGGED_ENCHANT_LINES.add(raw)) return;

        StringBuilder sb = new StringBuilder();
        sb.append("Enchant debug line raw='").append(raw).append("'");
        sb.append(" rootStyle=").append(describeStyle(line.getStyle()));
        sb.append(" siblings=").append(line.getSiblings().size());
        int[] index = {0};
        line.visit((style, part) -> {
            if (part != null && !part.isBlank()) {
                sb.append(" | seg[").append(index[0]++).append("]='").append(part).append("' style=").append(describeStyle(style));
            }
            return Optional.empty();
        }, line.getStyle());
        DrStandaloneMod.LOG.info(sb.toString());
    }

    private static String describeStyle(Style style) {
        if (style == null) return "null";
        Integer rgb = style.getColor() != null ? style.getColor().getRgb() & 0xFFFFFF : null;
        return "{rgb=" + (rgb == null ? "null" : String.format(Locale.ROOT, "#%06X", rgb))
            + ",bold=" + style.isBold()
            + ",italic=" + style.isItalic()
            + ",underlined=" + style.isUnderlined()
            + ",strikethrough=" + style.isStrikethrough()
            + ",obfuscated=" + style.isObfuscated()
            + "}";
    }

    private static void collectEnchantPartsFromSegment(String segment, Style style, List<EnchantPart> out) {
        if (segment == null || segment.isBlank()) return;
        Matcher suffixMatcher = ENCHANT_BONUS_SUFFIX.matcher(segment);
        while (suffixMatcher.find()) {
            out.add(new EnchantPart(suffixMatcher.group(1), DrRarityHelper.resolveThemeFromStyle(style)));
        }

        Matcher partMatcher = ENCHANT_BONUS_PART.matcher(segment.trim());
        if (partMatcher.matches()) {
            out.add(new EnchantPart(partMatcher.group(1), DrRarityHelper.resolveThemeFromStyle(style)));
        }
    }

    private record EnchantPart(String value, DrRarityHelper.TooltipTheme theme) {
    }

    private static String buildStatLetterCode(DrStatsDatabase.TooltipAnalysis analysis) {
        Set<String> codes = new LinkedHashSet<>();
        for (DrStatsDatabase.StatMatch match : analysis.matches) {
            String code = statLetterCode(match.label);
            if (!code.isBlank()) codes.add(code);
        }
        return String.join("", codes);
    }

    private static String statLetterCode(String label) {
        String key = normalizeCodeLabel(label);
        return switch (key) {
            case "STR" -> "S";
            case "DEX" -> "D";
            case "VIT" -> "V";
            case "INT" -> "I";
            case "HP" -> "H";
            case "ARMOR" -> "A";
            case "HP REGEN" -> "R";
            case "ENERGY REGEN" -> "E";
            case "DMG" -> "M";
            case "PURE DMG" -> "P";
            case "FIRE DMG" -> "F";
            case "ICE DMG" -> "C";
            case "POISON DMG" -> "O";
            case "VS. MONSTERS" -> "n";
            case "VS. PLAYERS" -> "p";
            case "ACCURACY" -> "Y";
            case "CRITICAL HIT" -> "T";
            case "EXECUTE" -> "X";
            case "BLEEDING" -> "B";
            case "PIERCING" -> "N";
            case "SHATTER" -> "Q";
            case "CRUSHING" -> "U";
            case "CLEAVE" -> "L";
            case "LIFE STEAL" -> "J";
            case "GLOWING" -> "G";
            case "BLINDING" -> "Z";
            case "SLOWNESS" -> "W";
            case "BLOCK" -> "K";
            case "DODGE" -> "o";
            case "REFLECT" -> "r";
            case "THORNS" -> "t";
            case "ABSORPTION" -> "u";
            case "ELEMENTAL RESIST" -> "e";
            case "FIRE RESIST" -> "f";
            case "ICE RESIST" -> "c";
            case "POISON RESIST" -> "z";
            case "PURE RESIST" -> "q";
            case "MOVE SPEED" -> "m";
            case "COOLDOWN RECOVERY" -> "w";
            case "HEALING" -> "h";
            case "ENERGY DRAIN" -> "j";
            case "HP RECOVERY" -> "y";
            case "ITEM FIND" -> "i";
            case "KEY FIND" -> "k";
            case "GEM FIND" -> "g";
            default -> "?";
        };
    }

    private static String normalizeCodeLabel(String label) {
        String normalized = label == null ? "" : label.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STRENGTH" -> "STR";
            case "DEXTERITY" -> "DEX";
            case "VITALITY" -> "VIT";
            case "INTELLECT", "INTELLIGENCE" -> "INT";
            case "HEALTH" -> "HP";
            case "HEALTH REGEN", "HEALTH/S", "HP/S", "HP REGEN/S" -> "HP REGEN";
            case "ENERGY/S", "ENERGY REGEN/S" -> "ENERGY REGEN";
            case "ELEMENTAL RESISTANCE" -> "ELEMENTAL RESIST";
            case "MOVEMENT SPEED" -> "MOVE SPEED";
            case "SLOW" -> "SLOWNESS";
            default -> normalized;
        };
    }

    private static String themeCode(DrRarityHelper.TooltipTheme theme) {
        if (theme == null) return "!";
        return switch (theme) {
            case Common -> "C";
            case Uncommon -> "U";
            case Rare -> "R";
            case Epic -> "E";
            case Legendary -> "L";
            case Mythic -> "M";
        };
    }

    private static Formatting upgradeFormatting(DrRarityHelper.TooltipTheme theme) {
        if (theme == null) return Formatting.GRAY;
        return switch (theme) {
            case Common -> Formatting.WHITE;
            case Uncommon -> Formatting.GREEN;
            case Rare -> Formatting.AQUA;
            case Epic -> Formatting.LIGHT_PURPLE;
            case Legendary -> Formatting.GOLD;
            case Mythic -> Formatting.RED;
        };
    }

    private static String sanitize(String value) {
        String sanitized = value
            .replace('\u00A0', ' ')
            .replaceAll("\\s+\\[(?:MIN|MAX|OVERCAP|ROLLS\\s+\\d+(?:\\.\\d+)?%|\\d+(?:\\.\\d+)?%|\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?)?)\\]\\s*$", "")
            .trim();
        String previous;
        do {
            previous = sanitized;
            sanitized = ENCHANT_BONUS_SUFFIX.matcher(sanitized).replaceFirst("").trim();
        } while (!sanitized.equals(previous));
        return sanitized;
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
