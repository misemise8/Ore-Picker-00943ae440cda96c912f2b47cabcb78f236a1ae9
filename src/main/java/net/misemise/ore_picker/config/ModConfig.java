package net.misemise.ore_picker.config;

import java.util.ArrayList;
import java.util.List;

/**
 * ModConfig: ランタイムで読み込む設定の POJO。
 * デフォルト値は妥当な安全値になっています。
 */
public class ModConfig {
    /** 一括破壊（vein-mine）で一度に壊せる最大ブロック数（開始ブロック含む、デフォルト 64） */
    public int maxVeinSize = 64;

    /** 絶対上限（どんなに設定されてもこれを超えない） */
    public int maxVeinSizeCap = 512;

    /**
     * 異なる種類の鉱石（例: modA:ore_x と modB:ore_y）が混在している場合に、
     * それらを同一鉱床としてまとめて探索するか。
     * - false: 同一ブロック種のみ連結（安全）
     * - true: OreUtils.isOre(...) ベースの連結を許可（強力）
     */
    public boolean mergeDifferentOreTypes = false;

    /** 自動回収を有効にするか (true/false) */
    public boolean autoCollectEnabled = true;

    /** 自動回収で拾う半径（float） -- 現状 1.5 をデフォルトにしている */
    public double pickupRadius = 1.5d;

    /** 追加で明示的に「鉱石」と見なすブロックIDのリスト。例: "mymod:custom_ore" */
    public List<String> extraOreBlocks = new ArrayList<>();

    /** デバッグログを出すかどうか（false 既定） */
    public boolean debug = false;

    public ModConfig() {}
}
