package net.misemise.ore_picker;

import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ExperienceDroppingBlock;

import java.lang.reflect.Method;
import java.util.List;

/**
 * OreUtils - タグ API に依存しない鉱石判定ユーティリティ。
 *
 * 判定順序:
 * 1) ExperienceDroppingBlock 継承
 * 2) translationKey / toString に "ore" が含まれる（小文字比較）
 * 3) ConfigManager.INSTANCE.extraOreBlocks によるホワイトリスト（柔軟マッチ）
 *
 * 追加: もし環境に Registry API が存在すれば（リフレクション経由で）ブロックIDを取得して
 *      extraOreBlocks の完全一致チェックも試みます。リフレクションに失敗してもフォールバック動作します。
 */
public final class OreUtils {
    private OreUtils() {}

    public static boolean isOre(BlockState state) {
        if (state == null) return false;
        Block blk = state.getBlock();
        if (blk == null) return false;

        // 1) ExperienceDroppingBlock を継承しているなら鉱石の可能性が高い
        try {
            if (blk instanceof ExperienceDroppingBlock) return true;
        } catch (Throwable ignored) {}

        // 2) 翻訳キー / toString に "ore"
        try {
            String key = blk.getTranslationKey();
            if (key != null && key.toLowerCase().contains("ore")) return true;
        } catch (Throwable ignored) {}

        try {
            String s = blk.toString();
            if (s != null && s.toLowerCase().contains("ore")) return true;
        } catch (Throwable ignored) {}

        // 3) Config のホワイトリストをチェック（柔軟マッチ）
        try {
            List<String> extra = ConfigManager.INSTANCE.extraOreBlocks;
            if (extra != null && !extra.isEmpty()) {
                // try reflection to get Registry.BLOCK.getId(block) if available for exact matching
                String registryId = tryGetRegistryId(blk); // may be null if not available

                for (String pattern : extra) {
                    if (pattern == null) continue;
                    pattern = pattern.trim();
                    if (pattern.isEmpty()) continue;

                    // If user provided something like "modid:block_name" and we have registryId, check exact
                    if (registryId != null && pattern.contains(":")) {
                        if (registryId.equalsIgnoreCase(pattern)) return true;
                    }

                    // Flexible checks: match against translationKey or toString or registryId substring
                    try {
                        String key = blk.getTranslationKey();
                        if (key != null && key.toLowerCase().contains(pattern.toLowerCase())) return true;
                    } catch (Throwable ignored) {}

                    try {
                        String s = blk.toString();
                        if (s != null && s.toLowerCase().contains(pattern.toLowerCase())) return true;
                    } catch (Throwable ignored) {}

                    if (registryId != null && registryId.toLowerCase().contains(pattern.toLowerCase())) return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    /**
     * リフレクションで Registry.BLOCK.getId(block) を試みる。
     * 使えない環境では null を返す（安全フォールバック）。
     */
    private static String tryGetRegistryId(Block blk) {
        try {
            // Attempt: net.minecraft.util.registry.Registry.BLOCK.getId(block)
            Class<?> registryClass = Class.forName("net.minecraft.util.registry.Registry");
            // getField/Method access differs across mappings; try getMethod "getId" on Registry.BLOCK
            // Registry.BLOCK is a public static field; attempt to read it
            try {
                java.lang.reflect.Field blockField = registryClass.getField("BLOCK");
                Object blockRegistry = blockField.get(null);
                if (blockRegistry != null) {
                    // try method getId(Block) or getKey(Block)
                    try {
                        Method getIdMethod = blockRegistry.getClass().getMethod("getId", Block.class);
                        Object id = getIdMethod.invoke(blockRegistry, blk);
                        if (id != null) {
                            // id.toString() should be like "modid:block"
                            return id.toString();
                        }
                    } catch (NoSuchMethodException ignored) {
                        // try getKey or getId alternative names
                        try {
                            Method getKeyMethod = blockRegistry.getClass().getMethod("getKey", Block.class);
                            Object key = getKeyMethod.invoke(blockRegistry, blk);
                            if (key != null) return key.toString();
                        } catch (Throwable ignored2) {}
                    }
                }
            } catch (NoSuchFieldException nsf) {
                // some mappings may not expose BLOCK as field; ignore
            }
        } catch (Throwable ignored) {
            // any failure -> return null (no registry id available)
        }
        return null;
    }
}
