package net.matte.drstandalone;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class DrRarityHelper {
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
        for (Text line : tooltip) {
            String text = line.getString().trim().toUpperCase(Locale.ROOT);
            if (text.equals("COMMON")) return TooltipTheme.Common;
            if (text.equals("UNCOMMON")) return TooltipTheme.Uncommon;
            if (text.equals("RARE")) return TooltipTheme.Rare;
            if (text.equals("EPIC")) return TooltipTheme.Epic;
            if (text.equals("LEGENDARY")) return TooltipTheme.Legendary;
            if (text.equals("MYTHIC")) return TooltipTheme.Mythic;
        }

        return resolveFromNameColor(stack.getName().getStyle());
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

    private static @Nullable TooltipTheme resolveFromNameColor(Style style) {
        Integer rgb = style.getColor() != null ? style.getColor().getRgb() & 0xFFFFFF : null;
        if (rgb == null) return null;

        if (matches(rgb, Formatting.LIGHT_PURPLE, Formatting.DARK_PURPLE)) return TooltipTheme.Epic;
        if (matches(rgb, Formatting.GOLD, Formatting.YELLOW)) return TooltipTheme.Legendary;
        if (matches(rgb, Formatting.GREEN, Formatting.DARK_GREEN)) return TooltipTheme.Uncommon;
        if (matches(rgb, Formatting.AQUA, Formatting.BLUE, Formatting.DARK_AQUA, Formatting.DARK_BLUE)) return TooltipTheme.Rare;
        if (matches(rgb, Formatting.WHITE, Formatting.GRAY)) return TooltipTheme.Common;
        return null;
    }

    private static boolean matches(int rgb, Formatting... formats) {
        for (Formatting formatting : formats) {
            Integer value = formatting.getColorValue();
            if (value != null && (value & 0xFFFFFF) == rgb) return true;
        }
        return false;
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
