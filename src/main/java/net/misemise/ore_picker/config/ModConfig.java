package net.misemise.ore_picker.config;

import java.util.ArrayList;
import java.util.List;

public class ModConfig {
    /** 一括破壊（vein-mine）で一度に壊せる最大ブロック数（安全上の上限） */
    public int maxVeinSize = 128;

    /** 将来の拡張用: XPを与えるブロックホワイトリストをここに置ける */
    // public Set<String> xpWhitelist = Set.of("minecraft:diamond_ore", ...);

    /**
     * 追加で明示的に「鉱石」と見なすブロックIDのリスト。
     * 例: "mymod:custom_ore", "othermod:rare_ore"
     * これらは OreUtils でチェックされ、一括破壊対象になります。
     */
    public List<String> extraOreBlocks = new ArrayList<>();

    /**
     * 異なる種類の鉱石（例: modA:ore_x と modB:ore_y）が同じ領域で混在している場合に
     * それらを同一鉱床としてまとめて探索するかどうか。
     * - false: 従来同様「同一ブロック種」のみ連結して壊す（安全デフォルト）
     * - true: 探索条件を OreUtils.isOre(...) ベースにして、異種鉱石も連結対象とする
     */
    public boolean mergeDifferentOreTypes = false;

    public ModConfig() {}
}
