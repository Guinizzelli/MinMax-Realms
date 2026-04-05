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
    private static int gainedSlimeballs;
    private static int gainedChests;
    private static int lastInventoryEmeralds = -1;
    private static int lastInventorySlimeballs = -1;
    private static int pendingChatEmeralds;
    private static long sessionStartMs;

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
        int inventorySlimeballs = countInventoryItem(net.minecraft.item.Items.SLIME_BALL);
        currentEmeralds = Math.max(currentEmeralds, inventoryEmeralds);

        if (lastInventoryEmeralds == -1) {
            lastInventoryEmeralds = inventoryEmeralds;
            lastInventorySlimeballs = inventorySlimeballs;
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

        int slimeDelta = inventorySlimeballs - lastInventorySlimeballs;
        if (slimeDelta > 0) gainedSlimeballs += slimeDelta;

        lastInventoryEmeralds = inventoryEmeralds;
        lastInventorySlimeballs = inventorySlimeballs;
    }

    public static void resetSession() {
        gainedEmeralds = 0;
        currentEmeralds = 0;
        gainedSlimeballs = 0;
        gainedChests = 0;
        lastInventoryEmeralds = -1;
        lastInventorySlimeballs = -1;
        pendingChatEmeralds = 0;
        sessionStartMs = System.currentTimeMillis();
    }

    private static void onIncomingMessage(String raw, boolean overlay) {
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (!config.gemMeterEnabled) return;
        if (raw == null || raw.isBlank()) return;
        if (sessionStartMs == 0) sessionStartMs = System.currentTimeMillis();

        if (matchesChestMessage(raw)) {
            gainedChests++;
        }

        if (!config.gemChatSource) return;
        if (overlay && !config.gemActionBarSource) return;

        Integer gained = parseGemGain(raw);
        if (gained == null || gained <= 0) return;

        gainedEmeralds += gained;
        pendingChatEmeralds += gained;
        currentEmeralds += gained;
    }

    private static Integer parseGemGain(String raw) {
        String normalized = raw.replace('\u00A0', ' ').trim();
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

    private static boolean matchesChestMessage(String raw) {
        String normalized = raw.replace('\u00A0', ' ').trim();
        return containsAnyConfiguredKeyword(normalized, DrStandaloneMod.config().chestChatKeywords, false);
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
        lines.add("Slime " + gainedSlimeballs + " | Chest " + gainedChests);
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

    private static int countInventoryItem(Item item) {
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) total += stack.getCount();
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
