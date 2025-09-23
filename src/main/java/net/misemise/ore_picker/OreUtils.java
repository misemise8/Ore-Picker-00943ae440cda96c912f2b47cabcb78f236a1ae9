package net.misemise.ore_picker;

import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.block.ExperienceDroppingBlock;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * OreUtils - 鉱石判定ユーティリティ（マッピング差に強くするためリフレクションで ID を取得）
 *
 * 判定ルール（優先度順）:
 * 1) ConfigManager.INSTANCE.extraOreBlocks に明示されたブロックID（カンマ区切り）に一致する場合は鉱石とみなす
 * 2) ブロックが ExperienceDroppingBlock のサブクラスであれば鉱石とみなす（原石系は XP を落とすことが多い）
 * 3) ブロックの ID の path に "ore" を含む場合は鉱石とみなす（フォールバック）
 *
 * リフレクションで色々な Registry API の構成を試み、失敗したら block.toString() を ID 文字列の代わりに使う。
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
                String idStr = getBlockIdString(block); // リフレクション or toString フォールバック
                if (idStr == null) idStr = block.toString();

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
            String idStr = getBlockIdString(block);
            if (idStr != null) {
                if (idStr.toLowerCase().contains("ore")) return true;
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

    /**
     * ブロックのリソース ID を文字列で取得するユーティリティ（複数のマッピングパターンを試行する）
     * 戻り値: "modid:name" 形式の文字列、取得できなければ null を返す（呼び出し側で toString() にフォールバックする）
     */
    private static String getBlockIdString(Block block) {
        // 試行1: net.minecraft.registry.Registries の static field BLOCK (Fabric 1.20+/new) -> Registries.BLOCK.getId(block)
        try {
            Class<?> registriesCls = Class.forName("net.minecraft.registry.Registries");
            Field blockField = null;
            try {
                blockField = registriesCls.getDeclaredField("BLOCK");
            } catch (NoSuchFieldException ignored) {}
            if (blockField != null) {
                Object registry = blockField.get(null);
                // try method getId(Object) or getId(Block)
                try {
                    Method getId = registry.getClass().getMethod("getId", Object.class);
                    Object idObj = getId.invoke(registry, block);
                    if (idObj != null) return idObj.toString();
                } catch (NoSuchMethodException ignored) {
                    try {
                        Method getId2 = registry.getClass().getMethod("getId", block.getClass());
                        Object idObj = getId2.invoke(registry, block);
                        if (idObj != null) return idObj.toString();
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}

        // 試行2: net.minecraft.registry.Registry の static field BLOCK -> Registry.BLOCK.getId(block)
        try {
            Class<?> registryCls = Class.forName("net.minecraft.registry.Registry");
            Field blockField = null;
            try {
                blockField = registryCls.getDeclaredField("BLOCK");
            } catch (NoSuchFieldException ignored) {}
            if (blockField != null) {
                Object registry = blockField.get(null);
                try {
                    Method getId = registry.getClass().getMethod("getId", Object.class);
                    Object idObj = getId.invoke(registry, block);
                    if (idObj != null) return idObj.toString();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // 試行3: net.minecraft.util.registry.Registry (古いマッピング)
        try {
            Class<?> utilRegistryCls = Class.forName("net.minecraft.util.registry.Registry");
            Field blockField = null;
            try {
                blockField = utilRegistryCls.getDeclaredField("BLOCK");
            } catch (NoSuchFieldException ignored) {}
            if (blockField != null) {
                Object registry = blockField.get(null);
                try {
                    Method getId = registry.getClass().getMethod("getId", Object.class);
                    Object idObj = getId.invoke(registry, block);
                    if (idObj != null) return idObj.toString();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // どれもダメなら null を返して呼び出し側で block.toString() にフォールバック
        return null;
    }
}
