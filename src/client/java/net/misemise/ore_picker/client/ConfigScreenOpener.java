package net.misemise.ore_picker.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * キーバインドで設定画面を開くユーティリティ。
 * - デフォルトキーは 'O' に設定しています（必要なら変更可能）。
 */
public class ConfigScreenOpener {
    private static KeyBinding openConfigKey;

    public static void init() {
        try {
            openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.ore_picker.open_config",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_O,
                    "category.ore_picker"
            ));
        } catch (Throwable t) {
            System.err.println("[OrePicker] Failed to register config keybinding (KeyBindingHelper).");
            t.printStackTrace();
            return;
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (openConfigKey.wasPressed()) {
                    ClothConfigScreen.open(MinecraftClient.getInstance().currentScreen);
                }
            } catch (Throwable t) {
                System.err.println("[OrePicker] Exception while trying to open config screen.");
                t.printStackTrace();
            }
        });
    }
}
