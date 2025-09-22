package net.misemise.ore_picker;

import net.minecraft.block.BlockState;
import net.minecraft.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * OreUtils - 鉱石判定ユーティリティ
 *
 * - ConfigManager.INSTANCE.extraOreBlocks がカンマ区切りの文字列でも扱えるようにする
 * - デフォルトでは block.toString() に "ore" を含むものを鉱石とみなす
 * - optional: ExperienceDroppingBlock を継承している場合も鉱石扱い（XP を落とすブロック）
 */
public final class OreUtils {
    private OreUtils() {}

    /**
     * 鉱石かどうかを判定する。
     * 他 mod の鉱石を含めたい場合は config/orepicker.properties の
     * extraOreBlocks にカンマ区切りでキーワードまたは block id を追加できます。
     *
     * 例:
     *   extraOreBlocks=modid:mythril_ore,modid:tin_ore
     */
    public static boolean isOre(BlockState state) {
        if (state == null) return false;
        Block blk = state.getBlock();
        if (blk == null) return false;

        String blockStr = "";
        try {
            blockStr = blk.toString().toLowerCase();
        } catch (Throwable ignored) {}

        // 1) config による追加マッチ（extraOreBlocks はカンマ区切りの String として扱う）
        try {
            if (net.misemise.ore_picker.config.ConfigManager.INSTANCE != null) {
                String extra = net.misemise.ore_picker.config.ConfigManager.INSTANCE.extraOreBlocks;
                if (extra != null && !extra.trim().isEmpty()) {
                    List<String> keys = parseCommaSeparated(extra);
                    for (String k : keys) {
                        String kk = k.toLowerCase().trim();
                        if (kk.isEmpty()) continue;
                        // 部分一致で許容（より寛容にすることで mod の id や名前に対応しやすくする）
                        if (blockStr.contains(kk) || kk.contains(blockStr) || blockStr.equals(kk)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2) 名前に "ore" が含まれているものは鉱石扱い（vanilla と多くの mod をカバー）
        try {
            if (blockStr.contains("ore")) return true;
        } catch (Throwable ignored) {}

        // 3) ExperienceDroppingBlock を継承していれば鉱石（XP を落とすブロック）
        try {
            Class<?> edClass = Class.forName("net.minecraft.block.ExperienceDroppingBlock");
            if (edClass.isAssignableFrom(blk.getClass())) return true;
        } catch (Throwable ignored) {}

        return false;
    }

    private static List<String> parseCommaSeparated(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String[] parts = s.split(",");
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }
}
