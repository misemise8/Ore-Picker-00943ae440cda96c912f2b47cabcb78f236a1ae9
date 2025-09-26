package net.misemise.ore_picker.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.misemise.ore_picker.config.ConfigManager;

import java.lang.reflect.*;

/**
 * HoldHudOverlay - Mixin で InGameHud.render の末尾から確実に呼び出す想定の実装
 *
 * 特徴:
 *  - 色は単色 (黄緑 #9AFF66)
 *  - ホールド中は不透明、離すと 1.2 秒で線形フェードアウト
 *  - テキストを 1.2 倍相当で表示（位置・幅計算で調整）
 *  - 互換性のため drawContext / TextRenderer 系メソッドをリフレクションで探索して呼ぶ
 *  - public static renderOnTop を Mixin から呼んで「チャットより上」に描画する
 */
public final class HoldHudOverlay {
    private HoldHudOverlay() {}

    private static final long FADE_MS = 1200L; // 1.2 秒
    private static final float SCALE = 1.2f;
    private static final int COLOR_RGB = 0x9AFF66; // 黄緑

    // hold state
    private static volatile boolean lastHold = false;
    private static volatile long lastChangeTime = System.currentTimeMillis();

    // --- (既存の register 等はそのまま使っている想定) ---
    // register() の中で HudRenderCallback にも登録してありますが、確実に上に出すために Mixin の呼び出しも追加します。
    // （register のコードは既にあるなら差し替え不要）

    public static void onHoldChanged(boolean hold) {
        if (hold != lastHold) {
            lastHold = hold;
            lastChangeTime = System.currentTimeMillis();
        }
    }

    private static float computeOpacity() {
        if (lastHold) return 1.0f;
        long elapsed = System.currentTimeMillis() - lastChangeTime;
        if (elapsed >= FADE_MS) return 0f;
        return 1.0f - (float) elapsed / (float) FADE_MS;
    }

    // Public entrypoint used by Mixin: InGameHud.render の最後から呼ぶ（チャットより上に描画される）
    public static void renderOnTop(MinecraftClient client, Object matrixStack, float tickDelta) {
        try {
            try { if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) return; } catch (Throwable ignored) {}

            float opacity = computeOpacity();
            if (opacity <= 0f) return;

            String textStr = "OrePicker: Running";
            Text labelText = Text.literal(textStr);
            OrderedText ordered = labelText.asOrderedText();

            int sw = client.getWindow().getScaledWidth();
            int sh = client.getWindow().getScaledHeight();

            int textWidth = tryGetTextWidth(client, textStr, ordered, labelText);
            int fontH = tryGetFontHeight(client);

            // center X, Y (少し下寄せ)
            float xf = (sw / 2f) - (textWidth * SCALE / 2f);
            float baseBottomOffset = 40f;
            float yf = (sh - baseBottomOffset - (fontH * SCALE));

            // tint with alpha
            int alphaByte = Math.round(opacity * 255f);
            if (opacity > 0f && alphaByte == 0) alphaByte = 1;
            alphaByte = alphaByte & 0xFF;
            int tint = (alphaByte << 24) | COLOR_RGB;

            // shader color
            RenderSystem.setShaderColor(
                    ((COLOR_RGB >> 16) & 0xFF) / 255.0f,
                    ((COLOR_RGB >> 8) & 0xFF) / 255.0f,
                    (COLOR_RGB & 0xFF) / 255.0f,
                    opacity
            );

            // Try to draw using TextRenderer + provided matrixStack (this will be after chat because Mixin calls from TAIL)
            boolean drawn = tryDrawWithTextRendererUsingMatrix(client, matrixStack, textStr, labelText, ordered, xf, yf, tint, SCALE);

            // fallback: try DrawContext-style (some mappings pass DrawContext earlier; keep for compatibility)
            if (!drawn) {
                // Try to build a DrawContext-like object from parameters -- we don't have one here, so fallback to default methods
                drawn = tryDrawWithTextRendererFallback(client, textStr, labelText, ordered, xf, yf, tint, SCALE);
            }

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
    }

    // --- ヘルパ: テキスト幅・高さ取得 ---
    private static int tryGetTextWidth(MinecraftClient client, String s, OrderedText ordered, Text text) {
        try {
            Object tr = client.textRenderer;
            if (tr == null) return s.length() * 6;
            try {
                Method m = tr.getClass().getMethod("getWidth", String.class);
                Object r = m.invoke(tr, s);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException ignored) {}
            try {
                Method m = tr.getClass().getMethod("getWidth", OrderedText.class);
                Object r = m.invoke(tr, ordered);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException ignored) {}
            try {
                Method m = tr.getClass().getMethod("getWidth", Text.class);
                Object r = m.invoke(tr, text);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
        return s.length() * 6;
    }

    private static int tryGetFontHeight(MinecraftClient client) {
        try {
            Object tr = client.textRenderer;
            if (tr == null) return 9;
            try {
                Field f = tr.getClass().getField("fontHeight");
                Object v = f.get(tr);
                if (v instanceof Integer) return (Integer) v;
            } catch (NoSuchFieldException ignored) {}
            try {
                Method m = tr.getClass().getMethod("getFontHeight");
                Object r = m.invoke(tr);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException ignored) {}
            try {
                Method m = tr.getClass().getMethod("getHeight");
                Object r = m.invoke(tr);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
        return 9;
    }

    // --- TextRenderer に MatrixStack を渡すバリエーションで呼ぶ（Mixin から渡された MatrixStack をそのまま使う） ---
    private static boolean tryDrawWithTextRendererUsingMatrix(MinecraftClient client, Object matrixStack, String str, Text text, OrderedText ordered, float xf, float yf, int tint, float scaleHint) {
        try {
            Object tr = client.textRenderer;
            if (tr == null || matrixStack == null) return false;

            for (Method m : tr.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (!(n.contains("drawwithshadow") || n.contains("draw"))) continue;
                Class<?>[] pts = m.getParameterTypes();
                try {
                    // (MatrixStack, Text/OrderedText/String, float, float, int)
                    if (pts.length == 5 && pts[0].getName().toLowerCase().contains("matrix")) {
                        if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                            m.invoke(tr, matrixStack, ordered, xf, yf, tint);
                            return true;
                        } else if (pts[1].getName().toLowerCase().contains("text")) {
                            m.invoke(tr, matrixStack, text, xf, yf, tint);
                            return true;
                        } else if (pts[1] == String.class) {
                            m.invoke(tr, matrixStack, str, xf, yf, tint);
                            return true;
                        }
                    }
                } catch (IllegalArgumentException iae) {
                } catch (InvocationTargetException ite) {
                } catch (NoSuchMethodError nsm) {
                }
            }
        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
        return false;
    }

    // --- 既存の汎用フォールバック（MatrixStack が作れないとき） ---
    private static boolean tryDrawWithTextRendererFallback(MinecraftClient client, String str, Text text, OrderedText ordered, float xf, float yf, int tint, float scaleHint) {
        try {
            Object tr = client.textRenderer;
            if (tr == null) return false;

            for (Method m : tr.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (!(n.contains("drawwithshadow") || n.equals("draw") || n.contains("drawstring"))) continue;
                Class<?>[] pts = m.getParameterTypes();
                try {
                    if (pts.length == 5 && pts[0].getName().toLowerCase().contains("matrix")) {
                        Object matrix = getDefaultMatrixStack();
                        if (matrix == null) continue;
                        if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                            m.invoke(tr, matrix, ordered, xf, yf, tint);
                            return true;
                        } else if (pts[1].getName().toLowerCase().contains("text")) {
                            m.invoke(tr, matrix, text, xf, yf, tint);
                            return true;
                        } else if (pts[1] == String.class) {
                            m.invoke(tr, matrix, str, xf, yf, tint);
                            return true;
                        }
                    }
                    if (pts.length == 4 && pts[0] == String.class) {
                        m.invoke(tr, str, xf, yf, tint);
                        return true;
                    }
                } catch (IllegalArgumentException iae) {
                } catch (InvocationTargetException ite) {
                } catch (NoSuchMethodError nsm) {
                }
            }
        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static Object getDefaultMatrixStack() {
        try {
            Class<?> msClass = Class.forName("net.minecraft.client.util.math.MatrixStack");
            try {
                Constructor<?> c = msClass.getDeclaredConstructor();
                c.setAccessible(true);
                return c.newInstance();
            } catch (NoSuchMethodException nsme) {
                return null;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
