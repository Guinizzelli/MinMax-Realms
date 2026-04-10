package net.matte.drstandalone;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrRarityHelper {
    private static final String TRANSMUTED_TOKEN = "TRANSMUTED";
    private static final Pattern UPGRADE_MARKER_PATTERN = Pattern.compile("\\[(\\+\\d+)\\]|\\((\\+\\d+)\\)");
    private static final Identifier COMMON_TEXTURE = Identifier.ofVanilla("common");
    private static final Identifier UNCOMMON_TEXTURE = Identifier.ofVanilla("uncommon");
    private static final Identifier RARE_TEXTURE = Identifier.ofVanilla("rare");
    private static final Identifier EPIC_TEXTURE = Identifier.ofVanilla("epic");
    private static final Identifier LEGENDARY_TEXTURE = Identifier.ofVanilla("legendary");
    private static final Identifier MYTHIC_TEXTURE = Identifier.ofVanilla("mythic");

    private static final ThreadLocal<Identifier> PENDING_TEXTURE = new ThreadLocal<>();

    private DrRarityHelper() {
    }

    public static TooltipTheme resolve(ItemStack stack, List<Text> tooltip) {
        TooltipTheme transmutedBase = findTransmutedBaseTheme(tooltip);
        if (transmutedBase != null) return transmutedBase;

        for (Text line : tooltip) {
            TooltipTheme theme = detectTheme(line.getString());
            if (theme != null) return theme;
        }

        return resolveFromNameColor(stack.getName().getStyle());
    }

    public static boolean isTransmuted(List<Text> tooltip) {
        for (Text line : tooltip) {
            String text = line.getString().trim().toUpperCase(Locale.ROOT);
            if (text.contains(TRANSMUTED_TOKEN)) return true;
        }
        return false;
    }

    public static @Nullable TooltipTheme resolveUpgradeMarkerTheme(ItemStack stack) {
        return stack == null ? null : resolveUpgradeMarkerTheme(stack.getName());
    }

    public static @Nullable TooltipTheme resolveUpgradeMarkerTheme(Text text) {
        if (text == null) return null;

        final TooltipTheme[] found = new TooltipTheme[1];
        text.visit((style, part) -> {
            if (found[0] != null || part == null || part.isBlank()) return Optional.empty();
            Matcher matcher = UPGRADE_MARKER_PATTERN.matcher(part);
            if (matcher.find()) {
                found[0] = resolveThemeFromStyle(style);
            }
            return Optional.empty();
        }, text.getStyle());

        if (found[0] != null) return found[0];

        String raw = text.getString();
        if (raw != null && UPGRADE_MARKER_PATTERN.matcher(raw).find()) {
            return resolveThemeFromStyle(text.getStyle());
        }
        return null;
    }

    public static void queue(ItemStack stack, List<Text> tooltip) {
        TooltipTheme theme = resolve(stack, tooltip);
        if (theme == null) PENDING_TEXTURE.remove();
        else PENDING_TEXTURE.set(theme.texture);
    }

    public static @Nullable Identifier apply(@Nullable Identifier original) {
        Identifier texture = PENDING_TEXTURE.get();
        return texture != null ? texture : original;
    }

    public static void clear() {
        PENDING_TEXTURE.remove();
    }

    public static @Nullable TooltipTheme resolveThemeFromStyle(Style style) {
        Integer rgb = style.getColor() != null ? style.getColor().getRgb() & 0xFFFFFF : null;
        if (rgb == null) return null;

        if (matches(rgb, Formatting.LIGHT_PURPLE, Formatting.DARK_PURPLE)) return TooltipTheme.Epic;
        if (matches(rgb, Formatting.GOLD, Formatting.YELLOW)) return TooltipTheme.Legendary;
        if (matches(rgb, Formatting.GREEN, Formatting.DARK_GREEN)) return TooltipTheme.Uncommon;
        if (matches(rgb, Formatting.AQUA, Formatting.BLUE, Formatting.DARK_AQUA, Formatting.DARK_BLUE)) return TooltipTheme.Rare;
        if (matches(rgb, Formatting.WHITE, Formatting.GRAY, Formatting.DARK_GRAY)) return TooltipTheme.Common;
        return nearestTheme(rgb);
    }

    private static @Nullable TooltipTheme resolveFromNameColor(Style style) {
        return resolveThemeFromStyle(style);
    }

    private static @Nullable TooltipTheme findTransmutedBaseTheme(List<Text> tooltip) {
        for (Text line : tooltip) {
            String text = line.getString().trim().toUpperCase(Locale.ROOT);
            if (!text.contains(TRANSMUTED_TOKEN)) continue;

            TooltipTheme embedded = detectTheme(text.replace(TRANSMUTED_TOKEN, "").trim());
            if (embedded != null) return embedded;
        }

        return null;
    }

    private static @Nullable TooltipTheme detectTheme(String rawText) {
        String text = rawText == null ? "" : rawText.trim().toUpperCase(Locale.ROOT);
        if (text.equals("COMMON") || text.contains(" COMMON")) return TooltipTheme.Common;
        if (text.equals("UNCOMMON") || text.contains(" UNCOMMON")) return TooltipTheme.Uncommon;
        if (text.equals("RARE") || text.contains(" RARE")) return TooltipTheme.Rare;
        if (text.equals("EPIC") || text.contains(" EPIC")) return TooltipTheme.Epic;
        if (text.equals("LEGENDARY") || text.contains(" LEGENDARY")) return TooltipTheme.Legendary;
        if (text.equals("MYTHIC") || text.contains(" MYTHIC")) return TooltipTheme.Mythic;
        return null;
    }

    private static boolean matches(int rgb, Formatting... formats) {
        for (Formatting formatting : formats) {
            Integer value = formatting.getColorValue();
            if (value != null && (value & 0xFFFFFF) == rgb) return true;
        }
        return false;
    }

    private static @Nullable TooltipTheme nearestTheme(int rgb) {
        TooltipTheme bestTheme = null;
        int bestDistance = Integer.MAX_VALUE;
        bestTheme = considerTheme(rgb, TooltipTheme.Common, bestTheme, bestDistance, Formatting.WHITE, Formatting.GRAY);
        bestDistance = bestTheme == null ? Integer.MAX_VALUE : colorDistance(rgb, representativeColor(bestTheme));
        TooltipTheme uncommon = considerTheme(rgb, TooltipTheme.Uncommon, bestTheme, bestDistance, Formatting.GREEN, Formatting.DARK_GREEN);
        if (uncommon != bestTheme) {
            bestTheme = uncommon;
            bestDistance = colorDistance(rgb, representativeColor(bestTheme));
        }
        TooltipTheme rare = considerTheme(rgb, TooltipTheme.Rare, bestTheme, bestDistance, Formatting.AQUA, Formatting.BLUE, Formatting.DARK_AQUA, Formatting.DARK_BLUE);
        if (rare != bestTheme) {
            bestTheme = rare;
            bestDistance = colorDistance(rgb, representativeColor(bestTheme));
        }
        TooltipTheme epic = considerTheme(rgb, TooltipTheme.Epic, bestTheme, bestDistance, Formatting.LIGHT_PURPLE, Formatting.DARK_PURPLE);
        if (epic != bestTheme) {
            bestTheme = epic;
            bestDistance = colorDistance(rgb, representativeColor(bestTheme));
        }
        TooltipTheme legendary = considerTheme(rgb, TooltipTheme.Legendary, bestTheme, bestDistance, Formatting.GOLD, Formatting.YELLOW);
        if (legendary != bestTheme) {
            bestTheme = legendary;
            bestDistance = colorDistance(rgb, representativeColor(bestTheme));
        }
        TooltipTheme mythic = considerTheme(rgb, TooltipTheme.Mythic, bestTheme, bestDistance, Formatting.RED, Formatting.DARK_RED);
        if (mythic != bestTheme) {
            bestTheme = mythic;
            bestDistance = colorDistance(rgb, representativeColor(bestTheme));
        }
        return bestDistance <= 160000 ? bestTheme : null;
    }

    private static TooltipTheme considerTheme(int rgb, TooltipTheme candidate, @Nullable TooltipTheme currentBest, int currentDistance, Formatting... formats) {
        int candidateDistance = minDistance(rgb, formats);
        return candidateDistance < currentDistance ? candidate : currentBest;
    }

    private static int minDistance(int rgb, Formatting... formats) {
        int best = Integer.MAX_VALUE;
        for (Formatting formatting : formats) {
            Integer value = formatting.getColorValue();
            if (value == null) continue;
            best = Math.min(best, colorDistance(rgb, value & 0xFFFFFF));
        }
        return best;
    }

    private static int representativeColor(TooltipTheme theme) {
        return switch (theme) {
            case Common -> Formatting.WHITE.getColorValue() & 0xFFFFFF;
            case Uncommon -> Formatting.GREEN.getColorValue() & 0xFFFFFF;
            case Rare -> Formatting.AQUA.getColorValue() & 0xFFFFFF;
            case Epic -> Formatting.LIGHT_PURPLE.getColorValue() & 0xFFFFFF;
            case Legendary -> Formatting.GOLD.getColorValue() & 0xFFFFFF;
            case Mythic -> Formatting.RED.getColorValue() & 0xFFFFFF;
        };
    }

    private static int colorDistance(int left, int right) {
        int lr = (left >> 16) & 0xFF;
        int lg = (left >> 8) & 0xFF;
        int lb = left & 0xFF;
        int rr = (right >> 16) & 0xFF;
        int rg = (right >> 8) & 0xFF;
        int rb = right & 0xFF;
        int dr = lr - rr;
        int dg = lg - rg;
        int db = lb - rb;
        return (dr * dr) + (dg * dg) + (db * db);
    }

    public enum TooltipTheme {
        Common(COMMON_TEXTURE),
        Uncommon(UNCOMMON_TEXTURE),
        Rare(RARE_TEXTURE),
        Epic(EPIC_TEXTURE),
        Legendary(LEGENDARY_TEXTURE),
        Mythic(MYTHIC_TEXTURE);

        public final Identifier texture;

        TooltipTheme(Identifier texture) {
            this.texture = texture;
        }
    }
}
