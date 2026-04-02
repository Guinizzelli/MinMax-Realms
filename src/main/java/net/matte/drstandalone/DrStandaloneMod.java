package net.matte.drstandalone;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DrStandaloneMod implements ClientModInitializer {
    public static final String MOD_ID = "dr-standalone";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static DrStandaloneConfig config;

    public static DrStandaloneConfig config() {
        return config;
    }

    @Override
    public void onInitializeClient() {
        config = DrStandaloneConfig.load();

        DrKeybinds.init();
        DrItemRollsFeature.init();
        DpsMeterFeature.init();
        GemMeterFeature.init();

        LOG.info("DR Standalone initialized.");
    }
}
