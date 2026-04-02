package net.matte.drstandalone;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class DrKeybinds {
    private static final String CATEGORY = "key.categories.dr_standalone";

    private static KeyBinding openConfig;
    private static KeyBinding toggleDps;
    private static KeyBinding toggleGems;
    private static KeyBinding toggleRolls;

    private DrKeybinds() {
    }

    public static void init() {
        openConfig = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dr_standalone.open_config",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY
        ));
        toggleDps = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dr_standalone.toggle_dps",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            CATEGORY
        ));
        toggleGems = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dr_standalone.toggle_gems",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            CATEGORY
        ));
        toggleRolls = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dr_standalone.toggle_rolls",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(DrKeybinds::onTick);
    }

    private static void onTick(MinecraftClient client) {
        while (openConfig.wasPressed()) {
            client.setScreen(new DrStandaloneConfigScreen(client.currentScreen));
        }

        while (toggleDps.wasPressed()) {
            DrStandaloneConfig config = DrStandaloneMod.config();
            config.dpsMeterEnabled = !config.dpsMeterEnabled;
            config.save();
        }

        while (toggleGems.wasPressed()) {
            DrStandaloneConfig config = DrStandaloneMod.config();
            config.gemMeterEnabled = !config.gemMeterEnabled;
            config.save();
        }

        while (toggleRolls.wasPressed()) {
            DrStandaloneConfig config = DrStandaloneMod.config();
            config.itemRollsEnabled = !config.itemRollsEnabled;
            config.save();
        }
    }
}
