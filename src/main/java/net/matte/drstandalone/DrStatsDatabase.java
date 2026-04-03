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

        if (best != null && !best.matches.isEmpty()) return new TooltipAnalysis(best.definition, best.matches);
        TooltipAnalysis genericWeapon = analyzeGenericWeapon(stack, tooltipLines, displayName, rarity);
        if (genericWeapon != null) return genericWeapon;
        return analyzeGenericArmor(stack, tooltipLines, displayName, rarity);
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
            if (normalized.contains(definition.normalizedName) || definition.normalizedName.contains(normalized)) {
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
        return value == null ? "" : value.replace('\u00A0', ' ').trim();
    }

    private static String normalizeName(String value) {
        String cleaned = cleanDisplayName(value).toLowerCase(Locale.ROOT);
        cleaned = cleaned.replaceAll("[^a-z0-9]+", " ").trim();
        return cleaned.replaceAll("\\s+", " ");
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
        ValueRange hpRegenRange = new ValueRange(Math.floor(healthRange.min / 2d), Math.ceil(healthRange.max / 2d));
        ValueRange energyRange = ARMOR_ENERGY_RANGES[tier - 1][rarityIndex];

        templates.add(GenericStatTemplate.singleRangeWithAliases("ARMOR", exactRange(armorBaseValue(armorKind, tier)), "ARMOR"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("HP", healthRange, "HP", "HEALTH"));
        templates.add(GenericStatTemplate.singleRangeWithAliases("HP REGEN", hpRegenRange, "HP REGEN", "HP/S", "HP REGEN/S"));
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
        templates.add(GenericStatTemplate.singleRangeWithAliases(
            "ELEMENTAL RESIST",
            flatRange(ELEMENTAL_RESIST_RANGES[tier - 1]),
            "FIRE RESIST", "FIRE RESISTANCE",
            "ICE RESIST", "ICE RESISTANCE",
            "POISON RESIST", "POISON RESISTANCE",
            "PURE RESIST", "PURE RESISTANCE",
            "ELEMENTAL RESIST", "ELEMENTAL RESISTANCE"
        ));

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

    private static final Pattern LEVEL_LINE = Pattern.compile("^LEVEL\\s*:\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
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
    private static final int[][][] ARMOR_HEALTH_RANGES = {
        {{45, 51}, {57, 63}, {69, 75}, {81, 87}, {93, 99}},
        {{131, 147}, {163, 179}, {196, 212}, {228, 244}, {260, 276}},
        {{355, 395}, {435, 475}, {516, 556}, {596, 636}, {676, 716}},
        {{894, 986}, {1077, 1169}, {1260, 1352}, {1443, 1535}, {1626, 1718}},
        {{2080, 2268}, {2457, 2645}, {2833, 3021}, {3210, 3398}, {3586, 3774}}
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
    private static final int[][] REFLECT_RANGES = {{3, 4}, {3, 4}, {4, 5}, {4, 5}, {5, 6}};
    private static final int[][] BLOCK_RANGES = {{3, 4}, {3, 4}, {4, 5}, {4, 5}, {5, 6}};
    private static final int[][] DODGE_RANGES = {{3, 4}, {3, 4}, {4, 5}, {4, 5}, {5, 6}};
    private static final int[][] ELEMENTAL_RESIST_RANGES = {{15, 25}, {15, 25}, {15, 25}, {15, 25}, {15, 25}};
    private static final int[][] GEM_FIND_RANGES = {{12, 16}, {12, 16}, {12, 16}, {12, 16}, {12, 16}};
    private static final int[][] ITEM_FIND_RANGES = {{6, 8}, {6, 8}, {6, 8}, {6, 8}, {6, 8}};
    private static final int[][] MOVE_SPEED_RANGES = {{8, 10}, {8, 10}, {8, 10}, {8, 10}, {8, 10}};

    private static ValueRange exactRange(double value) {
        return new ValueRange(value, value);
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
            Matcher matcher = RANGE_PATTERN.matcher(template);
            List<ValueRange> ranges = new ArrayList<>();
            StringBuilder regex = new StringBuilder("^");

            int last = 0;
            while (matcher.find()) {
                appendLiteralRegex(regex, template.substring(last, matcher.start()));
                regex.append(NUMBER_CAPTURE);
                ranges.add(new ValueRange(Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))));
                last = matcher.end();
            }

            if (ranges.isEmpty()) return null;

            appendLiteralRegex(regex, template.substring(last));
            regex.append("$");

            String label = template.substring(0, template.indexOf(':')).trim();
            return new StatTemplate(template, label, Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE), List.copyOf(ranges));
        }

        public StatMatch match(String tooltipLine) {
            Matcher matcher = pattern.matcher(cleanLine(tooltipLine));
            if (!matcher.matches() || matcher.groupCount() != ranges.size()) return null;

            double percentTotal = 0;
            boolean overcap = false;
            for (int i = 0; i < ranges.size(); i++) {
                double current = Double.parseDouble(matcher.group(i + 1));
                percentTotal += ranges.get(i).percent(current);
                if (ranges.get(i).isOvercap(current)) overcap = true;
            }

            return new StatMatch(label, tooltipLine, percentTotal / ranges.size(), buildRangeText(), overcap);
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
        private final String label;
        private final Pattern pattern;
        private final List<ValueRange> ranges;

        private GenericStatTemplate(String label, Pattern pattern, List<ValueRange> ranges) {
            this.label = label;
            this.pattern = pattern;
            this.ranges = ranges;
        }

        public static GenericStatTemplate singleRange(String label, String prefix, ValueRange range) {
            return new GenericStatTemplate(
                label,
                Pattern.compile("^" + Pattern.quote(prefix) + "\\s*:\\s*\\+?([+-]?\\d+(?:\\.\\d+)?)%?$", Pattern.CASE_INSENSITIVE),
                List.of(range)
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
                Pattern.compile("^(?:" + prefixRegex + ")\\s*:\\s*\\+?([+-]?\\d+(?:\\.\\d+)?)%?(?:\\s*HP/s)?$", Pattern.CASE_INSENSITIVE),
                List.of(range)
            );
        }

        public static GenericStatTemplate doubleRange(String label, String prefix, ValueRange first, ValueRange second) {
            return new GenericStatTemplate(
                label,
                Pattern.compile("^" + Pattern.quote(prefix) + "\\s*:\\s*\\+?([+-]?\\d+(?:\\.\\d+)?)\\s*-\\s*([+-]?\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE),
                List.of(first, second)
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
            Matcher matcher = pattern.matcher(cleanLine(tooltipLine));
            if (!matcher.matches() || matcher.groupCount() != ranges.size()) return null;

            double percentTotal = 0;
            boolean overcap = false;
            for (int i = 0; i < ranges.size(); i++) {
                double current = Double.parseDouble(matcher.group(i + 1));
                percentTotal += ranges.get(i).percent(current);
                if (ranges.get(i).isOvercap(current)) overcap = true;
            }

            return new StatMatch(label, tooltipLine, percentTotal / ranges.size(), buildRangeText(), overcap);
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
    }
}
