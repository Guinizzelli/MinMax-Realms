package net.matte.drstandalone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DrBuildOptimizerService {
    private static final List<String> CLASS_ORDER = List.of("Warrior", "Rogue", "Paladin");
    private static final Set<String> TRUSTED_ENDPOINT_HOSTS = Set.of("api.openai.com", "openai.com");
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

        return new OptimizationReport(best, current, scores, checks, recommendations);
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
        if (stack == null || stack.isEmpty()) return new ItemCheck(slot, "(empty)", -1, 0);

        List<Text> tooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, mc.player, TooltipType.BASIC);
        DrRarityHelper.TooltipTheme rarity = DrRarityHelper.resolve(stack, tooltip);
        DrStatsDatabase.TooltipAnalysis analysis = DrStatsDatabase.get().analyze(stack, tooltip, rarity);
        if (analysis == null) return new ItemCheck(slot, stack.getName().getString(), -1, 0);

        return new ItemCheck(slot, stack.getName().getString(), analysis.averagePercent, analysis.matches.size());
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

    public record ItemCheck(String slotLabel, String itemName, double averageRollPercent, int matchedStats) {
    }

    public record OptimizationReport(
        DpsMeterFeature.BuildScore bestClass,
        DpsMeterFeature.BuildScore currentClass,
        List<DpsMeterFeature.BuildScore> classRanking,
        List<ItemCheck> itemChecks,
        List<String> recommendations,
        String status
    ) {
        public OptimizationReport(DpsMeterFeature.BuildScore bestClass,
                                  DpsMeterFeature.BuildScore currentClass,
                                  List<DpsMeterFeature.BuildScore> classRanking,
                                  List<ItemCheck> itemChecks,
                                  List<String> recommendations) {
            this(bestClass, currentClass, List.copyOf(classRanking), List.copyOf(itemChecks), List.copyOf(recommendations), "OK");
        }

        public static OptimizationReport empty(String status) {
            DpsMeterFeature.BuildScore empty = new DpsMeterFeature.BuildScore("None", 0, Double.POSITIVE_INFINITY, 0);
            return new OptimizationReport(empty, empty, List.of(empty), List.of(), List.of(status), status);
        }
    }
}
