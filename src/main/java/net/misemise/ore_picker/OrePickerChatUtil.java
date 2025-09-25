package net.misemise.ore_picker;

import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 小さなヘルパー: 設定に応じてチャット送信を行う
 */
public final class OrePickerChatUtil {
    private OrePickerChatUtil() {}

    /**
     * プレイヤーへ [OrePicker] prefix を付けてメッセージを送信する（ConfigManager.INSTANCE.logToChat が true のときのみ）
     */
    public static void sendMaybeChat(ServerPlayerEntity player, Text message) {
        try {
            if (player == null) return;
            if (ConfigManager.INSTANCE == null) {
                try { ConfigManager.load(); } catch (Throwable ignored) {}
            }
            if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.logToChat) {
                return; // チャット出力は無効
            }
            // prefix を付けて送る (second parameter = false => not system message)
            try {
                Text pref = Text.literal("[OrePicker] ").append(message);
                player.sendMessage(pref, false);
            } catch (Throwable t) {
                // 互換性のため fallback
                player.sendMessage(Text.literal("[OrePicker] " + message.getString()), false);
            }
        } catch (Throwable ignored) {}
    }
}
