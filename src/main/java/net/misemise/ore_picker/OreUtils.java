package net.misemise.ore_picker;

import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.block.ExperienceDroppingBlock;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.List;

/**
 * OreUtils - 鉱石判定ユーティリティ
 *
 * 判定ルール（優先度順）:
 * 1) ConfigManager.INSTANCE.extraOreBlocks に明示されたブロックID（カンマ区切り）に一致する場合は鉱石とみなす
 * 2) ブロックが ExperienceDroppingBlock のサブクラスであれば鉱石とみなす（原石系は XP を落とすことが多い）
 * 3) ブロックの Registry ID の path に "ore" を含む場合は鉱石とみなす（フォールバック）
 */
public final class OreUtils {
    private OreUtils() {}

    public static boolean isOre(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();

        // 1) 明示リストチェック（ConfigManager の extraOreBlocks はカンマ区切りの文字列）
        try {
            if (net.misemise.ore_picker.config.ConfigManager.INSTANCE != null) {
                String extra = net.misemise.ore_picker.config.ConfigManager.INSTANCE.extraOreBlocks;
                List<String> extras = parseCommaSeparated(extra);
                Identifier id = null;
                try {
                    id = Registry.BLOCK.getId(block);
                } catch (Throwable ignored) { }
                String idStr = id != null ? id.toString() : block.toString();
                for (String e : extras) {
                    if (e == null || e.isEmpty()) continue;
                    if (e.equalsIgnoreCase(idStr)) return true;
                    // allow matching just path (minecraft:iron_ore => iron_ore)
                    if (idStr.contains(":")) {
                        String path = idStr.substring(idStr.indexOf(':') + 1);
                        if (e.equalsIgnoreCase(path)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2) XPドロップ判定（多くの鉱石は XP を落とす）
        try {
            if (block instanceof ExperienceDroppingBlock) return true;
        } catch (Throwable ignored) {}

        // 3) id 文字列に "ore" を含むかどうか（フォールバック）
        try {
            Identifier id = null;
            try {
                id = Registry.BLOCK.getId(block);
            } catch (Throwable ignored) {}
            if (id != null) {
                if (id.getPath().toLowerCase().contains("ore")) return true;
            } else {
                String s = block.toString().toLowerCase();
                if (s.contains("ore")) return true;
            }
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
