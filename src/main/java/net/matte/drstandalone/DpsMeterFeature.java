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
    public static final int PREVIEW_WIDTH = 328;
    public static final int PREVIEW_HEIGHT = 138;

    private static final double[] AVG_MOB_HP = {100, 85, 250, 700, 1750, 3800};
    // Median armor point estimate per tier based on 4 armor pieces (shield excluded),
    // using the mid non-mythic generic armor ranges as the simulated mob baseline.
    private static final double[] AVG_MOB_ARMOR = {0, 17, 25, 37, 50, 66};
    private static final double[] AVG_MOB_DODGE = {0, 2, 4, 6, 8, 10};
    private static final double[] AVG_MOB_BLOCK = {0, 1, 3, 5, 7, 9};

    private static final Pattern DMG_PATTERN = Pattern.compile("^DMG\\s*:\\s*\\+?(\\d+(?:\\.\\d+)?)\\s*-\\s*(\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_STAT_PATTERN = Pattern.compile("^([A-Z ./]+?)\\s*:\\s*\\+?(\\d+(?:\\.\\d+)?)(?:%|/s)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSIVE_SINGLE_STAT_PATTERN = Pattern.compile("^PASSIVE\\s*:\\s*([A-Z ./]+?)\\s*:\\s*\\+?(\\d+(?:\\.\\d+)?)(?:%|/s)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL_LINE = Pattern.compile(".*LEVEL\\s*:\\s*(\\d+).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCHANT_BONUS_SUFFIX = Pattern.compile("\\s*\\(\\s*[+-]?\\d+(?:\\.\\d+)?\\s*\\)\\s*$");
    private static final Pattern ENCHANT_BONUS_CAPTURE = Pattern.compile("\\(\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*\\)");
    private static final Pattern LOOSE_NUMBER_CAPTURE = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");
    private static final double PRIMARY_WEAPON_BONUS_PER_POINT = 0.0002d;
    private static final double STRENGTH_CRIT_CHANCE_PER_POINT = 0.005d;
    private static final double VITALITY_ENERGY_RECOVERY_PER_POINT = 0.00006d;

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
        int width = sim == null ? 180 : 328;
        int height = sim == null ? 50 : 138;
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

    public static void renderPreview(DrawContext context, int x, int y, double scale) {
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        context.getMatrices().scale((float) scale, (float) scale, 1);
        drawPanel(context, PREVIEW_WIDTH, PREVIEW_HEIGHT);

        int white = 0xFFE6E6E6;
        drawCentered(context, "DPS Meter Preview", PREVIEW_WIDTH, 10, 0xFFE5983A);
        context.drawText(mc.textRenderer, Text.literal("Weapon: Rogue Training Dagger"), 14, 26, 0xFFFFFFFF, false);
        context.drawText(mc.textRenderer, Text.literal("Session DPS: 1432.8"), 14, 40, 0xFFA4D037, false);
        context.drawText(mc.textRenderer, Text.literal("Target: T3  HP 100.0%"), 14, 54, white, false);
        context.fill(14, 70, PREVIEW_WIDTH - 14, 71, 0xFF666666);
        drawTwoCols(context, 14, 80,
            chip("Drag this HUD to place it", 0xFF5D8BFF),
            chip("Scale stays in config", 0xFFE5B73E)
        );
        drawTwoCols(context, 14, 96,
            chip("ESC or Return = back", white),
            chip("No live combat needed", 0xFF9BE370)
        );

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
        int sessionColor = 0xFFE5B73E;
        int white = 0xFFE6E6E6;

        context.drawText(mc.textRenderer, Text.literal(sim.weaponName).formatted(sim.weaponFormatting), 14, 10, 0xFFFFFFFF, false);

        context.drawText(mc.textRenderer, Text.literal("✦ DPS: " + format(sim.dps)), 14, 26, dpsColor, false);
        context.drawText(mc.textRenderer, Text.literal("※ Damage: " + sim.damageRange), 86, 26, damageColor, false);

        String apsText = "➤ APS: " + format(sim.aps);
        context.drawText(mc.textRenderer, Text.literal(sim.sessionLabel), width - 14 - mc.textRenderer.getWidth(sim.sessionLabel), 26, sessionColor, false);
        context.drawText(mc.textRenderer, Text.literal(apsText), width - 14 - mc.textRenderer.getWidth(apsText), 40, apsColor, false);

        context.drawText(mc.textRenderer, Text.literal("⚑ " + sim.classProfile + " ☠ " + sim.targetTier + " ❤ " + format(sim.targetHp) + "%"), 14, 40, white, false);
        context.fill(14, 56, width - 14, 57, 0xFF666666);

        drawThreeCols(context, 14, 66,
            chip("◉ Accuracy: " + format(sim.accuracy), 0xFFB6E63E),
            chip("➤ Piercing: " + format(sim.piercing), 0xFF50D6F2),
            chip("☀ Shatter: " + format(sim.shatter) + "%", 0xFFE7A341)
        );
        drawFourCols(context, 14, 80,
            chip("✦ Execute: " + format(sim.execute) + "%", 0xFFFF5B57),
            chip("✺ Crushing: " + format(sim.crushing) + "%", 0xFFF2C94C),
            chip("✶ Cleave: " + format(sim.cleave) + "%", 0xFF9BE370),
            chip("✧ Crit: " + format(sim.critChance) + "%", 0xFFEDEDED)
        );
        drawFourCols(context, 14, 94,
            chip("⚒ STR: " + format(sim.str), 0xFFE89C3D),
            chip("☘ DEX: " + format(sim.dex), 0xFFB6E63E),
            chip("♥ VIT: " + format(sim.vit), 0xFFFF8B47),
            chip("❄ INT: " + format(sim.intelligence), 0xFF64D8F6)
        );
        drawThreeCols(context, 14, 108,
            chip("✶ Elemental: " + format(sim.elemental), 0xFFB768F6),
            chip("✧ Pure: " + format(sim.pure), 0xFFEDEDED),
            chip("✷ Crit Mul: x" + format(sim.critMult), 0xFFEDEDED)
        );
        drawTwoCols(context, 14, 122,
            chip("⚡ Energy Regen: " + format(sim.energyRegen) + "/s", 0xFF5D8BFF),
            chip("❤ HP Regen: " + format(sim.hpRegen) + "/s", 0xFFFF8A8A)
        );
    }

    private static void drawThreeCols(DrawContext context, int x, int y, StatChip a, StatChip b, StatChip c) {
        drawChip(context, a, x, y);
        drawChip(context, b, x + 110, y);
        drawChip(context, c, x + 220, y);
    }

    private static void drawFourCols(DrawContext context, int x, int y, StatChip a, StatChip b, StatChip c, StatChip d) {
        drawChip(context, a, x, y);
        drawChip(context, b, x + 78, y);
        drawChip(context, c, x + 156, y);
        drawChip(context, d, x + 234, y);
    }

    private static void drawTwoCols(DrawContext context, int x, int y, StatChip a, StatChip b) {
        drawChip(context, a, x, y);
        drawChip(context, b, x + 170, y);
    }

    private static void drawChip(DrawContext context, StatChip chip, int x, int y) {
        context.drawText(mc.textRenderer, Text.literal(chip.text), x, y, chip.color, false);
    }

    private static StatChip chip(String text, int color) {
        return new StatChip(text, color);
    }


    public static @Nullable BuildScore evaluateCurrentBuildForClass(String classProfile) {
        DrStandaloneConfig base = DrStandaloneMod.config();
        DrStandaloneConfig local = base.copy();
        local.classProfile = classProfile;

        try {
            Simulation simulation = simulate(local);
            if (simulation == null) return null;
            return new BuildScore(simulation.classProfile, simulation.dps, simulation.ttk, simulation.aps);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static @Nullable Simulation simulate(DrStandaloneConfig config) {
        if (mc.player == null) return null;

        ItemStack weaponStack = mc.player.getMainHandStack();
        if (weaponStack.isEmpty()) return null;
        WeaponKind kind = WeaponKind.from(weaponStack.getItem());
        if (kind == null) return null;
        List<String> weaponTooltip = getTooltipLines(weaponStack);
        int weaponTier = resolveWeaponTier(weaponStack, weaponTooltip);

        Stats stats = new Stats();
        merge(stats, parseStats(weaponTooltip, true));
        merge(stats, parseStats(getTooltipLines(mc.player.getEquippedStack(EquipmentSlot.FEET)), false));
        merge(stats, parseStats(getTooltipLines(mc.player.getEquippedStack(EquipmentSlot.LEGS)), false));
        merge(stats, parseStats(getTooltipLines(mc.player.getEquippedStack(EquipmentSlot.CHEST)), false));
        merge(stats, parseStats(getTooltipLines(mc.player.getEquippedStack(EquipmentSlot.HEAD)), false));
        merge(stats, parseStats(getTooltipLines(mc.player.getOffHandStack()), false));
        applyWeaponBasePassives(stats, kind, weaponTooltip);

        if (stats.avgDamage <= 0) return null;

        ClassProfile profile = ClassProfile.valueOf(config.classProfile);
        TargetTier tier = TargetTier.valueOf(config.targetTier);
        double hpPct = Math.max(0, Math.min(1, config.targetHpPercent / 100d));

        double enemyArmor = AVG_MOB_ARMOR[tier.ordinal()] * config.mobArmorScale;
        double enemyHp = AVG_MOB_HP[tier.ordinal()] * config.mobHealthScale;
        double enemyDodge = AVG_MOB_DODGE[tier.ordinal()] * config.mobAvoidanceScale;
        double enemyBlock = AVG_MOB_BLOCK[tier.ordinal()] * config.mobAvoidanceScale;

        applyClassRules(stats, profile, kind, tier, config, enemyArmor, enemyHp);

        double effectiveAccuracy = stats.accuracy;
        double effectivePiercing = stats.piercing;

        double avoidChance = clamp((enemyDodge + enemyBlock) / 100d, 0, 0.95);
        double accuracyBypassChance = clamp(effectiveAccuracy / 100d, 0, 1);
        double hitChance = ((1 - accuracyBypassChance) * (1 - avoidChance)) + accuracyBypassChance;

        double armorAfterPiercing = Math.max(0, enemyArmor - effectivePiercing);
        double armorMitigation = armorReduction(armorAfterPiercing);
        double baseArmorMultiplier = 1 - armorMitigation;
        double shatterChance = clamp(stats.shatter / 100d, 0, 1);
        double expectedArmorMultiplier = ((1 - shatterChance) * baseArmorMultiplier) + shatterChance;

        double weaponPrimaryBonus = 1;
        if (kind == WeaponKind.Axe) weaponPrimaryBonus += stats.str * PRIMARY_WEAPON_BONUS_PER_POINT;
        if (kind == WeaponKind.Sword) weaponPrimaryBonus += stats.dex * PRIMARY_WEAPON_BONUS_PER_POINT;
        if (kind == WeaponKind.Mace) weaponPrimaryBonus += stats.vit * PRIMARY_WEAPON_BONUS_PER_POINT;
        if (kind == WeaponKind.Scythe) weaponPrimaryBonus += stats.intelligence * PRIMARY_WEAPON_BONUS_PER_POINT;

        double physicalBase = stats.avgDamage * weaponPrimaryBonus;
        double mobMultiplier = 1 + (stats.vsMonsters / 100d);
        double phaseMultiplier = executeMultiplier(stats.execute, hpPct) * crushingMultiplier(stats.crushing, hpPct);
        double cleaveMultiplier = 1 + (clamp(stats.cleave / 100d, 0, 1) * 0.5d);

        double critChance = clamp((stats.critChance + (stats.str * STRENGTH_CRIT_CHANCE_PER_POINT)) / 100d, 0, 1);
        double critBonus = Math.max(1.0d, config.baseCritBonus) + (stats.dex * config.dexCritMultiplierPerPoint) + (profile == ClassProfile.Rogue ? 0.25 : 0);
        double critMultiplier = 1 + (critChance * critBonus);
        double critProcMultiplier = 1 + critBonus;

        double physicalPerHit = physicalBase * mobMultiplier * phaseMultiplier * expectedArmorMultiplier;
        double elementalPerHit = (stats.fireDamage + stats.iceDamage + stats.poisonDamage) * (1 - clamp(config.elementalReduction, 0, 0.95));
        double purePerHit = stats.pureDamage;
        double normalHit = (physicalBase * mobMultiplier * baseArmorMultiplier) + elementalPerHit + purePerHit;
        double totalPerHit = (physicalPerHit + elementalPerHit + purePerHit) * critMultiplier * cleaveMultiplier * hitChance;

        double regenPerSecond = config.basePassiveEnergyRegen + stats.energyRegen;
        regenPerSecond *= 1 + (stats.vit * VITALITY_ENERGY_RECOVERY_PER_POINT);
        double weaponEnergyCost = kind.energyCostForTier(weaponTier);
        AttackWindow attackWindow = isMelee(kind)
            ? simulateMeleeSession(Math.max(0.1d, config.meleeSessionAps), regenPerSecond, weaponEnergyCost)
            : sustainedWindow(Math.min(config.practicalAttackCap, regenPerSecond / weaponEnergyCost), regenPerSecond, weaponEnergyCost, config.attackSpeed);
        double aps = attackWindow.aps();
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
            format(normalHit) + " hit",
            dps,
            aps,
            ttk,
            attackWindow.sessionLabel(),
            effectiveAccuracy,
            effectivePiercing,
            stats.shatter,
            stats.execute,
            stats.crushing,
            stats.cleave,
            critChance * 100,
            critProcMultiplier,
            stats.str,
            stats.dex,
            stats.vit,
            stats.intelligence,
            stats.fireDamage + stats.iceDamage + stats.poisonDamage,
            stats.pureDamage,
            stats.energyRegen,
            stats.hpRegen
        );
    }

    private static void applyClassRules(Stats stats, ClassProfile profile, WeaponKind kind, TargetTier tier, DrStandaloneConfig config, double enemyArmor, double enemyHp) {
        switch (profile) {
            case Warrior -> {
                stats.fireDamage += tier.classDamage;
                if (kind == WeaponKind.Axe) {
                    stats.shatter += 5;
                    if (config.warriorArmorCondition) {
                        double playerArmor = stats.str * 0.01;
                        int stacks = Math.max(0, Math.min(config.warriorCombatStacks, 10));
                        playerArmor += stacks;
                        playerArmor *= 1 + (Math.min(stacks, 5) * 0.02);
                        if (playerArmor > enemyArmor) stats.shatter += 10;
                    }
                }
                if (config.warriorBerserk) stats.critChance += 12;
            }
            case Rogue -> {
                stats.poisonDamage += tier.classDamage;
                if (kind == WeaponKind.Sword) stats.execute += 5;
                double convertible = stats.fireDamage + stats.iceDamage + stats.pureDamage;
                stats.fireDamage *= 0.5;
                stats.iceDamage *= 0.5;
                stats.pureDamage *= 0.5;
                stats.poisonDamage += convertible * 0.5;

                if (config.rogueTargetPoisoned) stats.avgDamage *= 1.15;
        if (config.rogueDashBonus) stats.vsMonsters += 10;

                stats.accuracy += 10;
                if (config.rogueFirstSeconds) {
                    stats.critChance += 10;
                    stats.accuracy += 20;
                }
                if (config.rogueAbility) {
                    stats.avgDamage *= 1 + (Math.min(config.rogueAbilityStacks, 10) * 0.05);
                    if (config.rogueExecuteMod) stats.execute += 15;
                    if (config.roguePiercingMod) stats.piercing += 15;
                }
            }
            case Paladin -> {
                stats.pureDamage += tier.classDamage;
                stats.vsMonsters += 5;
                stats.pureDamage *= 1.10;
                double elemental = stats.fireDamage + stats.iceDamage + stats.poisonDamage;
                stats.fireDamage *= 0.5;
                stats.iceDamage *= 0.5;
                stats.poisonDamage *= 0.5;
                stats.pureDamage += elemental * 0.5;
                if (kind == WeaponKind.Mace) stats.crushing += 5;
                if (config.paladinAbility && config.paladinPureMod) stats.pureDamage *= 1.25;
            }
            case None -> {
            }
        }
    }

    private static List<String> getTooltipLines(ItemStack stack) {
        if (stack.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        for (Text line : stack.getTooltip(Item.TooltipContext.DEFAULT, mc.player, TooltipType.BASIC)) {
            String text = stripRollAnnotations(line.getString());
            if (!text.isBlank()) lines.add(text);
        }
        return lines;
    }

    private static Stats parseStats(List<String> tooltip, boolean allowBaseDamage) {
        Stats stats = new Stats();
        for (String line : tooltip) {
            String matchableLine = sanitizeTooltipLine(line);
            if (allowBaseDamage) {
                Matcher dmg = DMG_PATTERN.matcher(matchableLine);
                if (dmg.matches()) {
                    stats.avgDamage = (parseDouble(dmg.group(1)) + parseDouble(dmg.group(2))) / 2d;
                    continue;
                }
            }

            ParsedStat parsed = tryParseSingleStat(matchableLine, line);
            if (parsed == null) continue;
            String key = parsed.key();
            double value = parsed.value();

            switch (key) {
                case "VS. MONSTERS" -> stats.vsMonsters += value;
                case "CRITICAL HIT" -> stats.critChance += value;
                case "BLEEDING" -> stats.bleeding += value;
                case "PURE DMG" -> stats.pureDamage += value;
                case "FIRE DMG" -> stats.fireDamage += value;
                case "ICE DMG" -> stats.iceDamage += value;
                case "POISON DMG" -> stats.poisonDamage += value;
                case "PIERCING" -> stats.piercing += value;
                case "ACCURACY" -> stats.accuracy += value;
                case "SHATTER" -> stats.shatter += value;
                case "EXECUTE" -> stats.execute += value;
                case "CRUSHING" -> stats.crushing += value;
                case "CLEAVE" -> stats.cleave += value;
                case "STR", "STRENGTH" -> stats.str += value;
                case "DEX", "DEXTERITY" -> stats.dex += value;
                case "VIT", "VITALITY" -> stats.vit += value;
                case "INT", "INTELLECT", "INTELLIGENCE" -> stats.intelligence += value;
                case "ENERGY REGEN", "ENERGY/S", "ENERGY REGEN/S" -> stats.energyRegen += value;
                case "HP REGEN", "HP/S", "HEALTH REGEN", "HEALTH/S" -> stats.hpRegen += value;
            }
        }
        return stats;
    }

    private static @Nullable ParsedStat tryParseSingleStat(String matchableLine, String originalLine) {
        Matcher single = SINGLE_STAT_PATTERN.matcher(matchableLine);
        if (single.matches()) {
            return new ParsedStat(single.group(1).trim().toUpperCase(Locale.ROOT), parseDouble(single.group(2)) + extractEnchantBonus(originalLine));
        }

        single = PASSIVE_SINGLE_STAT_PATTERN.matcher(matchableLine);
        if (single.matches()) {
            return new ParsedStat(single.group(1).trim().toUpperCase(Locale.ROOT), parseDouble(single.group(2)) + extractEnchantBonus(originalLine));
        }

        String loose = matchableLine.trim();
        if (loose.regionMatches(true, 0, "PASSIVE:", 0, "PASSIVE:".length())) {
            loose = loose.substring("PASSIVE:".length()).trim();
        }

        int colon = loose.indexOf(':');
        if (colon <= 0 || colon >= loose.length() - 1) return null;

        String key = loose.substring(0, colon).trim().toUpperCase(Locale.ROOT);
        Matcher number = LOOSE_NUMBER_CAPTURE.matcher(loose.substring(colon + 1));
        if (!number.find()) return null;
        return new ParsedStat(key, parseDouble(number.group()) + extractEnchantBonus(originalLine));
    }

    private static void applyWeaponBasePassives(Stats stats, WeaponKind kind, List<String> weaponTooltip) {
        switch (kind) {
            case Sword -> {
                if (!containsStatLine(weaponTooltip, "ACCURACY")) stats.accuracy += 5;
            }
            case Scythe -> {
                if (!containsStatLine(weaponTooltip, "CLEAVE")) stats.cleave += 5;
            }
            case Axe -> {
                if (!containsStatLine(weaponTooltip, "CRITICAL HIT")) stats.critChance += 5;
            }
            case Mace -> {
                if (!containsStatLine(weaponTooltip, "CRUSHING")) stats.crushing += 5;
            }
            case Bow -> {
                if (!containsStatLine(weaponTooltip, "BLEEDING")) stats.bleeding += 5;
            }
        }
    }

    private static boolean containsStatLine(List<String> tooltip, String key) {
        String expected = key.toUpperCase(Locale.ROOT);
        for (String line : tooltip) {
            ParsedStat parsed = tryParseSingleStat(sanitizeTooltipLine(line), line);
            if (parsed != null && parsed.key().equals(expected)) return true;
        }
        return false;
    }

    private static int resolveWeaponTier(ItemStack stack, List<String> tooltipLines) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.startsWith("wooden_")) return 1;
        if (path.startsWith("stone_")) return 2;
        if (path.startsWith("iron_")) return 3;
        if (path.startsWith("diamond_")) return 4;
        if (path.startsWith("golden_")) return 5;

        if (path.equals("bow") || path.equals("crossbow")) {
            int level = parseTooltipLevel(tooltipLines);
            if (level <= 0) return 1;
            if (level < 20) return 1;
            if (level < 40) return 2;
            if (level < 60) return 3;
            if (level < 80) return 4;
            return 5;
        }

        return 1;
    }

    private static int parseTooltipLevel(List<String> tooltipLines) {
        for (String line : tooltipLines) {
            Matcher matcher = LEVEL_LINE.matcher(line);
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

    private static void merge(Stats into, Stats from) {
        into.avgDamage += from.avgDamage;
        into.vsMonsters += from.vsMonsters;
        into.critChance += from.critChance;
        into.bleeding += from.bleeding;
        into.pureDamage += from.pureDamage;
        into.fireDamage += from.fireDamage;
        into.iceDamage += from.iceDamage;
        into.poisonDamage += from.poisonDamage;
        into.piercing += from.piercing;
        into.accuracy += from.accuracy;
        into.shatter += from.shatter;
        into.execute += from.execute;
        into.crushing += from.crushing;
        into.cleave += from.cleave;
        into.str += from.str;
        into.dex += from.dex;
        into.vit += from.vit;
        into.intelligence += from.intelligence;
        into.energyRegen += from.energyRegen;
        into.hpRegen += from.hpRegen;
    }

    private static String sanitizeTooltipLine(String value) {
        String sanitized = value.replace('\u00A0', ' ').trim();
        String previous;
        do {
            previous = sanitized;
            sanitized = sanitized
                .replaceAll("\\s+\\[(?:MIN|MAX|OVERCAP|Overcap|\\d+(?:\\.\\d+)?%|\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+(?:\\.\\d+)?-\\d+(?:\\.\\d+)?)?)\\]\\s*$", "")
                .trim();
            sanitized = ENCHANT_BONUS_SUFFIX.matcher(sanitized).replaceFirst("").trim();
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
        double total = 0d;
        while (matcher.find()) {
            total += parseDouble(matcher.group(1));
        }
        return total;
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value.replace(',', '.'));
    }

    private static double armorReduction(double armor) {
        return armor / (armor + 100d);
    }

    private static double executeMultiplier(double execute, double hpPct) {
        double executeChance = clamp(execute / 100d, 0, 1);
        double missingHealthPct = 1d - clamp(hpPct, 0, 1);
        return 1 + (executeChance * 1.75d * missingHealthPct);
    }

    private static double crushingMultiplier(double crushing, double hpPct) {
        double crushingChance = clamp(crushing / 100d, 0, 1);
        double currentHealthPct = clamp(hpPct, 0, 1);
        return 1 + (crushingChance * 1.75d * currentHealthPct);
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

    private static boolean isMelee(WeaponKind kind) {
        return kind != WeaponKind.Bow;
    }

    private static AttackWindow simulateMeleeSession(double aps, double regenPerSecond, double costPerHit) {
        double safeAps = Math.max(0.1d, aps);
        double stamina = 100d;
        double dt = 1d / safeAps;
        int hits = 0;
        final double epsilon = 1.0E-6;

        while (hits < 10000 && stamina + epsilon >= costPerHit) {
            stamina -= costPerHit;
            hits++;
            stamina = Math.min(100d, stamina + (regenPerSecond * dt));
        }

        if (hits <= 0) {
            return new AttackWindow(safeAps, 0, 0, "Ⅱ 0.0s at " + format(safeAps) + " APS");
        }

        double stopSeconds = hits / safeAps;
        return new AttackWindow(safeAps, hits, stopSeconds, "Ⅱ " + formatSeconds(stopSeconds) + " at " + format(safeAps) + " APS");
    }

    private static AttackWindow sustainedWindow(double aps, double regenPerSecond, double costPerHit, double fallbackAps) {
        double safeAps = aps > 0 ? aps : fallbackAps;
        double breakEven = costPerHit > 0 ? regenPerSecond / costPerHit : safeAps;
        String label = safeAps <= breakEven + 1.0E-6
            ? "Ⅱ Infinite at " + format(safeAps) + " APS"
            : "Ⅱ Sustained " + format(safeAps) + " APS";
        return new AttackWindow(safeAps, Integer.MAX_VALUE, Double.POSITIVE_INFINITY, label);
    }

    private enum ClassProfile {
        None,
        Warrior,
        Rogue,
        Paladin
    }

    private enum TargetTier {
        T0(0), T1(2), T2(4), T3(8), T4(16), T5(32);
        final int classDamage;
        TargetTier(int classDamage) { this.classDamage = classDamage; }
    }

    private enum WeaponKind {
        Sword(8.0), Scythe(8.4), Axe(8.8), Mace(9.2), Bow(9.6);
        final double energyCost;
        WeaponKind(double energyCost) { this.energyCost = energyCost; }
        double energyCostForTier(int tier) {
            int safeTier = Math.max(1, Math.min(5, tier));
            return energyCost * (1d + (0.2d * (safeTier - 1)));
        }
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
        double bleeding;
        double pureDamage;
        double fireDamage;
        double iceDamage;
        double poisonDamage;
        double piercing;
        double accuracy;
        double shatter;
        double execute;
        double crushing;
        double cleave;
        double str;
        double dex;
        double vit;
        double intelligence;
        double energyRegen;
        double hpRegen;
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
        String sessionLabel,
        double accuracy,
        double piercing,
        double shatter,
        double execute,
        double crushing,
        double cleave,
        double critChance,
        double critMult,
        double str,
        double dex,
        double vit,
        double intelligence,
        double elemental,
        double pure,
        double energyRegen,
        double hpRegen
    ) {
    }

    public record BuildScore(String classProfile, double dps, double ttk, double aps) {
    }

    private record StatChip(String text, int color) {
    }

    private record AttackWindow(double aps, int hits, double stopSeconds, String sessionLabel) {
    }

    private record ParsedStat(String key, double value) {
    }
}
