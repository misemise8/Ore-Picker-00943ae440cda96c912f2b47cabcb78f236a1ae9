package net.misemise.ore_picker;

import net.misemise.ore_picker.config.ConfigManager;

/**
 * 小さなログラッパー。
 * - debug() は ConfigManager.INSTANCE.debug が true のときのみ出力する。
 * - info() は目立つが頻度が高くないメッセージ用。
 * - hud() は HUD 固有のデバッグで throttle ロジックと組み合わせて使うことを想定。
 */
public final class OrePickerLog {
    private OrePickerLog() {}

    private static boolean debugEnabled() {
        try {
            return ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void info(String msg) {
        try {
            System.out.println("[OrePicker] " + msg);
        } catch (Throwable ignored) {}
    }

    public static void debug(String msg) {
        try {
            if (debugEnabled()) System.out.println("[OrePicker][DEBUG] " + msg);
        } catch (Throwable ignored) {}
    }

    public static void hud(String msg) {
        try {
            if (debugEnabled()) System.out.println("[OrePicker][HUD] " + msg);
        } catch (Throwable ignored) {}
    }

    public static void error(String msg, Throwable t) {
        try {
            System.err.println("[OrePicker][ERROR] " + msg);
            if (t != null) t.printStackTrace();
        } catch (Throwable ignored) {}
    }
}
