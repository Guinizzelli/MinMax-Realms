package net.matte.drstandalone;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
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

    private static int gainedEmeralds;
    private static int currentEmeralds;
    private static int lastInventoryEmeralds = -1;
    private static long sessionStartMs;

    private GemMeterFeature() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> render(drawContext));
    }

    private static void tick() {
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (!config.gemMeterEnabled || mc.player == null) return;

        int inventoryEmeralds = countInventoryEmeralds();
        currentEmeralds = Math.max(currentEmeralds, inventoryEmeralds);

        if (lastInventoryEmeralds == -1) {
            lastInventoryEmeralds = inventoryEmeralds;
            if (sessionStartMs == 0) sessionStartMs = System.currentTimeMillis();
            return;
        }

        int delta = inventoryEmeralds - lastInventoryEmeralds;
        if (delta > 0) {
            gainedEmeralds += delta;
            currentEmeralds = inventoryEmeralds;
        }

        lastInventoryEmeralds = inventoryEmeralds;
    }

    private static void render(DrawContext context) {
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (!config.gemMeterEnabled || mc.player == null || mc.options.hudHidden) return;

        FindStats findStats = collectFindStats();
        List<String> lines = new ArrayList<>();
        lines.add("Gained: " + formatCompact(gainedEmeralds) + "G");
        lines.add("G/h: " + formatCompact(getEmeraldsPerHour()));
        lines.add("Session: " + getSessionTimeString());
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
            if (stack.isOf(Items.EMERALD)) total += stack.getCount();
            else if (stack.isOf(Items.EMERALD_BLOCK)) total += stack.getCount() * 9;
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
