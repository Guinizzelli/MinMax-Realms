package net.matte.drstandalone;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GemMeterFeature {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Pattern SINGLE_STAT_PATTERN = Pattern.compile("^([A-Z ./]+?)\\s*:\\s*\\+?(\\d+(?:\\.\\d+)?)(?:%|/s)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCHANT_BONUS_CAPTURE = Pattern.compile("\\(([+-]?\\d+(?:\\.\\d+)?)\\)");
    private static final Pattern GEM_GAIN_PATTERN = Pattern.compile("(?i)(?:\\+\\s*|gained\\s+|gain\\s+|earned\\s+|received\\s+|obtained\\s+|added\\s+)(\\d+(?:[.,]\\d+)?)\\s*(?:g|gems?|emeralds?)");
    private static final Pattern ANY_NUMBER_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)");

    private static int gainedEmeralds;
    private static int currentEmeralds;
    private static int gainedSlimes;
    private static int gainedChests;
    private static final List<Integer> customRuleCounts = new ArrayList<>();
    private static int lastInventoryEmeralds = -1;
    private static int pendingChatEmeralds;
    private static long sessionStartMs;
    private static String lastSlimeMessage = "";
    private static long lastSlimeMessageAtMs;
    private static String lastChestMessage = "";
    private static long lastChestMessageAtMs;
    private static final List<String> lastCustomRuleMessages = new ArrayList<>();
    private static final List<Long> lastCustomRuleMessageAtMs = new ArrayList<>();
    private static final long CHAT_EVENT_DEDUP_MS = 750L;

    private GemMeterFeature() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onIncomingMessage(message.getString(), overlay));
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> render(drawContext));
    }

    private static void tick() {
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (!config.gemMeterEnabled || mc.player == null) return;
        if (!config.gemInventorySource) {
            if (sessionStartMs == 0) sessionStartMs = System.currentTimeMillis();
            return;
        }

        int inventoryEmeralds = countInventoryEmeralds();
        currentEmeralds = Math.max(currentEmeralds, inventoryEmeralds);

        if (lastInventoryEmeralds == -1) {
            lastInventoryEmeralds = inventoryEmeralds;
            if (sessionStartMs == 0) sessionStartMs = System.currentTimeMillis();
            return;
        }

        int delta = inventoryEmeralds - lastInventoryEmeralds;
        if (delta > 0) {
            int unmatchedDelta = Math.max(0, delta - pendingChatEmeralds);
            gainedEmeralds += unmatchedDelta;
            currentEmeralds = inventoryEmeralds;
            pendingChatEmeralds = Math.max(0, pendingChatEmeralds - delta);
        }

        lastInventoryEmeralds = inventoryEmeralds;
    }

    public static void resetSession() {
        gainedEmeralds = 0;
        currentEmeralds = 0;
        gainedSlimes = 0;
        gainedChests = 0;
        lastInventoryEmeralds = -1;
        pendingChatEmeralds = 0;
        sessionStartMs = System.currentTimeMillis();
        lastSlimeMessage = "";
        lastSlimeMessageAtMs = 0L;
        lastChestMessage = "";
        lastChestMessageAtMs = 0L;
        ensureCustomRuleStateSize();
        for (int i = 0; i < customRuleCounts.size(); i++) {
            customRuleCounts.set(i, 0);
            lastCustomRuleMessages.set(i, "");
            lastCustomRuleMessageAtMs.set(i, 0L);
        }
    }

    private static void onIncomingMessage(String raw, boolean overlay) {
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (!config.gemMeterEnabled) return;
        if (raw == null || raw.isBlank()) return;
        if (sessionStartMs == 0) sessionStartMs = System.currentTimeMillis();

        String normalized = raw.replace('\u00A0', ' ').trim();

        if (matchesSlimeMessage(normalized) && shouldCountChatEvent(normalized, true)) {
            gainedSlimes++;
        }

        if (matchesChestMessage(normalized) && shouldCountChatEvent(normalized, false)) {
            gainedChests++;
        }

        ensureCustomRuleStateSize();
        for (int i = 0; i < config.gemCustomRules.size(); i++) {
            DrStandaloneConfig.GemCustomRule rule = config.gemCustomRules.get(i);
            if (matchesCustomRuleMessage(normalized, rule) && shouldCountCustomRuleEvent(normalized, i)) {
                customRuleCounts.set(i, customRuleCounts.get(i) + 1);
            }
        }

        if (!config.gemChatSource) return;
        if (overlay && !config.gemActionBarSource) return;

        Integer gained = parseGemGain(normalized);
        if (gained == null || gained <= 0) return;

        gainedEmeralds += gained;
        pendingChatEmeralds += gained;
        currentEmeralds += gained;
    }

    private static Integer parseGemGain(String normalized) {
        if (!containsAnyConfiguredKeyword(normalized, DrStandaloneMod.config().gemChatKeywords, true)) return null;

        Matcher matcher = GEM_GAIN_PATTERN.matcher(normalized);
        if (matcher.find()) {
            String numeric = matcher.group(1).replace(',', '.');
            return (int) Math.round(Double.parseDouble(numeric));
        }

        double max = -1;
        Matcher any = ANY_NUMBER_PATTERN.matcher(normalized);
        while (any.find()) {
            double parsed = Double.parseDouble(any.group(1).replace(',', '.'));
            if (parsed > max) max = parsed;
        }
        if (max > 0) return (int) Math.round(max);
        return null;
    }

    private static boolean matchesSlimeMessage(String normalized) {
        if (!"Chat".equalsIgnoreCase(DrStandaloneMod.config().slimeParseMode)) return false;
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (!lowered.contains("slime")) return false;

        // Only count actual reward / drop lines, not combat feedback lines that repeat the mob name.
        if (lowered.contains("fall from") || lowered.contains("dropped:") || lowered.contains("dropped ")) {
            return true;
        }

        if (lowered.contains("dodged") || lowered.contains("absorbed") || lowered.contains("blocked")
            || lowered.contains("target ") || lowered.contains("opponent ")) {
            return false;
        }

        return containsAnyConfiguredKeyword(normalized, DrStandaloneMod.config().slimeChatKeywords, false)
            && (lowered.contains("fall from") || lowered.contains("dropped"));
    }

    private static boolean matchesChestMessage(String normalized) {
        if (!"Chat".equalsIgnoreCase(DrStandaloneMod.config().chestParseMode)) return false;
        return containsAnyConfiguredKeyword(normalized, DrStandaloneMod.config().chestChatKeywords, false);
    }

    private static boolean matchesCustomRuleMessage(String normalized, DrStandaloneConfig.GemCustomRule rule) {
        return rule != null
            && "Chat".equalsIgnoreCase(rule.parseMode)
            && containsAnyConfiguredKeyword(normalized, rule.chatKeywords, false);
    }

    private static boolean shouldCountChatEvent(String normalized, boolean slimeEvent) {
        long now = System.currentTimeMillis();
        if (slimeEvent) {
            if (normalized.equalsIgnoreCase(lastSlimeMessage) && (now - lastSlimeMessageAtMs) <= CHAT_EVENT_DEDUP_MS) {
                return false;
            }
            lastSlimeMessage = normalized;
            lastSlimeMessageAtMs = now;
            return true;
        }

        if (normalized.equalsIgnoreCase(lastChestMessage) && (now - lastChestMessageAtMs) <= CHAT_EVENT_DEDUP_MS) {
            return false;
        }
        lastChestMessage = normalized;
        lastChestMessageAtMs = now;
        return true;
    }

    private static boolean shouldCountCustomRuleEvent(String normalized, int index) {
        long now = System.currentTimeMillis();
        String lastMessage = lastCustomRuleMessages.get(index);
        long lastAt = lastCustomRuleMessageAtMs.get(index);
        if (normalized.equalsIgnoreCase(lastMessage) && (now - lastAt) <= CHAT_EVENT_DEDUP_MS) {
            return false;
        }
        lastCustomRuleMessages.set(index, normalized);
        lastCustomRuleMessageAtMs.set(index, now);
        return true;
    }

    private static void ensureCustomRuleStateSize() {
        int target = DrStandaloneMod.config().gemCustomRules == null ? 0 : DrStandaloneMod.config().gemCustomRules.size();
        while (customRuleCounts.size() < target) customRuleCounts.add(0);
        while (lastCustomRuleMessages.size() < target) lastCustomRuleMessages.add("");
        while (lastCustomRuleMessageAtMs.size() < target) lastCustomRuleMessageAtMs.add(0L);
        while (customRuleCounts.size() > target) customRuleCounts.remove(customRuleCounts.size() - 1);
        while (lastCustomRuleMessages.size() > target) lastCustomRuleMessages.remove(lastCustomRuleMessages.size() - 1);
        while (lastCustomRuleMessageAtMs.size() > target) lastCustomRuleMessageAtMs.remove(lastCustomRuleMessageAtMs.size() - 1);
    }

    private static boolean containsAnyConfiguredKeyword(String raw, String keywords, boolean blankMatchesAll) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (keywords == null || keywords.isBlank()) return blankMatchesAll;

        for (String token : keywords.split(",")) {
            String keyword = token.trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty() && normalized.contains(keyword)) return true;
        }
        return false;
    }

    private static void render(DrawContext context) {
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (!config.gemMeterEnabled || mc.player == null || mc.options.hudHidden) return;

        FindStats findStats = collectFindStats();
        List<String> lines = new ArrayList<>();
        lines.add("Gained: " + formatCompact(gainedEmeralds) + "G");
        lines.add("G/h: " + formatCompact(getEmeraldsPerHour()));
        lines.add("Session: " + getSessionTimeString());
        String sideCounters = buildSideCounters(config);
        if (!sideCounters.isBlank()) {
            lines.add(sideCounters);
        }
        ensureCustomRuleStateSize();
        for (int i = 0; i < config.gemCustomRules.size(); i++) {
            DrStandaloneConfig.GemCustomRule rule = config.gemCustomRules.get(i);
            if (rule == null || "Disabled".equalsIgnoreCase(rule.parseMode)) continue;
            String title = rule.title == null || rule.title.isBlank() ? "Custom " + (i + 1) : rule.title.trim();
            lines.add(title + " " + customRuleCounts.get(i));
        }
        lines.add("GF " + formatStat(findStats.gemFind()) + "% | IF " + formatStat(findStats.itemFind()) + "% | KF " + formatStat(findStats.keyFind()) + "%");

        int padding = 6;
        int lineHeight = mc.textRenderer.fontHeight + 2;
        int width = mc.textRenderer.getWidth("Gem Meter");
        for (String line : lines) width = Math.max(width, mc.textRenderer.getWidth(line));
        width += padding * 2;
        int height = padding * 2 + lineHeight * (lines.size() + 1);

        context.getMatrices().push();
        context.getMatrices().translate(config.gemHudX, config.gemHudY, 0);
        context.getMatrices().scale((float) config.gemHudScale, (float) config.gemHudScale, 1);
        context.fill(0, 0, width, height, 0xAA0F1218);

        int textY = padding;
        context.drawText(mc.textRenderer, Text.literal("Gem Meter"), padding, textY, 0xFF7DFF78, false);
        textY += lineHeight;
        for (String line : lines) {
            context.drawText(mc.textRenderer, Text.literal(line), padding, textY, 0xFFEAEAEA, false);
            textY += lineHeight;
        }

        context.getMatrices().pop();
    }

    private static int countInventoryEmeralds() {
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.isOf(net.minecraft.item.Items.EMERALD)) total += stack.getCount();
            else if (stack.isOf(net.minecraft.item.Items.EMERALD_BLOCK)) total += stack.getCount() * 9;
        }
        return total;
    }

    private static String buildSideCounters(DrStandaloneConfig config) {
        List<String> chunks = new ArrayList<>();
        if ("Chat".equalsIgnoreCase(config.slimeParseMode)) chunks.add("Slime " + gainedSlimes);
        if ("Chat".equalsIgnoreCase(config.chestParseMode)) chunks.add("Chest " + gainedChests);
        return String.join(" | ", chunks);
    }

    private static FindStats collectFindStats() {
        double gemFind = 0;
        double itemFind = 0;
        double keyFind = 0;

        gemFind += sumFindStat(mc.player.getMainHandStack(), "GEM FIND");
        gemFind += sumFindStat(mc.player.getOffHandStack(), "GEM FIND");
        gemFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.HEAD), "GEM FIND");
        gemFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.CHEST), "GEM FIND");
        gemFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.LEGS), "GEM FIND");
        gemFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.FEET), "GEM FIND");

        itemFind += sumFindStat(mc.player.getMainHandStack(), "ITEM FIND");
        itemFind += sumFindStat(mc.player.getOffHandStack(), "ITEM FIND");
        itemFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.HEAD), "ITEM FIND");
        itemFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.CHEST), "ITEM FIND");
        itemFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.LEGS), "ITEM FIND");
        itemFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.FEET), "ITEM FIND");

        keyFind += sumFindStat(mc.player.getMainHandStack(), "KEY FIND");
        keyFind += sumFindStat(mc.player.getOffHandStack(), "KEY FIND");
        keyFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.HEAD), "KEY FIND");
        keyFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.CHEST), "KEY FIND");
        keyFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.LEGS), "KEY FIND");
        keyFind += sumFindStat(mc.player.getEquippedStack(EquipmentSlot.FEET), "KEY FIND");

        return new FindStats(gemFind, itemFind, keyFind);
    }

    private static double sumFindStat(ItemStack stack, String targetLabel) {
        if (stack.isEmpty() || mc.player == null) return 0;

        double total = 0;
        List<Text> tooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, mc.player, TooltipType.BASIC);
        for (Text line : tooltip) {
            String raw = line.getString();
            String sanitized = sanitizeTooltipLine(raw);
            Matcher matcher = SINGLE_STAT_PATTERN.matcher(sanitized);
            if (!matcher.matches()) continue;
            String label = normalizeStatLabel(matcher.group(1));
            if (!targetLabel.equals(label)) continue;
            total += parseDouble(matcher.group(2)) + extractEnchantBonus(raw);
        }
        return total;
    }

    private static String normalizeStatLabel(String label) {
        String normalized = label == null ? "" : label.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "GEM FIND" -> "GEM FIND";
            case "ITEM FIND" -> "ITEM FIND";
            case "KEY FIND" -> "KEY FIND";
            default -> normalized;
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

    private static double extractEnchantBonus(String value) {
        Matcher matcher = ENCHANT_BONUS_CAPTURE.matcher(value);
        if (matcher.find()) {
            return parseDouble(matcher.group(1));
        }
        return 0d;
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value.replace(',', '.'));
    }

    private static String formatStat(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001d) {
            return Integer.toString((int) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static double getEmeraldsPerHour() {
        long elapsed = sessionStartMs == 0 ? 0 : Math.max(0, System.currentTimeMillis() - sessionStartMs);
        if (elapsed <= 0) return 0;
        return gainedEmeralds * 3_600_000d / elapsed;
    }

    private static String getSessionTimeString() {
        long elapsed = sessionStartMs == 0 ? 0 : Math.max(0, System.currentTimeMillis() - sessionStartMs);
        long totalSeconds = elapsed / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds);
        return String.format(Locale.ROOT, "%02dm %02ds", minutes, seconds);
    }

    private static String formatCompact(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fb", value / 1_000_000_000d);
        if (abs >= 1_000_000) return String.format(Locale.ROOT, "%.1fm", value / 1_000_000d);
        if (abs >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000d);
        return Integer.toString((int) Math.round(value));
    }

    private record FindStats(double gemFind, double itemFind, double keyFind) {
    }
}
