package net.misemise.ore_picker.config;

public class ModConfig {
    /** 一括破壊（vein-mine）で一度に壊せる最大ブロック数（安全上の上限） */
    public int maxVeinSize = 128;

    /** 将来の拡張用: XPを与えるブロックホワイトリストをここに置ける */
    // public Set<String> xpWhitelist = Set.of("minecraft:diamond_ore", ...);

    public ModConfig() {}
}
