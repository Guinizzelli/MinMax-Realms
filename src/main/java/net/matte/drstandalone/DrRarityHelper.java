package net.matte.drstandalone;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import java.util.List;
import java.util.Locale;

public final class DrRarityHelper {
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

        return null;
    }

    public enum TooltipTheme {
        Common,
        Uncommon,
        Rare,
        Epic,
        Legendary,
        Mythic
    }
}
