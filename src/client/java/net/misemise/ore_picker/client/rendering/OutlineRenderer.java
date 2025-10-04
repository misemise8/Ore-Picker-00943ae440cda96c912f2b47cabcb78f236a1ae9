package net.misemise.ore_picker.client.rendering;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.misemise.ore_picker.config.ConfigManager;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * OutlineRenderer (STUB)
 * コンパイル回避のための何もしないスタブ。
 * 後で環境に合わせた実実装に差し替えます。
 */
public final class OutlineRenderer {
    private OutlineRenderer() {}

    /**
     * returns true if nothing rendered (for caller convenience).
     */
    public static boolean renderOutlines(MinecraftClient client, MatrixStack matrices, Camera camera, float tickDelta) {
        try {
            // 設定でオフなら即終了
            try {
                if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableOutlineOverlay) return true;
            } catch (Throwable ignored) {}

            if (client == null || camera == null || matrices == null) return true;

            // もし選択ブロックセットがあれば確認する（副作用なし）
            try {
                Class<?> c = Class.forName("net.misemise.ore_picker.client.Ore_pickerClient");
                Field f = null;
                try {
                    f = c.getDeclaredField("selectedBlocks");
                    f.setAccessible(true);
                } catch (NoSuchFieldException nsf) {
                    try { f = c.getField("selectedBlocks"); } catch (NoSuchFieldException ignored) {}
                }
                if (f != null) {
                    Object val = f.get(null);
                    if (val instanceof Set) {
                        //noinspection unchecked
                        Set<BlockPos> selected = (Set<BlockPos>) val;
                        if (selected == null || selected.isEmpty()) return true;
                    }
                }
            } catch (Throwable ignored) {}

            // スタブなので描画はしない
            return true;
        } catch (Throwable t) {
            return true;
        }
    }
}
