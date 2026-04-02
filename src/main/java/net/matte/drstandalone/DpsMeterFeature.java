package net.matte.drstandalone;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DpsMeterFeature {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static final double[] AVG_MOB_HP = {85, 250, 700, 1750, 3800};
    private static final double[] AVG_MOB_ARMOR = {4, 10, 18, 28, 40};
    private static final double[] AVG_MOB_DODGE = {2, 4, 6, 8, 10};
    private static final double[] AVG_MOB_BLOCK = {1, 3, 5, 7, 9};

    private static final Pattern DMG_PATTERN = Pattern.compile("^DMG\\s*:\\s*\\+?(\\d+(?:\\.\\d+)?)\\s*-\\s*(\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_STAT_PATTERN = Pattern.compile("^([A-Z ./]+?)\\s*:\\s*\\+?(\\d+(?:\\.\\d+)?)(?:%|/s)?$", Pattern.CASE_INSENSITIVE);

    private DpsMeterFeature() {
    }

    public static void init() {
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> render(drawContext));
    }

    private static void render(DrawContext context) {
        DrStandaloneConfig config = DrStandaloneMod.config();
        if (!config.dpsMeterEnabled || mc.player == null || mc.options.hudHidden) return;

        Simulation sim = simulate(config);
        double scale = config.dpsHudScale;
        int width = sim == null ? 180 : 320;
        int height = sim == null ? 50 : 122;
        int x = config.dpsHudX;
        int y = config.dpsHudY;

        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        context.getMatrices().scale((float) scale, (float) scale, 1);
        drawPanel(context, width, height);

        if (sim == null) {
            drawCentered(context, "DPS Meter", width, 10, 0xFFE5983A);
            drawCentered(context, "Hold a DR weapon", width, 24, 0xFFFFFFFF);
            drawCentered(context, "to simulate DPS", width, 36, 0xFFFFFFFF);
        } else {
            drawSimulation(context, sim, width);
        }

        context.getMatrices().pop();
    }

    private static void drawPanel(DrawContext context, int width, int height) {
        int bg = 0xB40F1218;
        int border = 0xFF333333;
        int inner = 0xFF4A4A4A;
        int corner = 0xFF554433;
        int cornerBorder = 0xFF2A1F1A;

        context.fill(0, 0, width, height, bg);
        context.drawBorder(0, 0, width, height, border);
        context.drawBorder(2, 2, width - 4, height - 4, inner);
        drawCorner(context, -2, -2, corner, cornerBorder);
        drawCorner(context, width - 10, -2, corner, cornerBorder);
        drawCorner(context, -2, height - 10, corner, cornerBorder);
        drawCorner(context, width - 10, height - 10, corner, cornerBorder);
    }

    private static void drawCorner(DrawContext context, int x, int y, int fill, int border) {
        context.fill(x, y, x + 10, y + 10, fill);
        context.drawBorder(x, y, 10, 10, border);
    }

    private static void drawCentered(DrawContext context, String text, int width, int y, int color) {
        int x = Math.max(8, (width - mc.textRenderer.getWidth(text)) / 2);
        context.drawText(mc.textRenderer, Text.literal(text), x, y, color, false);
    }

    private static void drawSimulation(DrawContext context, Simulation sim, int width) {
        int dpsColor = 0xFFA4D037;
        int damageColor = 0xFFE5983A;
        int apsColor = 0xFF3FC1C9;
        int ttkColor = 0xFFE5B73E;
        int subColor = 0xFFE0E0E0;

        context.drawText(mc.textRenderer, Text.literal("DPS: " + format(sim.dps)), 8, 8, dpsColor, false);
        context.drawText(mc.textRenderer, Text.literal("Damage: " + sim.damageRange), 8, 22, damageColor, false);

        String apsText = "APS: " + format(sim.aps);
        String ttkText = "TTK: " + formatSeconds(sim.ttk);
        context.drawText(mc.textRenderer, Text.literal(apsText), width - 8 - mc.textRenderer.getWidth(apsText), 8, apsColor, false);
        context.drawText(mc.textRenderer, Text.literal(ttkText), width - 8 - mc.textRenderer.getWidth(ttkText), 22, ttkColor, false);

        context.drawText(mc.textRenderer, Text.literal(sim.weaponName).formatted(sim.weaponFormatting), 8, 46, 0xFFFFFFFF, false);
        context.drawText(mc.textRenderer, Text.literal("Class: " + sim.classProfile + "   Tier: " + sim.targetTier + "   HP: " + format(sim.targetHp) + "%"), 8, 60, subColor, false);
        context.fill(8, 75, width - 8, 76, 0xFF666666);

        context.drawText(mc.textRenderer, Text.literal("Acc " + format(sim.accuracy) + " | Pier " + format(sim.piercing) + " | Shatter " + format(sim.shatter) + "%"), 8, 82, 0xFF8EE26B, false);
        context.drawText(mc.textRenderer, Text.literal("Exec " + format(sim.execute) + "% | Crush " + format(sim.crushing) + "% | Crit " + format(sim.critChance) + "% x" + format(sim.critMult)), 8, 94, 0xFFFFC857, false);
        context.drawText(mc.textRenderer, Text.literal("STR " + format(sim.str) + " | DEX " + format(sim.dex) + " | VIT " + format(sim.vit) + " | INT " + format(sim.intelligence)), 8, 106, 0xFFD7D7D7, false);
    }

    private static @Nullable Simulation simulate(DrStandaloneConfig config) {
        if (mc.player == null) return null;

        ItemStack weaponStack = mc.player.getMainHandStack();
        if (weaponStack.isEmpty()) return null;
        WeaponKind kind = WeaponKind.from(weaponStack.getItem());
        if (kind == null) return null;

        Stats stats = new Stats();
        merge(stats, parseStats(getTooltipLines(weaponStack), true));
        merge(stats, parseStats(getTooltipLines(mc.player.getEquippedStack(EquipmentSlot.FEET)), false));
        merge(stats, parseStats(getTooltipLines(mc.player.getEquippedStack(EquipmentSlot.LEGS)), false));
        merge(stats, parseStats(getTooltipLines(mc.player.getEquippedStack(EquipmentSlot.CHEST)), false));
        merge(stats, parseStats(getTooltipLines(mc.player.getEquippedStack(EquipmentSlot.HEAD)), false));
        merge(stats, parseStats(getTooltipLines(mc.player.getOffHandStack()), false));

        if (stats.avgDamage <= 0) return null;

        ClassProfile profile = ClassProfile.valueOf(config.classProfile);
        TargetTier tier = TargetTier.valueOf(config.targetTier);
        double hpPct = Math.max(0, Math.min(1, config.targetHpPercent / 100d));

        double enemyArmor = AVG_MOB_ARMOR[tier.ordinal()] * config.mobArmorScale;
        double enemyHp = AVG_MOB_HP[tier.ordinal()] * config.mobHealthScale;
        double enemyDodge = AVG_MOB_DODGE[tier.ordinal()] * config.mobAvoidanceScale;
        double enemyBlock = AVG_MOB_BLOCK[tier.ordinal()] * config.mobAvoidanceScale;

        applyClassRules(stats, profile, kind, tier);

        double effectiveAccuracy = stats.accuracy;
        double effectivePiercing = stats.piercing;
        if (profile == ClassProfile.Rogue) effectivePiercing += effectiveAccuracy * 0.5;

        double avoid = Math.max(0, Math.min(0.95, (enemyDodge + enemyBlock - effectiveAccuracy) / 100d));
        double hitChance = 1 - avoid;

        double armorAfterPiercing = Math.max(0, enemyArmor - effectivePiercing);
        double armorMitigation = armorReduction(armorAfterPiercing);
        double shatterChance = clamp(stats.shatter / 100d, 0, 1);
        double expectedArmorMultiplier = ((1 - shatterChance) * (1 - armorMitigation)) + shatterChance;

        double weaponPrimaryBonus = 1;
        if (kind == WeaponKind.Axe) weaponPrimaryBonus += stats.str * 0.0002;
        if (kind == WeaponKind.Sword) weaponPrimaryBonus += stats.dex * 0.0002;
        if (kind == WeaponKind.Mace) weaponPrimaryBonus += stats.vit * 0.0002;

        double physicalBase = stats.avgDamage * weaponPrimaryBonus;
        double mobMultiplier = 1 + (stats.vsMonsters / 100d);
        double phaseMultiplier = executeMultiplier(stats.execute, hpPct) * crushingMultiplier(stats.crushing, hpPct);

        double critChance = clamp((stats.critChance + (stats.str * 0.003514286)) / 100d, 0, 1);
        double critBonus = config.baseCritBonus + (stats.dex * config.dexCritMultiplierPerPoint) + (profile == ClassProfile.Rogue ? 0.25 : 0);
        double critMultiplier = 1 + (critChance * critBonus);

        double physicalPerHit = physicalBase * mobMultiplier * phaseMultiplier * expectedArmorMultiplier * critMultiplier;
        double elementalPerHit = (stats.fireDamage + stats.iceDamage + stats.poisonDamage) * (1 - clamp(config.elementalReduction, 0, 0.95));
        double purePerHit = stats.pureDamage;
        double totalPerHit = (physicalPerHit + elementalPerHit + purePerHit) * hitChance;

        double regenPerSecond = config.basePassiveEnergyRegen + stats.energyRegen;
        regenPerSecond *= 1 + (stats.vit * 0.00006d);
        double aps = Math.min(config.practicalAttackCap, regenPerSecond / kind.energyCost);
        if (aps <= 0) aps = config.attackSpeed;

        double dps = totalPerHit * aps;
        double ttk = dps > 0 ? enemyHp / dps : Double.POSITIVE_INFINITY;

        DrRarityHelper.TooltipTheme rarity = DrRarityHelper.resolve(weaponStack, weaponStack.getTooltip(Item.TooltipContext.DEFAULT, mc.player, TooltipType.BASIC));

        return new Simulation(
            weaponStack.getName().getString(),
            formattingForRarity(rarity),
            config.classProfile,
            config.targetTier,
            config.targetHpPercent,
            format(stats.avgDamage) + " avg",
            dps,
            aps,
            ttk,
            effectiveAccuracy,
            effectivePiercing,
            stats.shatter,
            stats.execute,
            stats.crushing,
            critChance * 100,
            critBonus,
            stats.str,
            stats.dex,
            stats.vit,
            stats.intelligence
        );
    }

    private static void applyClassRules(Stats stats, ClassProfile profile, WeaponKind kind, TargetTier tier) {
        switch (profile) {
            case Warrior -> {
                stats.fireDamage += tier.classDamage;
                if (kind == WeaponKind.Axe) stats.shatter += 5;
            }
            case Rogue -> {
                stats.poisonDamage += tier.classDamage;
                if (kind == WeaponKind.Sword) stats.execute += 5;
                stats.accuracy += 10;
            }
            case Paladin -> {
                stats.pureDamage += tier.classDamage;
                stats.vsMonsters += 5;
                stats.pureDamage *= 1.10;
                if (kind == WeaponKind.Mace) stats.crushing += 5;
            }
            case None -> {
            }
        }
    }

    private static List<String> getTooltipLines(ItemStack stack) {
        if (stack.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        for (Text line : stack.getTooltip(Item.TooltipContext.DEFAULT, mc.player, TooltipType.BASIC)) {
            String text = sanitizeTooltipLine(line.getString());
            if (!text.isBlank()) lines.add(text);
        }
        return lines;
    }

    private static Stats parseStats(List<String> tooltip, boolean allowBaseDamage) {
        Stats stats = new Stats();
        for (String line : tooltip) {
            if (allowBaseDamage) {
                Matcher dmg = DMG_PATTERN.matcher(line);
                if (dmg.matches()) {
                    stats.avgDamage = (parseDouble(dmg.group(1)) + parseDouble(dmg.group(2))) / 2d;
                    continue;
                }
            }

            Matcher single = SINGLE_STAT_PATTERN.matcher(line);
            if (!single.matches()) continue;
            String key = single.group(1).trim().toUpperCase(Locale.ROOT);
            double value = parseDouble(single.group(2));

            switch (key) {
                case "VS. MONSTERS" -> stats.vsMonsters += value;
                case "CRITICAL HIT" -> stats.critChance += value;
                case "PURE DMG" -> stats.pureDamage += value;
                case "FIRE DMG" -> stats.fireDamage += value;
                case "ICE DMG" -> stats.iceDamage += value;
                case "POISON DMG" -> stats.poisonDamage += value;
                case "PIERCING" -> stats.piercing += value;
                case "ACCURACY" -> stats.accuracy += value;
                case "SHATTER" -> stats.shatter += value;
                case "EXECUTE" -> stats.execute += value;
                case "CRUSHING" -> stats.crushing += value;
                case "STR", "STRENGTH" -> stats.str += value;
                case "DEX", "DEXTERITY" -> stats.dex += value;
                case "VIT", "VITALITY" -> stats.vit += value;
                case "INT", "INTELLECT", "INTELLIGENCE" -> stats.intelligence += value;
                case "ENERGY REGEN", "ENERGY/S", "ENERGY REGEN/S" -> stats.energyRegen += value;
            }
        }
        return stats;
    }

    private static void merge(Stats into, Stats from) {
        into.avgDamage += from.avgDamage;
        into.vsMonsters += from.vsMonsters;
        into.critChance += from.critChance;
        into.pureDamage += from.pureDamage;
        into.fireDamage += from.fireDamage;
        into.iceDamage += from.iceDamage;
        into.poisonDamage += from.poisonDamage;
        into.piercing += from.piercing;
        into.accuracy += from.accuracy;
        into.shatter += from.shatter;
        into.execute += from.execute;
        into.crushing += from.crushing;
        into.str += from.str;
        into.dex += from.dex;
        into.vit += from.vit;
        into.intelligence += from.intelligence;
        into.energyRegen += from.energyRegen;
    }

    private static String sanitizeTooltipLine(String value) {
        return value.replace('\u00A0', ' ')
            .replaceAll("\\s+\\[(?:MIN|MAX|OVERCAP|Overcap|\\d+(?:\\.\\d+)?%|\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?)?)\\]\\s*$", "")
            .trim();
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value.replace(',', '.'));
    }

    private static double armorReduction(double armor) {
        return armor / (armor + 100d);
    }

    private static double executeMultiplier(double execute, double hpPct) {
        if (hpPct >= 0.5) return 1;
        double depth = (0.5 - hpPct) / 0.5;
        return 1 + (execute / 100d) * depth;
    }

    private static double crushingMultiplier(double crushing, double hpPct) {
        if (hpPct <= 0.5) return 1;
        double depth = (hpPct - 0.5) / 0.5;
        return 1 + (crushing / 100d) * depth;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatSeconds(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1fs", value) : "∞";
    }

    private static Formatting formattingForRarity(@Nullable DrRarityHelper.TooltipTheme rarity) {
        if (rarity == null) return Formatting.WHITE;
        return switch (rarity) {
            case Common -> Formatting.WHITE;
            case Uncommon -> Formatting.GREEN;
            case Rare -> Formatting.AQUA;
            case Epic -> Formatting.LIGHT_PURPLE;
            case Legendary -> Formatting.YELLOW;
            case Mythic -> Formatting.GOLD;
        };
    }

    private enum ClassProfile {
        None,
        Warrior,
        Rogue,
        Paladin
    }

    private enum TargetTier {
        T1(2), T2(4), T3(8), T4(16), T5(32);
        final int classDamage;
        TargetTier(int classDamage) { this.classDamage = classDamage; }
    }

    private enum WeaponKind {
        Sword(8.0), Scythe(8.4), Axe(8.8), Mace(9.2), Bow(9.6);
        final double energyCost;
        WeaponKind(double energyCost) { this.energyCost = energyCost; }
        static @Nullable WeaponKind from(Item item) {
            String path = Registries.ITEM.getId(item).getPath();
            if (path.endsWith("_sword")) return Sword;
            if (path.endsWith("_hoe")) return Scythe;
            if (path.endsWith("_axe")) return Axe;
            if (path.endsWith("_shovel")) return Mace;
            if (path.equals("bow") || path.equals("crossbow")) return Bow;
            return null;
        }
    }

    private static final class Stats {
        double avgDamage;
        double vsMonsters;
        double critChance;
        double pureDamage;
        double fireDamage;
        double iceDamage;
        double poisonDamage;
        double piercing;
        double accuracy;
        double shatter;
        double execute;
        double crushing;
        double str;
        double dex;
        double vit;
        double intelligence;
        double energyRegen;
    }

    private record Simulation(
        String weaponName,
        Formatting weaponFormatting,
        String classProfile,
        String targetTier,
        double targetHp,
        String damageRange,
        double dps,
        double aps,
        double ttk,
        double accuracy,
        double piercing,
        double shatter,
        double execute,
        double crushing,
        double critChance,
        double critMult,
        double str,
        double dex,
        double vit,
        double intelligence
    ) {
    }
}
