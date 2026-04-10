package net.matte.drstandalone.autoaugment;

import net.matte.drstandalone.DrRarityHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrEnchantSnapshotParser {
    private static final Pattern UPGRADE_PREFIX = Pattern.compile("^\\[\\+(\\d+)]\\s*");
    private static final Pattern ENCHANT_BONUS_SUFFIX = Pattern.compile("\\(([+-]?\\d+(?:\\.\\d+)?%?)\\)");
    private static final Pattern ENCHANT_BONUS_PART = Pattern.compile("^([+-]?\\d+(?:\\.\\d+)?%?)$");
    private static final Pattern STAT_LABEL_PATTERN = Pattern.compile("^\\s*([^:]+):\\s*(.*)$");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern RANGE_PATTERN = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)\\s*-\\s*([+-]?\\d+(?:\\.\\d+)?)");

    private DrEnchantSnapshotParser() {
    }

    public static @Nullable Snapshot inspect(MinecraftClient mc, ItemStack stack) {
        if (mc.player == null || stack == null || stack.isEmpty()) return null;

        List<Text> rawTooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, mc.player, TooltipType.BASIC);
        if (rawTooltip == null || rawTooltip.isEmpty()) return null;

        List<Text> cleanedTooltip = new ArrayList<>();
        for (Text line : rawTooltip) {
            String raw = sanitize(line.getString());
            if (raw.isBlank()) continue;
            if (shouldSkipLine(raw)) continue;
            cleanedTooltip.add(line);
        }

        DrRarityHelper.TooltipTheme rarity = DrRarityHelper.resolve(stack, cleanedTooltip);
        boolean transmuted = cleanedTooltip.stream()
            .map(Text::getString)
            .map(DrEnchantSnapshotParser::sanitize)
            .anyMatch(line -> line.toUpperCase(Locale.ROOT).contains("TRANSMUTED"));

        Map<String, ParsedStat> stats = parseStats(stack, cleanedTooltip);
        return new Snapshot(
            stack.getName().getString(),
            readUpgradeLevel(stack.getName().getString()),
            rarity,
            transmuted,
            Map.copyOf(stats)
        );
    }

    public static List<String> diff(@Nullable Snapshot before, @Nullable Snapshot after) {
        if (after == null) return List.of("No parsed item snapshot.");
        if (before == null) return List.of("Snapshot ready: " + shortSummary(after));

        List<String> changes = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        labels.addAll(before.statsByLabel().keySet());
        labels.addAll(after.statsByLabel().keySet());

        for (String label : labels) {
            ParsedStat oldStat = before.statsByLabel().get(label);
            ParsedStat newStat = after.statsByLabel().get(label);

            if (oldStat == null && newStat != null) {
                changes.add(label + " added " + format(newStat.total()));
                continue;
            }
            if (oldStat != null && newStat == null) {
                changes.add(label + " removed");
                continue;
            }
            if (oldStat == null) continue;

            double totalDelta = round1(newStat.total() - oldStat.total());
            double enchantDelta = round1(newStat.enchantBonus() - oldStat.enchantBonus());
            double passiveDelta = round1(newStat.passiveBonus() - oldStat.passiveBonus());

            if (Math.abs(totalDelta) < 0.0001 && Math.abs(enchantDelta) < 0.0001 && Math.abs(passiveDelta) < 0.0001
                && oldStat.enchantRarity() == newStat.enchantRarity()) continue;

            StringBuilder line = new StringBuilder(label);
            if (Math.abs(enchantDelta) >= 0.0001) line.append(" ench ").append(signed(enchantDelta));
            if (Math.abs(passiveDelta) >= 0.0001) line.append(" passive ").append(signed(passiveDelta));
            if (Math.abs(totalDelta) >= 0.0001) line.append(" total ").append(signed(totalDelta));
            if (oldStat.enchantRarity() != newStat.enchantRarity() && newStat.enchantRarity() != null) line.append(" ").append(newStat.enchantRarity().name());
            changes.add(line.toString().trim());
        }

        if (changes.isEmpty()) changes.add("No stat delta detected.");
        return changes;
    }

    public static String shortSummary(Snapshot snapshot) {
        return String.format(Locale.ROOT, "%s | +%d | stats %d",
            baseItemName(snapshot.rawItemName()),
            snapshot.upgradeLevel(),
            snapshot.statsByLabel().size()
        );
    }

    public static String baseItemName(String raw) {
        return raw == null ? "" : raw.replaceFirst("^\\[\\+\\d+]\\s*", "").trim();
    }

    private static Map<String, ParsedStat> parseStats(ItemStack stack, List<Text> tooltip) {
        ItemFamily family = ItemFamily.from(stack);
        Map<String, MutableParsedStat> stats = new LinkedHashMap<>();
        boolean weaponSectionStarted = false;

        for (Text line : tooltip) {
            String raw = sanitize(line.getString());
            if (raw.isBlank()) continue;

            boolean passive = false;
            String working = raw;
            if (working.toUpperCase(Locale.ROOT).startsWith("PASSIVE:")) {
                passive = true;
                working = working.substring(8).trim();
            }

            Matcher matcher = STAT_LABEL_PATTERN.matcher(working);
            if (!matcher.find()) continue;

            String rawLabel = matcher.group(1).trim();
            String canonical = canonicalLabel(rawLabel);
            if (canonical.isBlank()) continue;

            if (family.isWeaponFamily()) {
                if ("DMG".equals(canonical)) {
                    weaponSectionStarted = true;
                    continue;
                }
                if (!weaponSectionStarted) continue;
            }

            if (!shouldIncludeLabel(family, canonical)) continue;

            String valuePart = matcher.group(2).trim();
            double baseValue = parseVisibleValue(stripEnchantSuffixes(valuePart));
            EnchantInfo enchantInfo = parseEnchantInfo(line);

            MutableParsedStat stat = stats.computeIfAbsent(canonical, ignored -> new MutableParsedStat(rawLabel, canonical));
            if (passive) stat.passiveBonus += baseValue;
            else stat.baseVisibleValue += baseValue;
            stat.enchantBonus += enchantInfo.totalBonus();
            if (enchantInfo.theme() != null) stat.enchantRarity = enchantInfo.theme();
        }

        applyPassiveFallback(family, stats);

        Map<String, ParsedStat> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, MutableParsedStat> entry : stats.entrySet()) {
            MutableParsedStat stat = entry.getValue();
            frozen.put(entry.getKey(), new ParsedStat(
                stat.rawLabel,
                stat.canonicalLabel,
                round1(stat.baseVisibleValue),
                round1(stat.enchantBonus),
                stat.enchantRarity,
                round1(stat.passiveBonus),
                round1(stat.baseVisibleValue + stat.enchantBonus + stat.passiveBonus)
            ));
        }
        return frozen;
    }

    private static EnchantInfo parseEnchantInfo(Text line) {
        List<Double> values = new ArrayList<>();
        final DrRarityHelper.TooltipTheme[] detectedTheme = {null};

        line.visit((style, segment) -> {
            collectEnchantParts(segment, style, values, detectedTheme);
            return Optional.empty();
        }, line.getStyle());

        if (values.isEmpty()) {
            Matcher matcher = ENCHANT_BONUS_SUFFIX.matcher(line.getString());
            while (matcher.find()) values.add(parseNumber(matcher.group(1)));
        }

        double total = 0;
        for (double value : values) total += value;
        return new EnchantInfo(total, detectedTheme[0]);
    }

    private static void collectEnchantParts(String segment, Style style, List<Double> values, DrRarityHelper.TooltipTheme[] detectedTheme) {
        if (segment == null || segment.isBlank()) return;

        Matcher suffixMatcher = ENCHANT_BONUS_SUFFIX.matcher(segment);
        while (suffixMatcher.find()) {
            values.add(parseNumber(suffixMatcher.group(1)));
            if (detectedTheme[0] == null) detectedTheme[0] = resolveThemeFromStyle(style);
        }

        Matcher partMatcher = ENCHANT_BONUS_PART.matcher(segment.trim());
        if (partMatcher.matches()) {
            values.add(parseNumber(partMatcher.group(1)));
            if (detectedTheme[0] == null) detectedTheme[0] = resolveThemeFromStyle(style);
        }
    }

    private static @Nullable DrRarityHelper.TooltipTheme resolveThemeFromStyle(Style style) {
        Integer rgb = style.getColor() != null ? style.getColor().getRgb() & 0xFFFFFF : null;
        if (rgb == null) return null;
        if (matches(rgb, Formatting.RED, Formatting.DARK_RED)) return DrRarityHelper.TooltipTheme.Mythic;
        if (matches(rgb, Formatting.GOLD, Formatting.YELLOW)) return DrRarityHelper.TooltipTheme.Legendary;
        if (matches(rgb, Formatting.LIGHT_PURPLE, Formatting.DARK_PURPLE)) return DrRarityHelper.TooltipTheme.Epic;
        if (matches(rgb, Formatting.AQUA, Formatting.BLUE, Formatting.DARK_AQUA, Formatting.DARK_BLUE)) return DrRarityHelper.TooltipTheme.Rare;
        if (matches(rgb, Formatting.GREEN, Formatting.DARK_GREEN)) return DrRarityHelper.TooltipTheme.Uncommon;
        if (matches(rgb, Formatting.GRAY, Formatting.WHITE)) return DrRarityHelper.TooltipTheme.Common;
        return null;
    }

    private static boolean matches(int rgb, Formatting... formats) {
        for (Formatting formatting : formats) {
            Integer colorValue = formatting.getColorValue();
            if (colorValue != null && (colorValue & 0xFFFFFF) == rgb) return true;
        }
        return false;
    }

    private static double parseVisibleValue(String valuePart) {
        Matcher rangeMatcher = RANGE_PATTERN.matcher(valuePart);
        if (rangeMatcher.find()) {
            return (parseNumber(rangeMatcher.group(1)) + parseNumber(rangeMatcher.group(2))) / 2d;
        }
        Matcher numberMatcher = NUMBER_PATTERN.matcher(valuePart);
        if (numberMatcher.find()) return parseNumber(numberMatcher.group(1));
        return 0;
    }

    private static String stripEnchantSuffixes(String valuePart) {
        return ENCHANT_BONUS_SUFFIX.matcher(valuePart).replaceAll("").trim();
    }

    private static double parseNumber(String value) {
        if (value == null || value.isBlank()) return 0;
        String cleaned = value.replace("%", "").replace("/s", "").replace("+", "").trim();
        if (cleaned.isBlank()) return 0;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int readUpgradeLevel(String name) {
        Matcher matcher = UPGRADE_PREFIX.matcher(sanitize(name));
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean shouldSkipLine(String raw) {
        String upper = raw.toUpperCase(Locale.ROOT);
        return upper.startsWith("DURABILITY:")
            || raw.toLowerCase(Locale.ROOT).startsWith("minecraft:")
            || upper.matches("\\d+\\s+COMPONENT\\(S\\)")
            || upper.equals("COMMON")
            || upper.equals("UNCOMMON")
            || upper.equals("RARE")
            || upper.equals("EPIC")
            || upper.equals("LEGENDARY")
            || upper.equals("MYTHIC")
            || upper.contains("TRANSMUTED");
    }

    private static String canonicalLabel(String label) {
        String normalized = sanitize(label).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STRENGTH" -> "STR";
            case "DEXTERITY" -> "DEX";
            case "VITALITY" -> "VIT";
            case "INTELLECT", "INTELLIGENCE" -> "INT";
            case "HEALTH" -> "HP";
            case "HP RECOVERY" -> "HP REGEN";
            case "ENERGY/S", "ENERGY REGEN/S" -> "ENERGY REGEN";
            case "ELEMENTAL RESISTANCE" -> "ELEMENTAL RESIST";
            case "MOVEMENT SPEED" -> "MOVE SPEED";
            default -> normalized;
        };
    }

    private static boolean shouldIncludeLabel(ItemFamily family, String canonical) {
        return switch (family) {
            case Armor -> !Set.of("LEVEL", "HP", "ARMOR", "ENERGY REGEN").contains(canonical);
            case Shield -> !Set.of("LEVEL", "HP", "DMG REDUCTION", "HP REGEN").contains(canonical);
            case Weapon, Scythe, Sword, Axe, Mace, Spade, Bow -> !Set.of("LEVEL", "DMG").contains(canonical);
            case Unknown -> !Set.of("LEVEL").contains(canonical);
        };
    }

    private static void applyPassiveFallback(ItemFamily family, Map<String, MutableParsedStat> stats) {
        switch (family) {
            case Scythe -> addPassiveFallback(stats, "CLEAVE", 5);
            case Sword -> addPassiveFallback(stats, "EXECUTE", 5);
            case Axe -> addPassiveFallback(stats, "SHATTER", 5);
            case Mace, Spade -> addPassiveFallback(stats, "CRUSHING", 5);
            default -> {
            }
        }
    }

    private static void addPassiveFallback(Map<String, MutableParsedStat> stats, String label, double value) {
        MutableParsedStat existing = stats.get(label);
        if (existing != null && existing.passiveBonus > 0) return;

        MutableParsedStat stat = stats.computeIfAbsent(label, ignored -> new MutableParsedStat(label, label));
        stat.passiveBonus += value;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').trim();
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    public record Snapshot(
        String rawItemName,
        int upgradeLevel,
        @Nullable DrRarityHelper.TooltipTheme rarity,
        boolean transmuted,
        Map<String, ParsedStat> statsByLabel
    ) {
    }

    public record ParsedStat(
        String rawLabel,
        String canonicalLabel,
        double baseVisibleValue,
        double enchantBonus,
        @Nullable DrRarityHelper.TooltipTheme enchantRarity,
        double passiveBonus,
        double total
    ) {
    }

    private static final class MutableParsedStat {
        private final String rawLabel;
        private final String canonicalLabel;
        private double baseVisibleValue;
        private double enchantBonus;
        private @Nullable DrRarityHelper.TooltipTheme enchantRarity;
        private double passiveBonus;

        private MutableParsedStat(String rawLabel, String canonicalLabel) {
            this.rawLabel = rawLabel;
            this.canonicalLabel = canonicalLabel;
        }
    }

    private record EnchantInfo(double totalBonus, @Nullable DrRarityHelper.TooltipTheme theme) {
    }

    private enum ItemFamily {
        Weapon,
        Armor,
        Shield,
        Scythe,
        Sword,
        Axe,
        Mace,
        Spade,
        Bow,
        Unknown;

        private boolean isWeaponFamily() {
            return this == Weapon || this == Scythe || this == Sword || this == Axe || this == Mace || this == Bow;
        }

        private static ItemFamily from(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return Unknown;
            if (stack.getItem() == Items.SHIELD) return Shield;

            EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR) return Armor;

            String name = sanitize(stack.getName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("helmet") || name.contains("chestplate") || name.contains("platemail") || name.contains("leggings") || name.contains("boots")) return Armor;
            if (name.contains("scythe")) return Scythe;
            if (name.contains("bow")) return Bow;
            if (name.contains("mace")) return Mace;
            if (name.contains("spade") || name.contains("shovel")) return Spade;
            if (name.contains("axe")) return Axe;
            if (name.contains("sword")) return Sword;

            Item item = stack.getItem();
            if (item == Items.BOW || item == Items.CROSSBOW) return Bow;
            return Weapon;
        }
    }
}
