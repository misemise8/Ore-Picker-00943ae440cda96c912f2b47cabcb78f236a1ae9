package net.misemise.ore_picker.config;

/**
 * 非同期IOや外部ファイルを今すぐ使わず、ランタイムで参照できる形の簡易設定管理。
 * 将来JSON/YAMLでの永続化に差し替え可能。
 */
public final class ConfigManager {
    public static ModConfig INSTANCE = new ModConfig();

    private ConfigManager() {}

    public static void load() {
        // 今はデフォルト設定をそのまま使う。
        // 将来: ファイルから読み込んで INSTANCE を差し替える。
        INSTANCE = new ModConfig();
        System.out.println("[OrePicker] ConfigManager: loaded default config (maxVeinSize=" + INSTANCE.maxVeinSize + ")");
    }
}
