package net.misemise.ore_picker;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.misemise.ore_picker.config.ConfigManager;

/**
 * NotificationHelper
 *
 * - サーバー側のチャット通知 / コンソールログ出力を集約するユーティリティ
 * - ConfigManager.INSTANCE.logToChat を参照してチャット送信を行うかを決定する
 * - 将来アクションバーや別の出力先に切り替える際はここだけ変更すればよい
 */
public final class NotificationHelper {
    private static final String PREFIX = "[OrePicker] ";

    private NotificationHelper() {}

    /**
     * プレイヤーにチャットで通知（ConfigManager.INSTANCE.logToChat が true の場合のみ）。
     * 失敗しても例外を投げず、フォールバックで標準出力へ出す。
     */
    public static void notifyPlayer(ServerPlayerEntity player, Text message) {
        if (player == null || message == null) return;

        try {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.logToChat) {
                // Text を組み合わせて送る（多くの mappings で有効）
                player.sendMessage(Text.literal(PREFIX).append(message), false);
            }
        } catch (Throwable t) {
            // ここでは API 差異・例外に備えて落ちないようにし、フォールバックでログ出力
            try {
                System.out.println(PREFIX + safeGetPlayerName(player) + " -> " + safeGetTextString(message));
            } catch (Throwable ignored) {
                // 最終フォールバック: 例外も握りつぶす（サーバー安定性優先）
            }
        }
    }

    /**
     * コンソール（stdout）へ必ずログを出す。
     */
    public static void logToConsole(ServerPlayerEntity player, Text message) {
        try {
            String who = (player == null ? "server" : safeGetPlayerName(player));
            System.out.println(PREFIX + who + ": " + safeGetTextString(message));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * プレイヤーに通知（Config の判定あり）+ コンソールへ出力
     */
    public static void notifyBoth(ServerPlayerEntity player, Text message) {
        notifyPlayer(player, message);
        logToConsole(player, message);
    }

    /* -------------------- ヘルパー -------------------- */

    /**
     * 安全にプレイヤー名を取得する。まず player.getName().getString() を試し、
     * 失敗したら gameProfile 経由、さらに失敗したら "unknown" を返す。
     */
    private static String safeGetPlayerName(ServerPlayerEntity player) {
        if (player == null) return "null-player";
        try {
            // Text -> String
            return player.getName().getString();
        } catch (Throwable t) {
            try {
                // GameProfile 経由（多くの環境で利用可能）
                if (player.getGameProfile() != null && player.getGameProfile().getName() != null) {
                    return player.getGameProfile().getName();
                }
            } catch (Throwable ignored) {}
        }
        return "unknown";
    }

    /**
     * Text を安全に文字列化する。getString() を試し、それが駄目なら toString()。
     */
    private static String safeGetTextString(Text text) {
        if (text == null) return "";
        try {
            return text.getString();
        } catch (Throwable t) {
            try {
                return text.toString();
            } catch (Throwable ignored) {
                return "";
            }
        }
    }
}
