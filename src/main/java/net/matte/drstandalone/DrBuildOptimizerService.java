package net.matte.drstandalone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrBuildOptimizerService {
    private static final List<String> CLASS_ORDER = List.of("Warrior", "Rogue", "Paladin");
    private static final Set<String> TRUSTED_ENDPOINT_HOSTS = Set.of("api.openai.com", "openai.com");
    private static final Pattern DMG_PATTERN = Pattern.compile("^DMG\\s*:\\s*\\+?(\\d+(?:\\.\\d+)?)\\s*-\\s*(\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_STAT_PATTERN = Pattern.compile("^([A-Z ./]+?)\\s*:\\s*\\+?([+-]?\\d+(?:\\.\\d+)?)(?:%|/s| HP/s)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_LEVEL_PATTERN = Pattern.compile(".*LEVEL\\s*:?\\s*(\\d+).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DR_STATUS_PATTERN = Pattern.compile("LV\\s*(\\d+)\\s+([A-Z]+)\\s*-\\s*HP\\s*(\\d+(?:\\.\\d+)?)\\s*-\\s*XP\\s*(\\d+(?:\\.\\d+)?)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCHANT_BONUS_SUFFIX = Pattern.compile("\\s*\\([+-]?\\d+(?:\\.\\d+)?\\)\\s*$");
    private static final Pattern ENCHANT_BONUS_CAPTURE = Pattern.compile("\\(([+-]?\\d+(?:\\.\\d+)?)\\)");
    private static String sessionApiKey = "";

    private DrBuildOptimizerService() {
    }

    public static void setSessionApiKey(String apiKey) {
        sessionApiKey = apiKey == null ? "" : apiKey.trim();
    }

    public static String getSessionApiKey() {
        return sessionApiKey;
    }

    public static boolean isTrustedEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return true;

        try {
            URI uri = URI.create(endpoint.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !scheme.equalsIgnoreCase("https")) return false;
            if (host == null || host.isBlank()) return false;
            return TRUSTED_ENDPOINT_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String endpointSecurityMessage(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return "No endpoint configured (offline-safe).";
        return isTrustedEndpoint(endpoint) ? "Trusted endpoint (HTTPS + allowlisted host)." : "Untrusted endpoint (requires HTTPS + allowlisted host).";
    }

    public static OptimizationReport analyzeCurrentBuild() {
        MinecraftClient mc = MinecraftClient.getInstance();
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (mc.player == null) return OptimizationReport.empty("No player detected.");

        List<DpsMeterFeature.BuildScore> scores = new ArrayList<>();
        for (String className : CLASS_ORDER) {
            DpsMeterFeature.BuildScore score = DpsMeterFeature.evaluateCurrentBuildForClass(className);
            if (score != null) scores.add(score);
        }

        if (scores.isEmpty()) {
            return OptimizationReport.empty("Equip a valid DR weapon to run DPS analysis.");
        }

        scores.sort(Comparator.comparingDouble(DpsMeterFeature.BuildScore::dps).reversed());
        DpsMeterFeature.BuildScore best = scores.getFirst();
        DpsMeterFeature.BuildScore current = scores.stream()
            .filter(score -> score.classProfile().equalsIgnoreCase(config.classProfile))
            .findFirst()
            .orElse(best);

        List<ItemCheck> checks = analyzeEquippedItems(mc);
        PlayerSnapshot snapshot = buildPlayerSnapshot(mc);
        List<StatTotal> totals = aggregateTotals(checks);
        ItemCheck weakest = checks.stream()
            .filter(item -> item.averageRollPercent >= 0)
            .min(Comparator.comparingDouble(item -> item.averageRollPercent))
            .orElse(null);

        List<String> recommendations = new ArrayList<>();
        double delta = best.dps() - current.dps();
        if (!best.classProfile().equalsIgnoreCase(config.classProfile) && delta > 1) {
            recommendations.add(String.format(Locale.ROOT,
                "Recommended class: %s (+%.1f DPS vs %s)",
                best.classProfile(),
                delta,
                config.classProfile));
        } else {
            recommendations.add(String.format(Locale.ROOT,
                "Current class is already strong (%s). Max simulated gain: +%.1f DPS",
                config.classProfile,
                Math.max(0, delta)));
        }

        if (weakest != null && weakest.averageRollPercent < 70) {
            recommendations.add(String.format(Locale.ROOT,
                "Weakest piece: %s (%.1f%%). Priority: replace it.",
                weakest.slotLabel,
                weakest.averageRollPercent));
        }

        recommendations.add("Recommended stat focus: " + focusForClass(best.classProfile()));
        recommendations.add(suggestCodexUpgrade(weakest));

        if (config.optimizerCloudAdvisorEnabled) {
            if (sessionApiKey.isBlank()) {
                recommendations.add("API mode enabled but key is missing: add a key for cloud advice.");
            } else {
                recommendations.add("API mode active: key detected (cloud endpoint integration still pending). ");
            }
        }

        if (snapshot.maxHealth() > 0) {
            recommendations.add(String.format(Locale.ROOT,
                "Player snapshot: level %d, %.0f/%.0f HP, armor %.0f.",
                snapshot.level(),
                snapshot.health(),
                snapshot.maxHealth(),
                snapshot.armor()));
        }

        return new OptimizationReport(best, current, scores, checks, totals, snapshot, recommendations);
    }

    private static List<ItemCheck> analyzeEquippedItems(MinecraftClient mc) {
        List<ItemCheck> checks = new ArrayList<>();
        checks.add(checkStack("Weapon", mc.player.getMainHandStack(), mc));
        checks.add(checkStack("Offhand", mc.player.getOffHandStack(), mc));
        checks.add(checkStack("Helmet", mc.player.getEquippedStack(EquipmentSlot.HEAD), mc));
        checks.add(checkStack("Chestplate", mc.player.getEquippedStack(EquipmentSlot.CHEST), mc));
        checks.add(checkStack("Leggings", mc.player.getEquippedStack(EquipmentSlot.LEGS), mc));
        checks.add(checkStack("Boots", mc.player.getEquippedStack(EquipmentSlot.FEET), mc));
        return checks;
    }

    private static ItemCheck checkStack(String slot, ItemStack stack, MinecraftClient mc) {
        if (stack == null || stack.isEmpty()) {
            DrStandaloneMod.LOG.info("Build Optimizer [{}]: empty slot.", slot);
            return new ItemCheck(slot, "(empty)", "", "", "", "", 0, 0, false, -1, 0, List.of(), List.of());
        }

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        List<Text> tooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, mc.player, TooltipType.BASIC);
        DrItemRollsFeature.TooltipSnapshot snapshot = DrItemRollsFeature.inspectTooltip(stack, tooltip, DrStandaloneMod.config());
        List<String> displayTooltipLines = tooltip.stream().map(Text::getString).map(DrBuildOptimizerService::stripRollAnnotations).filter(line -> !line.isBlank()).toList();
        List<String> tooltipLines = sanitizeTooltip(snapshot.cleanedLines());
        String fallbackRarity = formatRarity(snapshot.rarity(), snapshot.transmuted());
        int fallbackTier = detectTierFromMaterial(stack);
        int fallbackLevel = detectItemLevel(displayTooltipLines);
        String fallbackItemType = detectItemType(stack);
        String fallbackMaterial = detectMaterialLabel(stack);
        DrStatsDatabase.TooltipAnalysis analysis = snapshot.analysis();
        List<StatLine> parsedStats = parseTooltipStats(displayTooltipLines);
        if (analysis == null) {
            DrStandaloneMod.LOG.info("Build Optimizer [{}]: no DB/generic match for '{}' | rarity='{}' tier={} level={} type='{}' material='{}' | tooltip={}",
                slot, stack.getName().getString(), fallbackRarity, fallbackTier, fallbackLevel, fallbackItemType, fallbackMaterial, tooltipLines);
            return new ItemCheck(slot, stack.getName().getString(), itemId, fallbackRarity, fallbackItemType, fallbackMaterial, fallbackTier, fallbackLevel, false, -1, parsedStats.size(), parsedStats, tooltipLines);
        }

        Map<String, StatLine> parsedByLabel = new LinkedHashMap<>();
        for (StatLine parsed : parsedStats) {
            parsedByLabel.putIfAbsent(parsed.label(), parsed);
        }

        List<StatLine> statLines = new ArrayList<>();
        for (DrStatsDatabase.StatMatch match : analysis.matches) {
            String normalizedLabel = normalizeStatLabel(match.label);
            StatLine enriched = parsedByLabel.get(normalizedLabel);
            String cleanValueText = enriched != null ? enriched.valueText() : sanitizeTooltipLine(match.valueText);
            statLines.add(new StatLine(
                normalizedLabel,
                cleanValueText,
                enriched != null ? enriched.numericValue() : statValue(cleanValueText, match.label),
                statCategory(match.label),
                match.percent,
                match.rangeText,
                match.overcap
            ));
        }
        mergeFallbackStats(statLines, parsedStats);

        ItemCheck result = new ItemCheck(
            slot,
            stack.getName().getString(),
            itemId,
            firstNonBlank(analysis.definition.rarity, fallbackRarity),
            firstNonBlank(analysis.definition.itemType, fallbackItemType),
            fallbackMaterial,
            analysis.definition.tier > 0 ? analysis.definition.tier : fallbackTier,
            analysis.definition.level > 0 ? analysis.definition.level : fallbackLevel,
            analysis.definition.mythic,
            analysis.averagePercent,
            analysis.matches.size(),
            List.copyOf(statLines),
            tooltipLines
        );
        DrStandaloneMod.LOG.info("Build Optimizer [{}]: matched '{}' | rarity='{}' tier={} level={} type='{}' material='{}' | matchedStats={} | source='{}'",
            slot, result.itemName(), result.rarity(), result.tier(), result.level(), result.itemType(), result.materialLabel(), result.matchedStats(), analysis.definition.sourceId);
        return result;
    }

    private static PlayerSnapshot buildPlayerSnapshot(MinecraftClient mc) {
        String world = mc.world == null ? "unknown" : mc.world.getRegistryKey().getValue().toString();
        String server = mc.getCurrentServerEntry() == null ? "singleplayer/unknown" : mc.getCurrentServerEntry().address;
        DrHudSnapshot.Snapshot drawnHudSnapshot = DrHudSnapshot.latestFresh(15_000);
        HudStatusSnapshot hudStatus = findDungeonRealmsHudStatus(mc);
        int level = drawnHudSnapshot != null ? drawnHudSnapshot.level() : hudStatus != null ? hudStatus.level() : mc.player.experienceLevel;
        double xpPercent = drawnHudSnapshot != null ? drawnHudSnapshot.xpPercent() : hudStatus != null ? hudStatus.xpPercent() : mc.player.experienceProgress * 100.0f;
        double health = drawnHudSnapshot != null ? drawnHudSnapshot.health() : hudStatus != null ? hudStatus.health() * hudStatus.bossProgress() : mc.player.getHealth();
        double maxHealth = drawnHudSnapshot != null ? drawnHudSnapshot.health() : hudStatus != null ? hudStatus.health() : mc.player.getMaxHealth();
        String hudClass = drawnHudSnapshot != null ? drawnHudSnapshot.className() : hudStatus == null ? "" : hudStatus.className();

        return new PlayerSnapshot(
            mc.player.getName().getString(),
            level,
            health,
            maxHealth,
            mc.player.getAbsorptionAmount(),
            mc.player.getArmor(),
            mc.player.getHungerManager().getFoodLevel(),
            mc.player.getAir(),
            xpPercent,
            mc.player.getScore(),
            world,
            server,
            mc.player.getBlockX(),
            mc.player.getBlockY(),
            mc.player.getBlockZ(),
            mc.player.getMainHandStack().getName().getString(),
            mc.player.getOffHandStack().getName().getString(),
            hudClass
        );
    }

    private static List<StatTotal> aggregateTotals(List<ItemCheck> checks) {
        Map<String, MutableStatTotal> totals = new LinkedHashMap<>();
        for (ItemCheck check : checks) {
            for (StatLine stat : check.stats()) {
                if (!Double.isFinite(stat.numericValue())) continue;
                MutableStatTotal total = totals.computeIfAbsent(stat.label(), ignored -> new MutableStatTotal(stat.label(), stat.category()));
                total.value += stat.numericValue();
                total.sources++;
            }
        }

        List<StatTotal> ordered = new ArrayList<>();
        for (MutableStatTotal total : totals.values()) {
            ordered.add(new StatTotal(total.label, total.value, total.category, total.sources));
        }
        ordered.sort(Comparator.comparingInt((StatTotal stat) -> categorySortIndex(stat.category()))
            .thenComparingInt(stat -> statPriority(stat.label()))
            .thenComparing(StatTotal::label));
        return List.copyOf(ordered);
    }

    private static List<String> sanitizeTooltip(List<Text> tooltip) {
        List<String> lines = new ArrayList<>();
        for (Text line : tooltip) {
            String value = sanitizeTooltipLine(line.getString());
            if (!value.isBlank()) lines.add(value);
        }
        return lines;
    }

    private static List<StatLine> parseTooltipStats(List<String> tooltipLines) {
        List<StatLine> stats = new ArrayList<>();
        for (String line : tooltipLines) {
            String matchableLine = sanitizeTooltipLine(line);
            Matcher dmg = DMG_PATTERN.matcher(matchableLine);
            if (dmg.matches()) {
                double low = parseDouble(dmg.group(1));
                double high = parseDouble(dmg.group(2));
                stats.add(new StatLine("DMG", line, (low + high) / 2d, "Offense", -1, low + "-" + high, false));
                continue;
            }

            Matcher single = SINGLE_STAT_PATTERN.matcher(matchableLine);
            if (!single.matches()) continue;
            String label = normalizeStatLabel(single.group(1));
            if ("LEVEL".equals(label)) continue;
            double value = parseDouble(single.group(2)) + extractEnchantBonus(line);
            stats.add(new StatLine(label, line, value, statCategory(label), -1, "", false));
        }
        return stats;
    }

    private static void mergeFallbackStats(List<StatLine> primary, List<StatLine> fallback) {
        for (StatLine extra : fallback) {
            boolean exists = primary.stream().anyMatch(stat -> stat.label().equalsIgnoreCase(extra.label()));
            if (!exists) primary.add(extra);
        }
    }

    private static double statValue(String valueText, String label) {
        Matcher dmg = DMG_PATTERN.matcher(sanitizeTooltipLine(valueText));
        if (dmg.matches()) {
            return (parseDouble(dmg.group(1)) + parseDouble(dmg.group(2))) / 2d;
        }

        String sanitized = sanitizeTooltipLine(valueText);
        Matcher single = SINGLE_STAT_PATTERN.matcher(sanitized);
        if (single.matches()) {
            return parseDouble(single.group(2)) + extractEnchantBonus(valueText);
        }

        return Double.NaN;
    }

    private static String normalizeStatLabel(String label) {
        String normalized = label == null ? "" : label.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STRENGTH" -> "STR";
            case "DEXTERITY" -> "DEX";
            case "VITALITY" -> "VIT";
            case "INTELLECT", "INTELLIGENCE" -> "INT";
            case "HEALTH" -> "HP";
            case "HEALTH REGEN", "HEALTH/S", "HP/S", "HP REGEN/S", "HP RECOVERY" -> "HP REGEN";
            case "ENERGY/S", "ENERGY REGEN/S" -> "ENERGY REGEN";
            case "DMG REDUCTION" -> "ARMOR";
            case "FIRE RESISTANCE" -> "FIRE RESIST";
            case "ICE RESISTANCE" -> "ICE RESIST";
            case "POISON RESISTANCE" -> "POISON RESIST";
            case "PURE RESISTANCE" -> "PURE RESIST";
            case "ELEMENTAL RESISTANCE" -> "ELEMENTAL RESIST";
            case "MOVEMENT SPEED" -> "MOVE SPEED";
            default -> normalized;
        };
    }

    private static String statCategory(String label) {
        return switch (normalizeStatLabel(label)) {
            case "DMG", "VS. MONSTERS", "VS. PLAYERS", "CRITICAL HIT", "PURE DMG", "FIRE DMG", "ICE DMG", "POISON DMG",
                "PIERCING", "ACCURACY", "SHATTER", "EXECUTE", "CRUSHING", "BLEEDING", "CLEAVE", "LIFE STEAL",
                "GLOWING", "BLINDING", "SLOWNESS" -> "Offense";
            case "HP", "ARMOR", "HP REGEN", "ENERGY REGEN", "THORNS", "REFLECT", "BLOCK", "DODGE",
                "FIRE RESIST", "ICE RESIST", "POISON RESIST", "PURE RESIST", "ELEMENTAL RESIST", "ABSORPTION" -> "Defense";
            case "STR", "DEX", "VIT", "INT" -> "Attributes";
            default -> "Utility";
        };
    }

    private static int categorySortIndex(String category) {
        return switch (category) {
            case "Attributes" -> 0;
            case "Offense" -> 1;
            case "Defense" -> 2;
            default -> 3;
        };
    }

    private static int statPriority(String label) {
        return switch (normalizeStatLabel(label)) {
            case "DMG" -> 0;
            case "STR" -> 1;
            case "DEX" -> 2;
            case "VIT" -> 3;
            case "INT" -> 4;
            case "HP" -> 5;
            case "ARMOR" -> 6;
            case "CRITICAL HIT" -> 7;
            case "ACCURACY" -> 8;
            case "PIERCING" -> 9;
            case "SHATTER" -> 10;
            case "EXECUTE" -> 11;
            case "CRUSHING" -> 12;
            case "ENERGY REGEN" -> 13;
            case "HP REGEN" -> 14;
            case "COOLDOWN RECOVERY" -> 15;
            case "HEALING" -> 16;
            case "ENERGY DRAIN" -> 17;
            case "ABSORPTION" -> 18;
            case "HP RECOVERY" -> 19;
            case "KEY FIND" -> 20;
            default -> 100;
        };
    }

    private static String sanitizeTooltipLine(String value) {
        String sanitized = value.replace('\u00A0', ' ').trim();
        String previous;
        do {
            previous = sanitized;
            sanitized = sanitized
                .replaceAll("\\s*\\([+-]?\\d+(?:\\.\\d+)?\\)\\s*$", "")
                .replaceAll("\\s+\\[(?:MIN|MAX|OVERCAP|Overcap|\\d+(?:\\.\\d+)?%|\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?)?)\\]\\s*$", "")
                .trim();
        } while (!sanitized.equals(previous));
        return sanitized;
    }

    private static String stripRollAnnotations(String value) {
        String sanitized = value.replace('\u00A0', ' ').trim();
        String previous;
        do {
            previous = sanitized;
            sanitized = sanitized
                .replaceAll("\\s+\\[(?:MIN|MAX|OVERCAP|Overcap|\\d+(?:\\.\\d+)?%|\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?)?)\\]\\s*$", "")
                .trim();
        } while (!sanitized.equals(previous));
        return sanitized;
    }

    private static double extractEnchantBonus(String value) {
        Matcher matcher = ENCHANT_BONUS_CAPTURE.matcher(value);
        if (matcher.find()) {
            return parseDouble(matcher.group(1));
        }
        return 0d;
    }

    private static int detectItemLevel(List<String> tooltipLines) {
        for (String line : tooltipLines) {
            Matcher matcher = ITEM_LEVEL_PATTERN.matcher(line);
            if (matcher.matches()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String formatRarity(DrRarityHelper.TooltipTheme rarity, boolean transmuted) {
        if (rarity == null) return "";
        String base = switch (rarity) {
            case Common -> "Common";
            case Uncommon -> "Uncommon";
            case Rare -> "Rare";
            case Epic -> "Epic";
            case Legendary -> "Legendary";
            case Mythic -> "Mythic";
        };
        return transmuted ? "Transmuted " + base : base;
    }

    private static int detectTierFromMaterial(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.startsWith("wooden_") || path.startsWith("leather_")) return 1;
        if (path.startsWith("stone_") || path.startsWith("chainmail_")) return 2;
        if (path.startsWith("iron_")) return 3;
        if (path.startsWith("diamond_")) return 4;
        if (path.startsWith("golden_")) return 5;
        return 0;
    }

    private static String detectItemType(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.endsWith("_sword")) return "Sword";
        if (path.endsWith("_hoe")) return "Hoe";
        if (path.endsWith("_axe")) return "Axe";
        if (path.endsWith("_shovel")) return "Shovel";
        if (path.endsWith("_pickaxe")) return "Pickaxe";
        if (path.endsWith("_helmet")) return "Helmet";
        if (path.endsWith("_chestplate")) return "Chestplate";
        if (path.endsWith("_leggings")) return "Leggings";
        if (path.endsWith("_boots")) return "Boots";
        if (path.equals("bow")) return "Bow";
        if (path.equals("crossbow")) return "Crossbow";
        if (path.equals("shield")) return "Shield";
        return "";
    }

    private static String detectMaterialLabel(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.startsWith("wooden_")) return "Wood";
        if (path.startsWith("stone_")) return "Stone";
        if (path.startsWith("iron_")) return "Iron";
        if (path.startsWith("diamond_")) return "Diamond";
        if (path.startsWith("golden_")) return "Gold";
        if (path.startsWith("netherite_")) return "Netherite";
        if (path.startsWith("leather_")) return "Leather";
        if (path.startsWith("chainmail_")) return "Chainmail";
        return "";
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value.replace(',', '.'));
    }

    private static String suggestCodexUpgrade(ItemCheck weakest) {
        if (weakest == null) return "Scan the codex: target a higher tier/rarity item on your main offensive slot.";
        if (weakest.averageRollPercent < 0) return "Item not recognized in DB: check the codex and compare an equivalent item with same slot/tier.";
        return "Scan the codex: look for the same slot with higher average roll (>85%).";
    }

    private static String focusForClass(String className) {
        return switch (className) {
            case "Warrior" -> "STR, CRIT, SHATTER, FIRE DMG";
            case "Rogue" -> "DEX, ACCURACY, PIERCING, POISON DMG";
            case "Paladin" -> "VIT, PURE DMG, CRUSHING, VS. MONSTERS";
            default -> "DMG de base + ENERGY REGEN + CRIT";
        };
    }

    public record StatLine(String label, String valueText, double numericValue, String category, double percent, String rangeText, boolean overcap) {
    }

    public record StatTotal(String label, double value, String category, int contributingItems) {
    }

    public record PlayerSnapshot(
        String playerName,
        int level,
        double health,
        double maxHealth,
        double absorption,
        double armor,
        int hunger,
        int air,
        double xpPercent,
        int score,
        String worldKey,
        String serverAddress,
        int blockX,
        int blockY,
        int blockZ,
        String mainHand,
        String offHand,
        String hudClass
    ) {
    }

    public record ItemCheck(
        String slotLabel,
        String itemName,
        String itemId,
        String rarity,
        String itemType,
        String materialLabel,
        int tier,
        int level,
        boolean mythic,
        double averageRollPercent,
        int matchedStats,
        List<StatLine> stats,
        List<String> tooltipLines
    ) {
    }

    public record OptimizationReport(
        DpsMeterFeature.BuildScore bestClass,
        DpsMeterFeature.BuildScore currentClass,
        List<DpsMeterFeature.BuildScore> classRanking,
        List<ItemCheck> itemChecks,
        List<StatTotal> statTotals,
        PlayerSnapshot playerSnapshot,
        List<String> recommendations,
        String status
    ) {
        public OptimizationReport(DpsMeterFeature.BuildScore bestClass,
                                  DpsMeterFeature.BuildScore currentClass,
                                  List<DpsMeterFeature.BuildScore> classRanking,
                                  List<ItemCheck> itemChecks,
                                  List<StatTotal> statTotals,
                                  PlayerSnapshot playerSnapshot,
                                  List<String> recommendations) {
            this(bestClass, currentClass, List.copyOf(classRanking), List.copyOf(itemChecks), List.copyOf(statTotals), playerSnapshot, List.copyOf(recommendations), "OK");
        }

        public static OptimizationReport empty(String status) {
            DpsMeterFeature.BuildScore empty = new DpsMeterFeature.BuildScore("None", 0, Double.POSITIVE_INFINITY, 0);
            PlayerSnapshot snapshot = new PlayerSnapshot("", 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "", 0, 0, 0, "", "", "");
            return new OptimizationReport(empty, empty, List.of(empty), List.of(), List.of(), snapshot, List.of(status), status);
        }
    }

    private static final class MutableStatTotal {
        private final String label;
        private final String category;
        private double value;
        private int sources;

        private MutableStatTotal(String label, String category) {
            this.label = label;
            this.category = category;
        }
    }

    private static HudStatusSnapshot findDungeonRealmsHudStatus(MinecraftClient mc) {
        try {
            Object bossBarHud = findBossBarHud(mc.inGameHud);
            if (bossBarHud == null) {
                DrStandaloneMod.LOG.info("Build Optimizer: boss bar HUD not found, falling back to vanilla player stats.");
                return null;
            }

            for (Object bar : extractBossBars(bossBarHud)) {
                String text = extractBossBarText(bar);
                if (text == null || text.isBlank()) continue;

                Matcher matcher = DR_STATUS_PATTERN.matcher(text.replace('\u00A0', ' ').trim());
                if (!matcher.find()) continue;

                int level = Integer.parseInt(matcher.group(1));
                String className = matcher.group(2).trim();
                double hp = parseDouble(matcher.group(3));
                double xp = parseDouble(matcher.group(4));
                double progress = extractBossBarProgress(bar);
                if (!Double.isFinite(progress) || progress <= 0) progress = 1.0;
                DrStandaloneMod.LOG.info("Build Optimizer: parsed DR HUD status '{}'.", text);
                return new HudStatusSnapshot(level, className, hp, xp, Math.max(0d, Math.min(progress, 1d)));
            }
            DrStandaloneMod.LOG.info("Build Optimizer: no DR HUD boss bar matched expected pattern.");
        } catch (Exception ignored) {
            DrStandaloneMod.LOG.warn("Build Optimizer: failed to inspect boss bar HUD.", ignored);
        }
        return null;
    }

    private static Object findBossBarHud(Object inGameHud) throws ReflectiveOperationException {
        for (Method method : inGameHud.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType().getSimpleName().contains("BossBar")) {
                return method.invoke(inGameHud);
            }
        }

        for (Field field : inGameHud.getClass().getDeclaredFields()) {
            if (field.getType().getSimpleName().contains("BossBar")) {
                field.setAccessible(true);
                return field.get(inGameHud);
            }
        }
        return null;
    }

    private static List<Object> extractBossBars(Object bossBarHud) throws ReflectiveOperationException {
        for (Field field : bossBarHud.getClass().getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object value = field.get(bossBarHud);
                if (value instanceof Map<?, ?> map) {
                    return new ArrayList<>(map.values());
                }
            }
        }
        return List.of();
    }

    private static String extractBossBarText(Object bossBar) throws ReflectiveOperationException {
        for (Method method : bossBar.getClass().getMethods()) {
            if (method.getParameterCount() != 0) continue;
            Class<?> returnType = method.getReturnType();
            if (Text.class.isAssignableFrom(returnType) || CharSequence.class.isAssignableFrom(returnType) || Object.class.equals(returnType)) {
                Object value = method.invoke(bossBar);
                String candidate = null;
                if (value instanceof Text text) candidate = text.getString();
                else if (value instanceof CharSequence seq) candidate = seq.toString();
                if (candidate != null) {
                    candidate = candidate.replace('\u00A0', ' ').trim();
                    if (DR_STATUS_PATTERN.matcher(candidate).find()) return candidate;
                }
            }
        }

        for (Field field : bossBar.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(bossBar);
            String candidate = null;
            if (value instanceof Text text) candidate = text.getString();
            else if (value instanceof CharSequence seq) candidate = seq.toString();
            if (candidate != null) {
                candidate = candidate.replace('\u00A0', ' ').trim();
                if (DR_STATUS_PATTERN.matcher(candidate).find()) return candidate;
            }
        }
        return null;
    }

    private static double extractBossBarProgress(Object bossBar) throws ReflectiveOperationException {
        for (Method method : bossBar.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && Number.class.isAssignableFrom(boxedType(method.getReturnType()))) {
                Object value = method.invoke(bossBar);
                if (value instanceof Number number) {
                    double candidate = number.doubleValue();
                    if (candidate >= 0d && candidate <= 1.0001d) return candidate;
                }
            }
        }

        for (Field field : bossBar.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(bossBar);
            if (value instanceof Number number) {
                double candidate = number.doubleValue();
                if (candidate >= 0d && candidate <= 1.0001d) return candidate;
            }
        }
        return Double.NaN;
    }

    private static Class<?> boxedType(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private record HudStatusSnapshot(int level, String className, double health, double xpPercent, double bossProgress) {
    }
}
