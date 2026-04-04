package net.matte.drstandalone;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DrStatsDatabase {
    private static final String CUSTOM_ITEMS_ROOT = "assets/dr-standalone/dr-stats/custom_items";
    private static final String CUSTOM_ITEMS_INDEX = CUSTOM_ITEMS_ROOT + "/index.txt";
    private static final Pattern BRACKET_PREFIX = Pattern.compile("^\\[[^\\]]+]\\s*");
    private static final Pattern TAG_ENTRY = Pattern.compile("(\\w+):(?:\"([^\"]*)\"|([^,}]+))");
    private static final Pattern ENCHANT_BONUS_SUFFIX = Pattern.compile("\\s*\\([+-]?\\d+(?:\\.\\d+)?\\)\\s*$");
    private static final Pattern UPGRADE_LEVEL_PREFIX = Pattern.compile("^\\[\\+(\\d+)]\\s*");
    private static final Pattern RANGE_TEXT_PART = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)\\s*-\\s*([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern NUMBER_CAPTURE_PATTERN = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)");

    private static DrStatsDatabase INSTANCE;

    private final Map<String, List<CustomItemDefinition>> definitionsByName = new HashMap<>();
    private final List<CustomItemDefinition> definitions = new ArrayList<>();
    private List<CodexEntry> codexEntriesCache = List.of();

    private boolean loaded;
    private boolean loadFailed;

    public static DrStatsDatabase get() {
        if (INSTANCE == null) INSTANCE = new DrStatsDatabase();
        return INSTANCE;
    }

    public synchronized TooltipAnalysis analyze(ItemStack stack, List<Text> tooltip) {
        return analyze(stack, tooltip, null);
    }

    public synchronized TooltipAnalysis analyze(ItemStack stack, List<Text> tooltip, @Nullable DrRarityHelper.TooltipTheme rarity) {
        ensureLoaded();
        if (loadFailed || tooltip == null || tooltip.isEmpty()) return null;

        List<String> tooltipLines = new ArrayList<>();
        for (Text line : tooltip) {
            String value = cleanLine(line.getString());
            if (!value.isBlank()) tooltipLines.add(value);
        }

        if (tooltipLines.isEmpty()) return null;

        String displayName = cleanDisplayName(stack.getName().getString());
        CandidateMatch best = null;

        for (CustomItemDefinition definition : getCandidates(displayName)) {
            CandidateMatch match = definition.match(displayName, tooltipLines);
            if (match == null) continue;

            if (best == null || match.score > best.score) best = match;
        }

        TooltipAnalysis genericWeapon = analyzeGenericWeapon(stack, tooltipLines, displayName, rarity);
        TooltipAnalysis genericArmor = analyzeGenericArmor(stack, tooltipLines, displayName, rarity);
        TooltipAnalysis generic = genericWeapon != null ? genericWeapon : genericArmor;

        if (best != null && !best.matches.isEmpty()) {
            TooltipAnalysis custom = new TooltipAnalysis(best.definition, best.matches);
            if (isDefinitionCompatibleWithStack(best.definition, stack, tooltipLines)) {
                return applyUpgradeAdjustment(stack, custom);
            }
            DrStandaloneMod.LOG.info(
                "DR stats DB: rejected custom match '{}' (type='{}' tier={}) for stack '{}' and falling back to generic analysis.",
                best.definition.name,
                best.definition.itemType,
                best.definition.tier,
                Registries.ITEM.getId(stack.getItem())
            );
        }

        return applyUpgradeAdjustment(stack, generic);
    }


    public synchronized List<CodexEntry> getCodexEntries() {
        ensureLoaded();
        if (loadFailed || definitions.isEmpty()) return List.of();
        if (!codexEntriesCache.isEmpty()) return codexEntriesCache;

        List<CodexEntry> entries = new ArrayList<>();
        for (CustomItemDefinition definition : definitions) {
            entries.add(new CodexEntry(
                definition.name,
                definition.slot,
                definition.itemType,
                definition.rarity,
                definition.tier,
                definition.level,
                definition.mythic,
                definition.statTemplates.size(),
                definition.sourceId
            ));
        }

        entries.sort(Comparator.comparing((CodexEntry entry) -> entry.name.toLowerCase(Locale.ROOT))
            .thenComparing(entry -> entry.slot.toLowerCase(Locale.ROOT)));
        codexEntriesCache = List.copyOf(entries);
        return codexEntriesCache;
    }

    private void ensureLoaded() {
        if (loaded || loadFailed) return;

        try {
            loadDefinitions();
            loaded = true;
        } catch (Exception e) {
            loadFailed = true;
            DrStandaloneMod.LOG.error("Failed to load DR stats database.", e);
        }
    }

    private void loadDefinitions() throws IOException {
        definitions.clear();
        definitionsByName.clear();
        codexEntriesCache = List.of();

        for (String fileName : readIndex()) {
            String resourcePath = CUSTOM_ITEMS_ROOT + "/" + fileName;
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (stream == null) continue;

                parseSetFile(fileName, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private List<String> readIndex() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(CUSTOM_ITEMS_INDEX)) {
            if (stream == null) return List.of();

            List<String> names = new ArrayList<>();
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String value = line.trim();
                if (!value.isEmpty()) names.add(value);
            }

            return names;
        }
    }

    private void parseSetFile(String fileName, String content) {
        String sourceId = fileName.endsWith(".set") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String currentSlot = null;
        String itemType = null;
        Map<String, String> tagData = null;
        List<StatTemplate> statTemplates = new ArrayList<>();

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                registerDefinition(sourceId, currentSlot, itemType, tagData, statTemplates);
                currentSlot = line.substring(1, line.length() - 1).trim();
                itemType = null;
                tagData = null;
                statTemplates = new ArrayList<>();
                continue;
            }

            if (line.startsWith("type=")) {
                itemType = line.substring("type=".length()).trim();
                continue;
            }

            if (line.startsWith("tag=")) {
                tagData = parseTag(line.substring("tag=".length()));
                continue;
            }

            if (line.contains(":")) {
                StatTemplate template = StatTemplate.parse(line);
                if (template != null) statTemplates.add(template);
            }
        }

        registerDefinition(sourceId, currentSlot, itemType, tagData, statTemplates);
    }

    private void registerDefinition(String sourceId, String slot, String itemType, Map<String, String> tagData, List<StatTemplate> statTemplates) {
        if (slot == null || tagData == null || statTemplates.isEmpty()) return;

        String customName = tagData.getOrDefault("customName", "");
        if (customName.isBlank()) return;

        CustomItemDefinition definition = new CustomItemDefinition(
            sourceId,
            customName,
            normalizeName(customName),
            slot,
            itemType,
            tagData.getOrDefault("rarity", ""),
            parseInt(tagData.get("tier")),
            parseInt(tagData.get("level")),
            parseInt(tagData.get("mythic")) > 0,
            List.copyOf(statTemplates)
        );

        definitions.add(definition);
        definitionsByName.computeIfAbsent(definition.normalizedName, ignored -> new ArrayList<>()).add(definition);
    }

    private Map<String, String> parseTag(String tag) {
        Map<String, String> data = new HashMap<>();
        Matcher matcher = TAG_ENTRY.matcher(tag);

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            data.put(key, value != null ? value.trim() : "");
        }

        return data;
    }

    private List<CustomItemDefinition> getCandidates(String displayName) {
        String normalized = normalizeName(displayName);
        List<CustomItemDefinition> exact = definitionsByName.get(normalized);
        if (exact != null && !exact.isEmpty()) return exact;

        List<CustomItemDefinition> fuzzy = new ArrayList<>();
        for (CustomItemDefinition definition : definitions) {
            if (isSafeFuzzyNameMatch(normalized, definition.normalizedName)) {
                fuzzy.add(definition);
            }
        }

        return fuzzy;
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) return 0;

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String cleanDisplayName(String value) {
        return cleanLine(BRACKET_PREFIX.matcher(value).replaceFirst(""));
    }

    private static String cleanLine(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\u00A0', ' ').trim();
        String previous;
        do {
            previous = cleaned;
            cleaned = ENCHANT_BONUS_SUFFIX.matcher(cleaned).replaceFirst("").trim();
        } while (!cleaned.equals(previous));
        return cleaned;
    }

    private static String normalizeName(String value) {
        String cleaned = cleanDisplayName(value).toLowerCase(Locale.ROOT);
        cleaned = cleaned.replaceAll("[^a-z0-9]+", " ").trim();
        return cleaned.replaceAll("\\s+", " ");
    }

    private static boolean isSafeFuzzyNameMatch(String normalizedDisplay, String normalizedDefinition) {
        if (normalizedDisplay == null || normalizedDefinition == null) return false;
        if (normalizedDisplay.isBlank() || normalizedDefinition.isBlank()) return false;
        if (normalizedDisplay.equals(normalizedDefinition)) return true;
        if (!(normalizedDisplay.contains(normalizedDefinition) || normalizedDefinition.contains(normalizedDisplay))) return false;

        String[] displayTokens = normalizedDisplay.split(" ");
        String[] definitionTokens = normalizedDefinition.split(" ");

        // Avoid matching generic vanilla-like names such as "platemail boots" to long custom set names.
        if (Math.min(displayTokens.length, definitionTokens.length) < 3) return false;

        // Allow mild fuzzy differences, but reject names that add several custom qualifiers.
        if (Math.abs(displayTokens.length - definitionTokens.length) > 1) return false;

        return true;
    }

    private TooltipAnalysis applyUpgradeAdjustment(ItemStack stack, @Nullable TooltipAnalysis analysis) {
        if (analysis == null) return null;

        int upgradeLevel = parseUpgradeLevel(stack.getName().getString());
        if (upgradeLevel <= 0) return analysis;

        double multiplier = 1d + (upgradeLevel * 0.05d);
        Set<String> upgradeableLabels = upgradeableLabels(stack);
        if (upgradeableLabels.isEmpty()) return analysis;

        List<StatMatch> adjustedMatches = new ArrayList<>(analysis.matches.size());
        boolean changed = false;
        for (StatMatch match : analysis.matches) {
            if (!upgradeableLabels.contains(normalizeAlias(match.label))) {
                adjustedMatches.add(match);
                continue;
            }

            StatMatch adjusted = adjustMatchForUpgrade(match, multiplier);
            adjustedMatches.add(adjusted);
            changed |= adjusted != match;
        }

        return changed ? new TooltipAnalysis(analysis.definition, adjustedMatches) : analysis;
    }

    private int parseUpgradeLevel(String displayName) {
        Matcher matcher = UPGRADE_LEVEL_PREFIX.matcher(displayName == null ? "" : displayName.trim());
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Set<String> upgradeableLabels(ItemStack stack) {
        WeaponKind weaponKind = WeaponKind.from(stack);
        if (weaponKind != null) {
            return Set.of("DMG");
        }

        ArmorKind armorKind = ArmorKind.from(stack);
        if (armorKind == ArmorKind.Shield) {
            return Set.of("HP", "HP REGEN");
        }
        if (armorKind != null) {
            return Set.of("HP", "ENERGY REGEN");
        }

        return Set.of();
    }

    private StatMatch adjustMatchForUpgrade(StatMatch match, double multiplier) {
        List<ValueRange> baseRanges = parseRangeText(match.rangeText);
        List<ParsedValue> actualValues = parseActualValues(match.valueText, baseRanges.size());
        if (baseRanges.size() != actualValues.size() || baseRanges.isEmpty()) {
            return match;
        }

        double percentTotal = 0d;
        for (int i = 0; i < baseRanges.size(); i++) {
            ValueRange baseRange = baseRanges.get(i);
            ParsedValue actual = actualValues.get(i);
            double baseValue = inferBaseValue(actual.value(), actual.decimals(), baseRange, multiplier);
            percentTotal += baseRange.percent(baseValue);
        }

        List<ValueRange> adjustedRanges = new ArrayList<>(baseRanges.size());
        for (int i = 0; i < baseRanges.size(); i++) {
            adjustedRanges.add(scaleRangeForUpgrade(baseRanges.get(i), actualValues.get(i).decimals(), multiplier));
        }

        return new StatMatch(
            match.label,
            match.valueText,
            percentTotal / baseRanges.size(),
            buildRangeText(adjustedRanges),
            false
        );
    }

    private List<ValueRange> parseRangeText(String rangeText) {
        if (rangeText == null || rangeText.isBlank()) return List.of();

        List<ValueRange> ranges = new ArrayList<>();
        for (String part : rangeText.split("/")) {
            Matcher matcher = RANGE_TEXT_PART.matcher(part.trim());
            if (!matcher.find()) return List.of();
            ranges.add(new ValueRange(parseDouble(matcher.group(1)), parseDouble(matcher.group(2))));
        }
        return ranges;
    }

    private List<ParsedValue> parseActualValues(String valueText, int expectedCount) {
        if (valueText == null || valueText.isBlank() || expectedCount <= 0) return List.of();

        int colon = valueText.indexOf(':');
        String statPart = colon >= 0 ? valueText.substring(colon + 1) : valueText;
        String cleaned = cleanLine(statPart);

        List<ParsedValue> values = new ArrayList<>(expectedCount);
        Matcher matcher = NUMBER_CAPTURE_PATTERN.matcher(cleaned);
        while (matcher.find() && values.size() < expectedCount) {
            String raw = matcher.group(1);
            values.add(new ParsedValue(parseDouble(raw), decimalPlaces(raw)));
        }
        return values;
    }

    private double inferBaseValue(double actualValue, int decimals, ValueRange baseRange, double multiplier) {
        double step = decimals <= 0 ? 1d : Math.pow(10d, -decimals);
        double lowerBound = Math.max(baseRange.min, ((actualValue - (step / 2d)) / multiplier) - 0.0000001d);
        double upperBound = Math.min(baseRange.max, ((actualValue + (step / 2d)) / multiplier) + 0.0000001d);

        double start = decimals <= 0 ? Math.ceil(lowerBound) : Math.ceil(lowerBound / step) * step;
        double best = actualValue / multiplier;
        double target = best;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (double candidate = start; candidate <= upperBound + (step / 2d); candidate += step) {
            double rounded = roundToDecimals(candidate * multiplier, decimals);
            if (Math.abs(rounded - actualValue) > (step / 2d) + 0.0000001d) continue;

            double distance = Math.abs(candidate - target);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }

        return clamp(best, baseRange.min, baseRange.max);
    }

    private ValueRange scaleRangeForUpgrade(ValueRange baseRange, int decimals, double multiplier) {
        return new ValueRange(
            roundToDecimals(baseRange.min * multiplier, decimals),
            roundToDecimals(baseRange.max * multiplier, decimals)
        );
    }

    private String buildRangeText(List<ValueRange> ranges) {
        if (ranges.isEmpty()) return "";
        if (ranges.size() == 1) return formatRange(ranges.get(0));

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) builder.append(" / ");
            builder.append(formatRange(ranges.get(i)));
        }
        return builder.toString();
    }

    private String formatRange(ValueRange range) {
        return formatNumber(range.min) + "-" + formatNumber(range.max);
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001d) {
            return Integer.toString((int) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String normalizeAlias(String value) {
        return cleanLine(value).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String canonicalStatLabel(String value) {
        String normalized = normalizeAlias(value);
        return switch (normalized) {
            case "STRENGTH" -> "STR";
            case "DEXTERITY" -> "DEX";
            case "VITALITY" -> "VIT";
            case "INTELLECT", "INTELLIGENCE" -> "INT";
            case "HEALTH" -> "HP";
            case "HEALTH REGEN", "HEALTH/S", "HP/S", "HP REGEN/S" -> "HP REGEN";
            case "ENERGY/S", "ENERGY REGEN/S" -> "ENERGY REGEN";
            case "DMG REDUCTION" -> "ARMOR";
            case "ELEMENTAL RESISTANCE" -> "ELEMENTAL RESIST";
            case "FIRE RESISTANCE" -> "FIRE RESIST";
            case "ICE RESISTANCE" -> "ICE RESIST";
            case "POISON RESISTANCE" -> "POISON RESIST";
            case "PURE RESISTANCE" -> "PURE RESIST";
            case "MOVEMENT SPEED" -> "MOVE SPEED";
            case "SLOW" -> "SLOWNESS";
            default -> normalized;
        };
    }

    private static List<String> statAliasesForLabel(String label) {
        String canonical = canonicalStatLabel(label);
        return switch (canonical) {
            case "STR" -> List.of("STR", "STRENGTH");
            case "DEX" -> List.of("DEX", "DEXTERITY");
            case "VIT" -> List.of("VIT", "VITALITY");
            case "INT" -> List.of("INT", "INTELLECT", "INTELLIGENCE");
            case "HP" -> List.of("HP", "HEALTH");
            case "HP REGEN" -> List.of("HP REGEN", "HP/S", "HP REGEN/S", "HEALTH REGEN", "HEALTH/S");
            case "HP RECOVERY" -> List.of("HP RECOVERY");
            case "ENERGY REGEN" -> List.of("ENERGY REGEN", "ENERGY/S", "ENERGY REGEN/S");
            case "ARMOR" -> List.of("ARMOR", "DMG REDUCTION");
            case "ELEMENTAL RESIST" -> List.of("ELEMENTAL RESIST", "ELEMENTAL RESISTANCE");
            case "FIRE RESIST" -> List.of("FIRE RESIST", "FIRE RESISTANCE");
            case "ICE RESIST" -> List.of("ICE RESIST", "ICE RESISTANCE");
            case "POISON RESIST" -> List.of("POISON RESIST", "POISON RESISTANCE");
            case "PURE RESIST" -> List.of("PURE RESIST", "PURE RESISTANCE");
            case "MOVE SPEED" -> List.of("MOVE SPEED", "MOVEMENT SPEED");
            case "SLOWNESS" -> List.of("SLOWNESS", "SLOW");
            default -> List.of(canonical);
        };
    }

    private double parseDouble(String value) {
        return Double.parseDouble(value.replace(',', '.'));
    }

    private int decimalPlaces(String rawNumber) {
        int dot = rawNumber.indexOf('.');
        return dot < 0 ? 0 : rawNumber.length() - dot - 1;
    }

    private double roundToDecimals(double value, int decimals) {
        if (decimals <= 0) return Math.round(value);
        double factor = Math.pow(10d, decimals);
        return Math.round(value * factor) / factor;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ParsedValue(double value, int decimals) {
    }

    private boolean isDefinitionCompatibleWithStack(CustomItemDefinition definition, ItemStack stack, List<String> tooltipLines) {
        String expectedType = inferExpectedItemType(stack);
        if (!expectedType.isBlank() && !definition.itemType.isBlank()) {
            if (!isCompatibleItemType(definition.itemType, expectedType, stack)) {
                return false;
            }
        }

        int expectedTier = inferExpectedTier(stack, tooltipLines);
        if (expectedTier > 0 && definition.tier > 0 && expectedTier != definition.tier) {
            return false;
        }

        return true;
    }

    private boolean isCompatibleItemType(String definitionItemType, String expectedType, ItemStack stack) {
        String normalizedDefinition = normalizeName(definitionItemType);
        String normalizedExpected = normalizeName(expectedType);
        if (normalizedDefinition.equals(normalizedExpected)) {
            return true;
        }

        String stackPath = Registries.ITEM.getId(stack.getItem()).getPath();
        if (normalizedDefinition.equals(normalizeName(stackPath))) {
            return true;
        }

        String inferredFromDefinition = inferExpectedItemTypeFromDefinition(definitionItemType);
        return !inferredFromDefinition.isBlank() && normalizeName(inferredFromDefinition).equals(normalizedExpected);
    }

    private String inferExpectedItemTypeFromDefinition(String definitionItemType) {
        if (definitionItemType == null || definitionItemType.isBlank()) {
            return "";
        }

        String normalized = definitionItemType.trim().toUpperCase(Locale.ROOT);
        if (normalized.endsWith("_HELMET")) return ArmorKind.Helmet.label;
        if (normalized.endsWith("_CHESTPLATE")) return ArmorKind.Chestplate.label;
        if (normalized.endsWith("_LEGGINGS")) return ArmorKind.Leggings.label;
        if (normalized.endsWith("_BOOTS")) return ArmorKind.Boots.label;
        if (normalized.equals("SHIELD")) return ArmorKind.Shield.label;
        if (normalized.endsWith("_SWORD")) return WeaponKind.Sword.label;
        if (normalized.endsWith("_HOE")) return WeaponKind.Scythe.label;
        if (normalized.endsWith("_AXE")) return WeaponKind.Axe.label;
        if (normalized.endsWith("_SHOVEL") || normalized.endsWith("_SPADE")) return WeaponKind.Mace.label;
        if (normalized.equals("BOW") || normalized.equals("CROSSBOW")) return WeaponKind.Bow.label;
        return "";
    }

    private String inferExpectedItemType(ItemStack stack) {
        WeaponKind weaponKind = WeaponKind.from(stack);
        if (weaponKind != null) return weaponKind.label;

        ArmorKind armorKind = ArmorKind.from(stack);
        if (armorKind != null) return armorKind.label;

        return "";
    }

    private int inferExpectedTier(ItemStack stack, List<String> tooltipLines) {
        int level = parseLevel(tooltipLines);
        WeaponKind weaponKind = WeaponKind.from(stack);
        if (weaponKind != null) return resolveTier(stack, level);

        ArmorKind armorKind = ArmorKind.from(stack);
        if (armorKind != null) return resolveArmorTier(stack, level);

        return 0;
    }

    private @Nullable TooltipAnalysis analyzeGenericWeapon(ItemStack stack, List<String> tooltipLines, String displayName, @Nullable DrRarityHelper.TooltipTheme rarityTheme) {
        if (rarityTheme == null || rarityTheme == DrRarityHelper.TooltipTheme.Mythic) return null;

        WeaponKind weaponKind = WeaponKind.from(stack);
        if (weaponKind == null) return null;

        int level = parseLevel(tooltipLines);
        if (level <= 0) return null;

        int tier = resolveTier(stack, level);
        if (tier <= 0) return null;

        List<StatMatch> matches = buildGenericTemplates(tier, level, weaponKind, rarityTheme)
            .stream()
            .map(template -> template.matchAny(tooltipLines))
            .filter(Objects::nonNull)
            .toList();

        if (matches.isEmpty()) return null;

        CustomItemDefinition definition = new CustomItemDefinition(
            "generic:" + rarityTheme.name().toLowerCase(Locale.ROOT) + ":" + weaponKind.name().toLowerCase(Locale.ROOT),
            displayName,
            normalizeName(displayName),
            "weapon",
            weaponKind.label,
            formatRarity(rarityTheme),
            tier,
            level,
            false,
            List.of()
        );

        return new TooltipAnalysis(definition, matches);
    }

    private @Nullable TooltipAnalysis analyzeGenericArmor(ItemStack stack, List<String> tooltipLines, String displayName, @Nullable DrRarityHelper.TooltipTheme rarityTheme) {
        if (rarityTheme == null || rarityTheme == DrRarityHelper.TooltipTheme.Mythic) return null;

        ArmorKind armorKind = ArmorKind.from(stack);
        if (armorKind == null) return null;

        int level = parseLevel(tooltipLines);
        if (level <= 0) return null;

        int tier = resolveArmorTier(stack, level);
        if (tier <= 0) return null;

        List<StatMatch> matches = buildGenericArmorTemplates(tier, level, rarityTheme, armorKind)
            .stream()
            .map(template -> template.matchAny(tooltipLines))
            .filter(Objects::nonNull)
            .toList();

        if (matches.isEmpty()) return null;

        CustomItemDefinition definition = new CustomItemDefinition(
            "generic:" + rarityTheme.name().toLowerCase(Locale.ROOT) + ":" + armorKind.name().toLowerCase(Locale.ROOT),
            displayName,
            normalizeName(displayName),
            "armor",
            armorKind.label,
            formatRarity(rarityTheme),
            tier,
            level,
            false,
            List.of()
        );

        return new TooltipAnalysis(definition, matches);
    }

    private List<GenericStatTemplate> buildGenericTemplates(int tier, int level, WeaponKind weaponKind, DrRarityHelper.TooltipTheme rarityTheme) {
        List<GenericStatTemplate> templates = new ArrayList<>();

        DamageProfile damageProfile = DAMAGE_PROFILES[tier - 1][rarityIndex(rarityTheme)];
        if (damageProfile != null) {
            templates.add(GenericStatTemplate.doubleRange(
                "DMG",
                "DMG",
                scaleRange(damageProfile.lowerMin * weaponKind.damageMultiplier, damageProfile.lowerMax * weaponKind.damageMultiplier, level, tier),
                scaleRange(damageProfile.upperMin * weaponKind.damageMultiplier, damageProfile.upperMax * weaponKind.damageMultiplier, level, tier)
            ));
        }

        templates.add(GenericStatTemplate.singleRange("VS. MONSTERS", "VS. MONSTERS", flatRange(VS_MONSTERS_RANGES[tier - 1])));
        templates.add(GenericStatTemplate.singleRange("VS. PLAYERS", "VS. PLAYERS", flatRange(VS_PLAYERS_RANGES[tier - 1])));
        templates.add(GenericStatTemplate.singleRange("ACCURACY", "ACCURACY", flatRange(ACCURACY_RANGES[tier - 1])));
        templates.add(GenericStatTemplate.singleRange("CRITICAL HIT", "CRITICAL HIT", flatRange(CRITICAL_HIT_RANGES[tier - 1])));
        templates.add(GenericStatTemplate.singleRange("EXECUTE", "EXECUTE", flatRange(EXECUTE_RANGES[tier - 1])));
        templates.add(GenericStatTemplate.singleRange("BLEEDING", "BLEEDING", flatRange(BLEEDING_RANGES[tier - 1])));
        templates.add(GenericStatTemplate.singleRange("CLEAVE", "CLEAVE", flatRange(CLEAVE_RANGES[tier - 1])));
        templates.add(GenericStatTemplate.singleRange("CRUSHING", "CRUSHING", flatRange(CRUSHING_RANGES[tier - 1])));
        templates.add(GenericStatTemplate.singleRange("PIERCING", "PIERCING", flatRange(PIERCING_RANGES[tier - 1])));
        templates.add(GenericStatTemplate.singleRange("SHATTER", "SHATTER", flatRange(SHATTER_RANGES[tier - 1])));

        ValueRange elementalRange = flatRange(ELEMENTAL_RANGES[tier - 1]).scaled(weaponKind.damageMultiplier);
        templates.add(GenericStatTemplate.singleRange("PURE DMG", "PURE DMG", elementalRange));
        templates.add(GenericStatTemplate.singleRange("ICE DMG", "ICE DMG", elementalRange));
        templates.add(GenericStatTemplate.singleRange("FIRE DMG", "FIRE DMG", elementalRange));
        templates.add(GenericStatTemplate.singleRange("POISON DMG", "POISON DMG", elementalRange));
        templates.add(GenericStatTemplate.singleRange("LIFE STEAL", "LIFE STEAL", flatRange(LIFESTEAL_RANGES[tier - 1])));
        if (weaponKind == WeaponKind.Bow) {
            templates.add(GenericStatTemplate.singleRange("GLOWING", "GLOWING", flatRange(GLOWING_RANGES[tier - 1])));
            templates.add(GenericStatTemplate.singleRange("BLINDING", "BLINDING", flatRange(BLINDING_RANGES[tier - 1])));
            templates.add(GenericStatTemplate.singleRange("SLOWNESS", "SLOWNESS", flatRange(SLOWNESS_RANGES[tier - 1])));
        }

        return templates;
    }

    private List<GenericStatTemplate> buildGenericArmorTemplates(int tier, int level, DrRarityHelper.TooltipTheme rarityTheme, ArmorKind armorKind) {
        List<GenericStatTemplate> templates = new ArrayList<>();
        int rarityIndex = rarityIndex(rarityTheme);

        ValueRange healthRange = scaleRange(
            ARMOR_HEALTH_RANGES[tier - 1][rarityIndex][0],
            ARMOR_HEALTH_RANGES[tier - 1][rarityIndex][1],
            level,
            tier
        );
        ValueRange hpRegenRange = scaleRange(
            Math.floor(ARMOR_HEALTH_RANGES[tier - 1][rarityIndex][0] / 2d),
            Math.ceil(ARMOR_HEALTH_RANGES[tier - 1][rarityIndex][1] / 2d),
            level,
            tier
        );
        ValueRange energyRange = ARMOR_ENERGY_RANGES[tier - 1][rarityIndex];

        ValueRange[] armorRanges = ARMOR_RANGES[tier - 1][rarityIndex];
        ValueRange[] damageReductionRanges = DAMAGE_REDUCTION_RANGES[tier - 1][rarityIndex];

        templates.add(GenericStatTemplate.doubleRangeWithAliases("ARMOR", armorRanges[0], armorRanges[1], "ARMOR"));
        templates.add(GenericStatTemplate.doubleRangeWithAliases("DMG REDUCTION", damageReductionRanges[0], damageReductionRanges[1], "DMG REDUCTION"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("HP", healthRange, "HP", "HEALTH"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("HP REGEN", hpRegenRange, "HP REGEN", "HP/S", "HP REGEN/S", "HP RECOVERY"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("ENERGY REGEN", energyRange, "ENERGY REGEN", "ENERGY/S", "ENERGY REGEN/S"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("STR", flatRange(STAT_ATTRIBUTE_RANGES[tier - 1]), "STR", "STRENGTH"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("DEX", flatRange(STAT_ATTRIBUTE_RANGES[tier - 1]), "DEX", "DEXTERITY"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("VIT", flatRange(STAT_ATTRIBUTE_RANGES[tier - 1]), "VIT", "VITALITY"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("INT", flatRange(STAT_ATTRIBUTE_RANGES[tier - 1]), "INT", "INTELLECT", "INTELLIGENCE"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("THORNS", flatRange(THORNS_RANGES[tier - 1]), "THORNS"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("REFLECT", flatRange(REFLECT_RANGES[tier - 1]), "REFLECT"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("BLOCK", flatRange(BLOCK_RANGES[tier - 1]), "BLOCK"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("DODGE", flatRange(DODGE_RANGES[tier - 1]), "DODGE"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("GEM FIND", flatRange(GEM_FIND_RANGES[tier - 1]), "GEM FIND"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("ITEM FIND", flatRange(ITEM_FIND_RANGES[tier - 1]), "ITEM FIND"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("KEY FIND", flatRange(KEY_FIND_RANGES[tier - 1]), "KEY FIND"));
        templates.add(GenericStatTemplate.singleRangeWithAliases(
            "ELEMENTAL RESIST",
            flatRange(ELEMENTAL_RESIST_RANGES[tier - 1]),
            "FIRE RESIST", "FIRE RESISTANCE",
            "ICE RESIST", "ICE RESISTANCE",
            "POISON RESIST", "POISON RESISTANCE",
            "PURE RESIST", "PURE RESISTANCE",
            "ELEMENTAL RESIST", "ELEMENTAL RESISTANCE"
        ));

        if (armorKind == ArmorKind.Shield) {
            templates.add(GenericStatTemplate.singleRangeWithAliases("ABSORPTION", flatRange(ABSORPTION_RANGES[tier - 1]), "ABSORPTION"));
            templates.add(GenericStatTemplate.singleRangeWithAliases("HP RECOVERY", flatRange(HP_RECOVERY_RANGES[tier - 1]), "HP RECOVERY"));
        }
        if (armorKind == ArmorKind.Helmet) {
            templates.add(GenericStatTemplate.singleRangeWithAliases("COOLDOWN RECOVERY", scaleDiscreteRange(COOLDOWN_RECOVERY_RANGES[tier - 1][0], COOLDOWN_RECOVERY_RANGES[tier - 1][1], level, tier), "COOLDOWN RECOVERY"));
        }
        if (armorKind == ArmorKind.Chestplate) {
            templates.add(GenericStatTemplate.singleRangeWithAliases("HEALING", flatRange(HEALING_RANGES[tier - 1]), "HEALING"));
        }
        if (armorKind == ArmorKind.Leggings) {
            templates.add(GenericStatTemplate.singleRangeWithAliases("ENERGY DRAIN", flatRange(ENERGY_DRAIN_RANGES[tier - 1]), "ENERGY DRAIN"));
        }
        if (armorKind == ArmorKind.Boots) {
            templates.add(GenericStatTemplate.singleRangeWithAliases("MOVE SPEED", flatRange(MOVE_SPEED_RANGES[tier - 1]), "MOVE SPEED", "MOVEMENT SPEED"));
        }

        return templates;
    }

    private int parseLevel(List<String> tooltipLines) {
        for (String line : tooltipLines) {
            Matcher matcher = LEVEL_LINE.matcher(line);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }

        return 0;
    }

    private int resolveTier(ItemStack stack, int level) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.startsWith("wooden_")) return 1;
        if (path.startsWith("stone_")) return 2;
        if (path.startsWith("iron_")) return 3;
        if (path.startsWith("diamond_")) return 4;
        if (path.startsWith("golden_")) return 5;
        if (path.equals("bow") || path.equals("crossbow")) {
            if (level < 20) return 1;
            if (level < 40) return 2;
            if (level < 60) return 3;
            if (level < 80) return 4;
            return 5;
        }

        return 0;
    }

    private int resolveArmorTier(ItemStack stack, int level) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.startsWith("leather_")) return 1;
        if (path.startsWith("chainmail_")) return 2;
        if (path.startsWith("iron_")) return 3;
        if (path.startsWith("diamond_")) return 4;
        if (path.startsWith("golden_")) return 5;
        if (path.equals("shield")) {
            if (level < 20) return 1;
            if (level < 40) return 2;
            if (level < 60) return 3;
            if (level < 80) return 4;
            return 5;
        }

        return 0;
    }

    private static String formatRarity(DrRarityHelper.TooltipTheme rarity) {
        return switch (rarity) {
            case Common -> "Common";
            case Uncommon -> "Uncommon";
            case Rare -> "Rare";
            case Epic -> "Epic";
            case Legendary -> "Legendary";
            case Mythic -> "Mythic";
        };
    }

    private static int rarityIndex(DrRarityHelper.TooltipTheme rarity) {
        return switch (rarity) {
            case Common -> 0;
            case Uncommon -> 1;
            case Rare -> 2;
            case Epic -> 3;
            case Legendary -> 4;
            case Mythic -> -1;
        };
    }

    private static ValueRange flatRange(int[] pair) {
        return new ValueRange(pair[0], pair[1]);
    }

    private static ValueRange scaleRange(double min, double max, int level, int tier) {
        int median = TIER_MEDIAN_LEVELS[Math.max(0, Math.min(TIER_MEDIAN_LEVELS.length - 1, tier - 1))];
        double scale = 1 + ((double) (level - median) / 100d);
        double scaledMin = min * scale;
        double scaledMax = max * scale;

        if (level > 100) {
            scaledMin *= 1 + ((level - 100) * 0.05d);
        }

        return new ValueRange(Math.floor(scaledMin), Math.ceil(scaledMax));
    }

    private static ValueRange scaleDiscreteRange(double min, double max, int level, int tier) {
        int median = TIER_MEDIAN_LEVELS[Math.max(0, Math.min(TIER_MEDIAN_LEVELS.length - 1, tier - 1))];
        double scale = 1 + ((double) (level - median) / 100d);
        double scaledMin = min * scale;
        double scaledMax = max * scale;

        if (level > 100) {
            scaledMin *= 1 + ((level - 100) * 0.05d);
        }

        return new ValueRange(Math.round(scaledMin), Math.round(scaledMax));
    }

    private static final Pattern LEVEL_LINE = Pattern.compile(".*LEVEL\\s*:\\s*(\\d+).*", Pattern.CASE_INSENSITIVE);
    private static final int[] TIER_MEDIAN_LEVELS = {10, 30, 50, 70, 90};
    private static final DamageProfile[][] DAMAGE_PROFILES = {
        {
            new DamageProfile(7, 8, 8, 9),
            new DamageProfile(9, 10, 10, 11),
            new DamageProfile(11, 12, 12, 13),
            new DamageProfile(13, 14, 14, 15),
            new DamageProfile(15, 16, 16, 17)
        },
        {
            new DamageProfile(21, 23, 23, 25),
            new DamageProfile(25, 27, 27, 29),
            new DamageProfile(29, 31, 31, 33),
            new DamageProfile(33, 34, 34, 36),
            new DamageProfile(36, 38, 38, 40)
        },
        {
            new DamageProfile(50, 54, 54, 58),
            new DamageProfile(58, 62, 62, 66),
            new DamageProfile(66, 71, 71, 75),
            new DamageProfile(75, 79, 79, 83),
            new DamageProfile(83, 87, 87, 91)
        },
        {
            new DamageProfile(109, 118, 118, 127),
            new DamageProfile(127, 135, 135, 143),
            new DamageProfile(143, 152, 152, 161),
            new DamageProfile(161, 169, 169, 177),
            new DamageProfile(177, 186, 186, 195)
        },
        {
            new DamageProfile(227, 242, 242, 259),
            new DamageProfile(259, 274, 274, 289),
            new DamageProfile(289, 305, 305, 321),
            new DamageProfile(321, 337, 337, 353),
            new DamageProfile(353, 368, 368, 383)
        }
    };
    private static final int[][] VS_MONSTERS_RANGES = {{5, 8}, {6, 9}, {7, 10}, {8, 11}, {9, 12}};
    private static final int[][] VS_PLAYERS_RANGES = {{5, 8}, {6, 9}, {7, 10}, {8, 11}, {9, 12}};
    private static final int[][] ACCURACY_RANGES = {{5, 8}, {6, 9}, {7, 10}, {8, 11}, {9, 12}};
    private static final int[][] CRITICAL_HIT_RANGES = {{2, 3}, {3, 4}, {4, 6}, {5, 8}, {6, 10}};
    private static final int[][] EXECUTE_RANGES = {{2, 3}, {3, 4}, {4, 6}, {5, 8}, {6, 10}};
    private static final int[][] BLEEDING_RANGES = {{3, 4}, {4, 6}, {5, 8}, {6, 10}, {8, 12}};
    private static final int[][] CLEAVE_RANGES = {{2, 3}, {3, 4}, {4, 6}, {5, 8}, {6, 10}};
    private static final int[][] CRUSHING_RANGES = {{2, 3}, {3, 4}, {4, 6}, {5, 8}, {6, 10}};
    private static final int[][] PIERCING_RANGES = {{3, 4}, {4, 6}, {5, 8}, {6, 10}, {8, 12}};
    private static final int[][] SHATTER_RANGES = {{2, 3}, {3, 4}, {4, 6}, {5, 8}, {6, 10}};
    private static final int[][] ELEMENTAL_RANGES = {{3, 4}, {6, 8}, {12, 16}, {24, 32}, {48, 64}};
    private static final int[][] LIFESTEAL_RANGES = {{8, 12}, {6, 10}, {5, 8}, {5, 8}, {5, 8}};
    private static final int[][] GLOWING_RANGES = {{15, 30}, {20, 40}, {25, 50}, {30, 60}, {35, 70}};
    private static final int[][] BLINDING_RANGES = {{3, 5}, {4, 6}, {5, 8}, {6, 10}, {8, 12}};
    private static final int[][] SLOWNESS_RANGES = {{3, 5}, {4, 6}, {5, 8}, {6, 10}, {8, 12}};
    private static final int[][][] ARMOR_HEALTH_RANGES = {
        {{45, 51}, {57, 63}, {69, 75}, {81, 87}, {93, 99}},
        {{131, 147}, {163, 179}, {196, 212}, {228, 244}, {260, 276}},
        {{355, 395}, {435, 475}, {516, 556}, {596, 636}, {676, 716}},
        {{894, 986}, {1077, 1169}, {1260, 1352}, {1443, 1535}, {1626, 1718}},
        {{2080, 2268}, {2457, 2645}, {2833, 3021}, {3210, 3398}, {3586, 3774}}
    };
    private static final ValueRange[][][] ARMOR_RANGES = {
        {
            {exactRange(3), new ValueRange(3, 4)},
            {new ValueRange(3, 4), exactRange(4)},
            {exactRange(4), new ValueRange(4, 5)},
            {new ValueRange(4, 5), exactRange(5)},
            {exactRange(5), new ValueRange(5, 6)}
        },
        {
            {exactRange(5), new ValueRange(5, 6)},
            {new ValueRange(5, 6), exactRange(6)},
            {exactRange(6), new ValueRange(6, 7)},
            {new ValueRange(6, 7), exactRange(7)},
            {exactRange(7), new ValueRange(7, 8)}
        },
        {
            {exactRange(7), new ValueRange(7, 8)},
            {new ValueRange(7, 8), exactRange(8)},
            {exactRange(9), new ValueRange(9, 10)},
            {new ValueRange(9, 10), new ValueRange(10, 11)},
            {new ValueRange(10, 11), new ValueRange(11, 12)}
        },
        {
            {new ValueRange(9, 10), new ValueRange(11, 12)},
            {new ValueRange(10, 11), new ValueRange(12, 13)},
            {new ValueRange(11, 12), new ValueRange(13, 14)},
            {new ValueRange(12, 13), new ValueRange(14, 15)},
            {new ValueRange(13, 14), new ValueRange(15, 16)}
        },
        {
            {new ValueRange(13, 14), new ValueRange(15, 16)},
            {new ValueRange(14, 15), new ValueRange(16, 17)},
            {new ValueRange(15, 16), new ValueRange(17, 18)},
            {new ValueRange(16, 17), new ValueRange(18, 19)},
            {new ValueRange(17, 18), new ValueRange(19, 20)}
        }
    };
    private static final ValueRange[][][] DAMAGE_REDUCTION_RANGES = {
        {
            {exactRange(2), new ValueRange(2, 3)},
            {new ValueRange(2, 3), exactRange(3)},
            {exactRange(3), exactRange(3)},
            {exactRange(3), exactRange(3)},
            {exactRange(3), new ValueRange(3, 4)}
        },
        {
            {exactRange(3), new ValueRange(3, 4)},
            {new ValueRange(3, 4), exactRange(4)},
            {exactRange(4), new ValueRange(4, 5)},
            {new ValueRange(4, 5), exactRange(5)},
            {exactRange(5), exactRange(5)}
        },
        {
            {exactRange(5), exactRange(5)},
            {exactRange(5), exactRange(5)},
            {exactRange(6), exactRange(7)},
            {new ValueRange(6, 7), exactRange(7)},
            {exactRange(7), new ValueRange(7, 8)}
        },
        {
            {new ValueRange(6, 7), new ValueRange(7, 8)},
            {exactRange(7), new ValueRange(8, 9)},
            {new ValueRange(7, 8), exactRange(9)},
            {new ValueRange(8, 9), new ValueRange(9, 10)},
            {exactRange(9), new ValueRange(10, 11)}
        },
        {
            {exactRange(9), new ValueRange(10, 11)},
            {new ValueRange(9, 10), exactRange(11)},
            {new ValueRange(10, 11), new ValueRange(11, 12)},
            {exactRange(11), new ValueRange(12, 13)},
            {new ValueRange(11, 12), exactRange(13)}
        }
    };
    private static final ValueRange[][] ARMOR_ENERGY_RANGES = {
        {new ValueRange(2.81, 3.00), new ValueRange(3.00, 3.19), new ValueRange(3.19, 3.38), new ValueRange(3.38, 3.56), new ValueRange(3.56, 3.75)},
        {new ValueRange(3.75, 3.94), new ValueRange(3.94, 4.13), new ValueRange(4.13, 4.31), new ValueRange(4.31, 4.50), new ValueRange(4.50, 4.69)},
        {new ValueRange(4.69, 4.88), new ValueRange(4.88, 5.06), new ValueRange(5.06, 5.25), new ValueRange(5.25, 5.44), new ValueRange(5.44, 5.63)},
        {new ValueRange(5.63, 5.81), new ValueRange(5.81, 6.00), new ValueRange(6.00, 6.19), new ValueRange(6.19, 6.38), new ValueRange(6.38, 6.56)},
        {new ValueRange(6.56, 6.75), new ValueRange(6.75, 6.94), new ValueRange(6.94, 7.13), new ValueRange(7.13, 7.31), new ValueRange(7.31, 7.50)}
    };
    private static final int[][] STAT_ATTRIBUTE_RANGES = {{175, 200}, {200, 225}, {225, 250}, {250, 275}, {275, 300}};
    private static final int[][] THORNS_RANGES = {{3, 4}, {3, 4}, {3, 4}, {4, 5}, {4, 6}};
    private static final int[][] REFLECT_RANGES = {{3, 4}, {3, 4}, {3, 4}, {3, 4}, {4, 5}};
    private static final int[][] BLOCK_RANGES = {{3, 4}, {3, 4}, {3, 4}, {3, 4}, {4, 5}};
    private static final int[][] DODGE_RANGES = {{3, 4}, {3, 4}, {3, 4}, {3, 4}, {4, 5}};
    private static final int[][] ELEMENTAL_RESIST_RANGES = {{15, 25}, {15, 25}, {15, 25}, {15, 25}, {15, 25}};
    private static final int[][] GEM_FIND_RANGES = {{6, 10}, {6, 10}, {6, 10}, {6, 10}, {6, 10}};
    private static final int[][] ITEM_FIND_RANGES = {{3, 5}, {3, 5}, {3, 5}, {3, 5}, {3, 5}};
    private static final int[][] KEY_FIND_RANGES = {{3, 5}, {3, 5}, {3, 5}, {3, 5}, {3, 5}};
    private static final int[][] MOVE_SPEED_RANGES = {{8, 10}, {8, 10}, {8, 10}, {8, 10}, {8, 10}};
    private static final int[][] ABSORPTION_RANGES = {{3, 4}, {3, 4}, {3, 4}, {4, 5}, {4, 6}};
    private static final int[][] HP_RECOVERY_RANGES = {{9, 12}, {9, 12}, {9, 12}, {9, 12}, {9, 12}};
    private static final int[][] COOLDOWN_RECOVERY_RANGES = {{10, 15}, {10, 15}, {10, 15}, {10, 15}, {10, 15}};
    private static final int[][] HEALING_RANGES = {{4, 6}, {4, 6}, {4, 6}, {4, 6}, {4, 6}};
    private static final int[][] ENERGY_DRAIN_RANGES = {{6, 9}, {6, 9}, {6, 9}, {6, 9}, {6, 9}};

    private static ValueRange exactRange(double value) {
        return new ValueRange(value, value);
    }

    private static ValueRange armorRangeForTier(int tier) {
        return switch (tier) {
            case 1 -> new ValueRange(3, 5);
            case 2 -> new ValueRange(4, 6);
            case 3 -> new ValueRange(5, 7);
            case 4 -> new ValueRange(6, 8);
            default -> new ValueRange(7, 9);
        };
    }

    private static double armorBaseValue(ArmorKind armorKind, int tier) {
        return switch (armorKind) {
            case Helmet -> switch (tier) {
                case 1 -> 4;
                case 2 -> 5;
                case 3 -> 6;
                case 4 -> 7;
                default -> 8;
            };
            case Chestplate -> switch (tier) {
                case 1 -> 8;
                case 2 -> 10;
                case 3 -> 12;
                case 4 -> 14;
                default -> 16;
            };
            case Leggings -> switch (tier) {
                case 1 -> 5;
                case 2 -> 7;
                case 3 -> 8;
                case 4 -> 9;
                default -> 10;
            };
            case Boots, Shield -> switch (tier) {
                case 1 -> 4;
                case 2 -> 5;
                case 3 -> 6;
                case 4 -> 7;
                default -> 8;
            };
        };
    }

    public static class CodexEntry {
        public final String name;
        public final String slot;
        public final String itemType;
        public final String rarity;
        public final int tier;
        public final int level;
        public final boolean mythic;
        public final int statCount;
        public final String sourceId;

        public CodexEntry(String name, String slot, String itemType, String rarity, int tier, int level, boolean mythic, int statCount, String sourceId) {
            this.name = name;
            this.slot = slot;
            this.itemType = itemType;
            this.rarity = rarity;
            this.tier = tier;
            this.level = level;
            this.mythic = mythic;
            this.statCount = statCount;
            this.sourceId = sourceId;
        }
    }

    public static class TooltipAnalysis {
        public final CustomItemDefinition definition;
        public final List<StatMatch> matches;
        public final double averagePercent;

        public TooltipAnalysis(CustomItemDefinition definition, List<StatMatch> matches) {
            this.definition = definition;
            this.matches = List.copyOf(matches);

            double total = 0;
            int count = 0;
            for (StatMatch match : matches) {
                total += match.percent;
                count++;
            }

            averagePercent = count == 0 ? 0 : total / count;
        }
    }

    public static class CustomItemDefinition {
        public final String sourceId;
        public final String name;
        public final String normalizedName;
        public final String slot;
        public final String itemType;
        public final String rarity;
        public final int tier;
        public final int level;
        public final boolean mythic;
        public final List<StatTemplate> statTemplates;

        public CustomItemDefinition(String sourceId, String name, String normalizedName, String slot, String itemType, String rarity, int tier, int level, boolean mythic, List<StatTemplate> statTemplates) {
            this.sourceId = sourceId;
            this.name = name;
            this.normalizedName = normalizedName;
            this.slot = slot;
            this.itemType = itemType;
            this.rarity = rarity;
            this.tier = tier;
            this.level = level;
            this.mythic = mythic;
            this.statTemplates = statTemplates;
        }

        public CandidateMatch match(String displayName, List<String> tooltipLines) {
            List<StatMatch> matches = new ArrayList<>();
            int score = 0;

            String normalizedDisplay = normalizeName(displayName);
            if (normalizedDisplay.equals(normalizedName)) score += 1000;
            else if (normalizedDisplay.contains(normalizedName) || normalizedName.contains(normalizedDisplay)) score += 300;

            for (StatTemplate template : statTemplates) {
                for (String tooltipLine : tooltipLines) {
                    StatMatch match = template.match(tooltipLine);
                    if (match == null) continue;

                    matches.add(match);
                    score += 100 + (int) Math.round(match.percent);
                    break;
                }
            }

            if (matches.isEmpty()) return null;
            return new CandidateMatch(this, matches, score);
        }
    }

    private static class CandidateMatch {
        public final CustomItemDefinition definition;
        public final List<StatMatch> matches;
        public final int score;

        private CandidateMatch(CustomItemDefinition definition, List<StatMatch> matches, int score) {
            this.definition = definition;
            this.matches = matches;
            this.score = score;
        }
    }

    public static class StatMatch {
        public final String label;
        public final String valueText;
        public final double percent;
        public final String rangeText;
        public final boolean overcap;

        public StatMatch(String label, String valueText, double percent, String rangeText, boolean overcap) {
            this.label = label;
            this.valueText = valueText;
            this.percent = percent;
            this.rangeText = rangeText;
            this.overcap = overcap;
        }
    }

    public static class StatTemplate {
        private static final Pattern RANGE_PATTERN = Pattern.compile("<\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*,\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*>");
        private static final String NUMBER_CAPTURE = "([+-]?\\d+(?:\\.\\d+)?)";

        public final String original;
        public final String label;
        public final Pattern pattern;
        public final List<ValueRange> ranges;

        private StatTemplate(String original, String label, Pattern pattern, List<ValueRange> ranges) {
            this.original = original;
            this.label = label;
            this.pattern = pattern;
            this.ranges = ranges;
        }

        public static StatTemplate parse(String template) {
            int colonIndex = template.indexOf(':');
            if (colonIndex <= 0) return null;

            String rawLabel = template.substring(0, colonIndex).trim();
            String canonicalLabel = canonicalStatLabel(rawLabel);
            List<String> aliases = statAliasesForLabel(rawLabel);
            String suffixTemplate = template.substring(colonIndex);

            Matcher matcher = RANGE_PATTERN.matcher(suffixTemplate);
            List<ValueRange> ranges = new ArrayList<>();
            StringBuilder suffixRegex = new StringBuilder();

            int last = 0;
            while (matcher.find()) {
                appendLiteralRegex(suffixRegex, suffixTemplate.substring(last, matcher.start()));
                suffixRegex.append(NUMBER_CAPTURE);
                ranges.add(new ValueRange(Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))));
                last = matcher.end();
            }

            if (ranges.isEmpty()) return null;

            appendLiteralRegex(suffixRegex, suffixTemplate.substring(last));
            StringBuilder finalRegex = new StringBuilder("^(?:");
            for (int i = 0; i < aliases.size(); i++) {
                if (i > 0) finalRegex.append("|");
                appendLiteralRegex(finalRegex, aliases.get(i));
            }
            finalRegex.append(")");
            finalRegex.append(suffixRegex);
            finalRegex.append("$");
            return new StatTemplate(template, canonicalLabel, Pattern.compile(finalRegex.toString(), Pattern.CASE_INSENSITIVE), List.copyOf(ranges));
        }

        public StatMatch match(String tooltipLine) {
            Matcher matcher = pattern.matcher(cleanLine(tooltipLine));
            if (!matcher.matches() || matcher.groupCount() != ranges.size()) return null;

            double percentTotal = 0;
            for (int i = 0; i < ranges.size(); i++) {
                double current = Double.parseDouble(matcher.group(i + 1));
                percentTotal += ranges.get(i).percent(current);
            }

            return new StatMatch(label, tooltipLine, percentTotal / ranges.size(), buildRangeText(), false);
        }

        private String buildRangeText() {
            if (ranges.size() == 1) {
                return formatNumber(ranges.get(0).min) + "-" + formatNumber(ranges.get(0).max);
            }

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < ranges.size(); i++) {
                if (i > 0) builder.append(" / ");
                builder.append(formatNumber(ranges.get(i).min)).append("-").append(formatNumber(ranges.get(i).max));
            }
            return builder.toString();
        }

        private String formatNumber(double value) {
            if (Math.abs(value - Math.rint(value)) < 0.0001) {
                return Integer.toString((int) Math.rint(value));
            }

            return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        }

        private static void appendLiteralRegex(StringBuilder regex, String literal) {
            for (int i = 0; i < literal.length(); i++) {
                char c = literal.charAt(i);
                if (Character.isWhitespace(c)) regex.append("\\s*");
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
    }

    public static class ValueRange {
        public final double min;
        public final double max;

        public ValueRange(double min, double max) {
            this.min = min;
            this.max = max;
        }

        public double percent(double value) {
            if (max <= min) return value >= max ? 100 : 0;
            return Math.max(0, Math.min(100, ((value - min) / (max - min)) * 100));
        }

        public boolean isOvercap(double value) {
            return value > max;
        }

        public ValueRange scaled(double multiplier) {
            return new ValueRange(Math.floor(min * multiplier), Math.ceil(max * multiplier));
        }
    }

    private record DamageProfile(double lowerMin, double lowerMax, double upperMin, double upperMax) { }

    private enum WeaponKind {
        Sword("Sword", 1.0),
        Scythe("Scythe", 1.05),
        Axe("Axe", 1.1),
        Mace("Mace", 1.15),
        Bow("Bow", 1.2);

        private final String label;
        private final double damageMultiplier;

        WeaponKind(String label, double damageMultiplier) {
            this.label = label;
            this.damageMultiplier = damageMultiplier;
        }

        private static @Nullable WeaponKind from(ItemStack stack) {
            String path = Registries.ITEM.getId(stack.getItem()).getPath();
            if (path.endsWith("_sword")) return Sword;
            if (path.endsWith("_hoe")) return Scythe;
            if (path.endsWith("_axe")) return Axe;
            if (path.endsWith("_shovel")) return Mace;
            if (path.equals("bow") || path.equals("crossbow")) return Bow;
            return null;
        }
    }

    private enum ArmorKind {
        Helmet("Helmet"),
        Chestplate("Chestplate"),
        Leggings("Leggings"),
        Boots("Boots"),
        Shield("Shield");

        private final String label;

        ArmorKind(String label) {
            this.label = label;
        }

        private static @Nullable ArmorKind from(ItemStack stack) {
            String path = Registries.ITEM.getId(stack.getItem()).getPath();
            if (path.endsWith("_helmet")) return Helmet;
            if (path.endsWith("_chestplate")) return Chestplate;
            if (path.endsWith("_leggings")) return Leggings;
            if (path.endsWith("_boots")) return Boots;
            if (path.equals("shield")) return Shield;
            return null;
        }
    }

    private static class GenericStatTemplate {
        private static final Pattern NUMBER_PATTERN = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)");
        private final String label;
        private final Pattern pattern;
        private final List<ValueRange> ranges;
        private final List<String> aliases;

        private GenericStatTemplate(String label, Pattern pattern, List<ValueRange> ranges, List<String> aliases) {
            this.label = label;
            this.pattern = pattern;
            this.ranges = ranges;
            this.aliases = aliases;
        }

        public static GenericStatTemplate singleRange(String label, String prefix, ValueRange range) {
            return new GenericStatTemplate(
                label,
                Pattern.compile("^" + Pattern.quote(prefix) + "\\s*:\\s*\\+?([+-]?\\d+(?:\\.\\d+)?)%?$", Pattern.CASE_INSENSITIVE),
                List.of(range),
                List.of(normalizeAlias(prefix))
            );
        }

        public static GenericStatTemplate singleRangeWithAliases(String label, ValueRange range, String... prefixes) {
            StringBuilder prefixRegex = new StringBuilder();
            for (int i = 0; i < prefixes.length; i++) {
                if (i > 0) prefixRegex.append("|");
                prefixRegex.append(Pattern.quote(prefixes[i]));
            }

            return new GenericStatTemplate(
                label,
                Pattern.compile("^(?:" + prefixRegex + ")\\s*:\\s*\\+?([+-]?\\d+(?:\\.\\d+)?)(?:%|/s|\\s*HP/s)?$", Pattern.CASE_INSENSITIVE),
                List.of(range),
                normalizedAliases(prefixes)
            );
        }

        public static GenericStatTemplate doubleRange(String label, String prefix, ValueRange first, ValueRange second) {
            return new GenericStatTemplate(
                label,
                Pattern.compile("^" + Pattern.quote(prefix) + "\\s*:\\s*\\+?([+-]?\\d+(?:\\.\\d+)?)\\s*-\\s*([+-]?\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE),
                List.of(first, second),
                List.of(normalizeAlias(prefix))
            );
        }

        public static GenericStatTemplate doubleRangeWithAliases(String label, ValueRange first, ValueRange second, String... prefixes) {
            StringBuilder prefixRegex = new StringBuilder();
            for (int i = 0; i < prefixes.length; i++) {
                if (i > 0) prefixRegex.append("|");
                prefixRegex.append(Pattern.quote(prefixes[i]));
            }

            return new GenericStatTemplate(
                label,
                Pattern.compile("^(?:" + prefixRegex + ")\\s*:\\s*\\+?([+-]?\\d+(?:\\.\\d+)?)\\s*-\\s*([+-]?\\d+(?:\\.\\d+)?)(?:%|/s)?$", Pattern.CASE_INSENSITIVE),
                List.of(first, second),
                normalizedAliases(prefixes)
            );
        }

        public @Nullable StatMatch matchAny(List<String> tooltipLines) {
            for (String tooltipLine : tooltipLines) {
                StatMatch match = match(tooltipLine);
                if (match != null) return match;
            }

            return null;
        }

        private @Nullable StatMatch match(String tooltipLine) {
            String cleaned = cleanLine(tooltipLine);
            Matcher matcher = pattern.matcher(cleaned);
            if (matcher.matches() && matcher.groupCount() == ranges.size()) {
                return buildMatch(tooltipLine, matcher);
            }

            return matchLoose(tooltipLine, cleaned);
        }

        private @Nullable StatMatch buildMatch(String tooltipLine, Matcher matcher) {
            double percentTotal = 0;
            for (int i = 0; i < ranges.size(); i++) {
                double current = Double.parseDouble(matcher.group(i + 1));
                percentTotal += ranges.get(i).percent(current);
            }

            return new StatMatch(label, tooltipLine, percentTotal / ranges.size(), buildRangeText(), false);
        }

        private @Nullable StatMatch matchLoose(String tooltipLine, String cleaned) {
            int colon = cleaned.indexOf(':');
            if (colon <= 0) return null;

            String rawLabel = normalizeAlias(cleaned.substring(0, colon));
            if (!aliases.contains(rawLabel)) return null;

            List<Double> values = new ArrayList<>();
            Matcher numbers = NUMBER_PATTERN.matcher(cleaned.substring(colon + 1));
            while (numbers.find() && values.size() < ranges.size()) {
                values.add(Double.parseDouble(numbers.group(1)));
            }
            if (values.size() != ranges.size()) return null;

            double percentTotal = 0;
            for (int i = 0; i < ranges.size(); i++) {
                double current = values.get(i);
                percentTotal += ranges.get(i).percent(current);
            }
            return new StatMatch(label, tooltipLine, percentTotal / ranges.size(), buildRangeText(), false);
        }

        private String buildRangeText() {
            if (ranges.size() == 1) return formatRange(ranges.get(0));

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < ranges.size(); i++) {
                if (i > 0) builder.append(" / ");
                builder.append(formatRange(ranges.get(i)));
            }
            return builder.toString();
        }

        private String formatRange(ValueRange range) {
            return formatNumber(range.min) + "-" + formatNumber(range.max);
        }

        private String formatNumber(double value) {
            if (Math.abs(value - Math.rint(value)) < 0.0001) {
                return Integer.toString((int) Math.rint(value));
            }

            return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        }

        private static List<String> normalizedAliases(String... prefixes) {
            List<String> normalized = new ArrayList<>(prefixes.length);
            for (String prefix : prefixes) {
                normalized.add(normalizeAlias(prefix));
            }
            return List.copyOf(normalized);
        }

        private static String normalizeAlias(String value) {
            return cleanLine(value).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        }
    }
}
