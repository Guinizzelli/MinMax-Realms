package net.matte.drstandalone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DrStandaloneConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("dr-standalone.json");

    public boolean itemRollsEnabled = true;
    public boolean dpsMeterEnabled = true;
    public boolean gemMeterEnabled = true;

    public boolean showOverallOnLevel = true;
    public boolean statBreakdown = true;
    public int maxStats = 12;
    public String inlineStyle = "Percent";
    public boolean fancyMode = false;
    public boolean hideDurability = false;
    public boolean hideItemId = false;
    public boolean hideComponentsLine = false;

    public int dpsHudX = 8;
    public int dpsHudY = 90;
    public double dpsHudScale = 1.0;
    public String classProfile = "None";
    public String targetTier = "T3";
    public double targetHpPercent = 100.0;
    public double basePassiveEnergyRegen = 5.5;
    public double practicalAttackCap = 4.0;
    public double attackSpeed = 1.5;
    public double baseCritBonus = 0.5;
    public double dexCritMultiplierPerPoint = 0.00025;
    public double mobHealthScale = 1.0;
    public double mobArmorScale = 1.0;
    public double mobAvoidanceScale = 1.0;
    public double elementalReduction = 0.05;

    public int gemHudX = 8;
    public int gemHudY = 8;
    public double gemHudScale = 1.0;
    public boolean gemInventorySource = true;
    public boolean gemChatSource = true;
    public boolean gemActionBarSource = false;

    public static DrStandaloneConfig load() {
        try {
            if (Files.exists(PATH)) {
                return GSON.fromJson(Files.readString(PATH), DrStandaloneConfig.class);
            }
        } catch (IOException | JsonSyntaxException ignored) {
        }

        DrStandaloneConfig config = new DrStandaloneConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
