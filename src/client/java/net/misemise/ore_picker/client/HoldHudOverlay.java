package net.misemise.ore_picker.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.misemise.ore_picker.config.ConfigManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * HoldHudOverlay - 互換性を重視した HUD 描画ユーティリティ
 *
 * - 色は単色 (黄緑 #9AFF66)
 * - ホールド中は不透明、離すと 1.2 秒で線形フェードアウト
 * - テキストを 1.2 倍で表示（MatrixStack が渡されたら scale を使う）
 * - DrawContext / MatrixStack / TextRenderer の複数シグネチャをリフレクションで試す
 *
 * public static renderOnTop(MinecraftClient, Object matrixOrDrawContext, float tickDelta)
 * を Mix-in か HudRenderCallback のどちらかから呼んでください。
 */
public final class HoldHudOverlay {
    private HoldHudOverlay() {}

    private static final long FADE_MS = 1200L; // 1.2秒
    private static final float SCALE = 1.2f;
    private static final int COLOR_RGB = 0x9AFF66; // 黄緑 (no alpha byte embedded)

    // hold state
    private static volatile boolean lastHold = false;
    private static volatile long lastChangeTime = System.currentTimeMillis();

    // 外部から hold 状態を通知するユーティリティ（既にある onHoldChanged を使ってください）
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

    /**
     * Mixin や HudRenderCallback から呼ぶエントリポイント。
     * - client: MinecraftClient.getInstance() のものを渡してもよいが、ここでは引数でも受け取る。
     * - matrixOrDrawContext: ChatHud / InGameHud から渡される DrawContext か MatrixStack。どちらでも受け取る。
     */
    public static void renderOnTop(MinecraftClient client, Object matrixOrDrawContext, float tickDelta) {
        try {
            try { if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) return; } catch (Throwable ignored) {}

            float opacity = computeOpacity();
            if (opacity <= 0f) return;

            if (client == null) return;

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

            // 描画前: 深度テストを無効化して常に前面に表示、ブレンドを有効に
            try { RenderSystem.disableDepthTest(); } catch (Throwable ignored) {}
            try { RenderSystem.enableBlend(); } catch (Throwable ignored) {}

            // グローバルなアルファは shaderColor で与える（整数 alpha 変換による丸めフラッシュを避ける）
            RenderSystem.setShaderColor(
                    ((COLOR_RGB >> 16) & 0xFF) / 255.0f,
                    ((COLOR_RGB >> 8) & 0xFF) / 255.0f,
                    (COLOR_RGB & 0xFF) / 255.0f,
                    opacity
            );

            boolean drawn = false;

            // 1) もし matrixOrDrawContext が MatrixStack っぽければ（scale を適用して描画）
            if (matrixOrDrawContext != null && matrixOrDrawContext.getClass().getName().toLowerCase().contains("matrix")) {
                // try push/scale/pop if possible, otherwise just try TextRenderer with provided matrixStack
                drawn = tryDrawWithTextRendererUsingMatrix(client, matrixOrDrawContext, textStr, labelText, ordered, xf, yf, COLOR_RGB, SCALE);
                if (!drawn) {
                    // fallback to generic TextRenderer fallback (no matrix scaling)
                    drawn = tryDrawWithTextRendererFallback(client, textStr, labelText, ordered, xf, yf, COLOR_RGB, SCALE);
                }
            } else {
                // 2) try DrawContext-style drawing (modern mappings)
                if (matrixOrDrawContext != null) {
                    drawn = tryDrawWithDrawContextReflection(matrixOrDrawContext, client, textStr, labelText, ordered, xf / SCALE, yf / SCALE);
                }
                // 3) fallback: TextRenderer without a matrix
                if (!drawn) {
                    drawn = tryDrawWithTextRendererFallback(client, textStr, labelText, ordered, xf, yf, COLOR_RGB, SCALE);
                }
            }

            // restore shader color / depth / blend
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            try { RenderSystem.enableDepthTest(); } catch (Throwable ignored) {}
            try { RenderSystem.disableBlend(); } catch (Throwable ignored) {}

        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
    }

    // -------------------- ヘルパ --------------------

    private static int tryGetTextWidth(MinecraftClient client, String s, OrderedText ordered, Text text) {
        try {
            Object tr = client.textRenderer;
            if (tr == null) return s.length() * 6;
            // try getWidth(String)
            try {
                Method m = tr.getClass().getMethod("getWidth", String.class);
                Object r = m.invoke(tr, s);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException nsme) { }
            // try getWidth(OrderedText)
            try {
                Method m = tr.getClass().getMethod("getWidth", OrderedText.class);
                Object r = m.invoke(tr, ordered);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException nsme) { }
            // try getWidth(Text)
            try {
                Method m = tr.getClass().getMethod("getWidth", Text.class);
                Object r = m.invoke(tr, text);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException nsme) { }
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

    /**
     * drawContext ベースの描画をリフレクションで試す
     * xi, yi はスケール考慮済み（ここでは SCALE を掛ける前の値を渡すことを想定）
     */
    private static boolean tryDrawWithDrawContextReflection(Object drawCtx, MinecraftClient client, String str, Text text, OrderedText ordered, float xi, float yi) {
        try {
            Method[] methods = drawCtx.getClass().getMethods();
            Object tr = client.textRenderer;
            for (Method m : methods) {
                String n = m.getName().toLowerCase();
                if (!(n.contains("drawtext") || n.equals("draw") || n.contains("drawstring"))) continue;
                Class<?>[] pts = m.getParameterTypes();
                try {
                    // common signature: drawText(TextRenderer, OrderedText, int, int, int, boolean) など
                    if (pts.length >= 5) {
                        // Try many variants safely by invoking and catching IllegalArgumentException
                        try {
                            if (tr != null && (pts[0].isInstance(tr) || pts[0].isAssignableFrom(tr.getClass()))) {
                                // OrderedText variant
                                if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                                    Object[] args;
                                    if (pts.length == 6) {
                                        args = new Object[]{tr, ordered, Math.round(xi), Math.round(yi), COLOR_RGB, false};
                                    } else {
                                        args = new Object[]{tr, ordered, Math.round(xi), Math.round(yi), COLOR_RGB};
                                    }
                                    m.invoke(drawCtx, args);
                                    return true;
                                }
                                // String
                                if (pts[1] == String.class) {
                                    Object[] args;
                                    if (pts.length == 6) {
                                        args = new Object[]{tr, str, Math.round(xi), Math.round(yi), COLOR_RGB, false};
                                    } else {
                                        args = new Object[]{tr, str, Math.round(xi), Math.round(yi), COLOR_RGB};
                                    }
                                    m.invoke(drawCtx, args);
                                    return true;
                                }
                                // Text
                                if (pts[1].getName().toLowerCase().contains("text")) {
                                    Object[] args;
                                    if (pts.length == 6) {
                                        args = new Object[]{tr, text, Math.round(xi), Math.round(yi), COLOR_RGB, false};
                                    } else {
                                        args = new Object[]{tr, text, Math.round(xi), Math.round(yi), COLOR_RGB};
                                    }
                                    m.invoke(drawCtx, args);
                                    return true;
                                }
                            }
                        } catch (IllegalArgumentException iae) {
                        } catch (InvocationTargetException ite) {
                        } catch (NoSuchMethodError nsm) {
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * TextRenderer 側の drawWithShadow / draw をリフレクションで探して呼ぶ（MatrixStack を使う variant 等も試す）
     * - provided matrixStack を使うバージョン（Scale を適用する）
     */
    private static boolean tryDrawWithTextRendererUsingMatrix(MinecraftClient client, Object matrixStack, String str, Text text, OrderedText ordered, float xf, float yf, int colorRgb, float scaleHint) {
        try {
            Object tr = client.textRenderer;
            if (tr == null || matrixStack == null) return false;

            // try to push/scale/pop on the matrix stack if available (so text draws scaled)
            boolean scaled = false;
            Method pushM = null, popM = null, scaleM = null;
            try {
                Class<?> msClass = matrixStack.getClass();
                for (Method mm : msClass.getMethods()) {
                    String n = mm.getName().toLowerCase();
                    if (n.equals("push")) pushM = mm;
                    if (n.equals("pop")) popM = mm;
                    if (n.equals("scale") && mm.getParameterCount() == 3) scaleM = mm;
                }
            } catch (Throwable ignored) {}

            try {
                if (pushM != null) pushM.invoke(matrixStack);
                if (scaleM != null) {
                    scaleM.invoke(matrixStack, scaleHint, scaleHint, scaleHint);
                    scaled = true;
                }
            } catch (Throwable ignored) {}

            // find any method named drawWithShadow/draw and try candidates that accept MatrixStack
            for (Method m : tr.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (!(n.contains("drawwithshadow") || n.equals("draw") || n.contains("drawstring"))) continue;
                Class<?>[] pts = m.getParameterTypes();
                try {
                    if (pts.length == 5 && pts[0].getName().toLowerCase().contains("matrix")) {
                        if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                            m.invoke(tr, matrixStack, ordered, xf / scaleHint, yf / scaleHint, colorRgb);
                            try { if (popM != null) popM.invoke(matrixStack); } catch (Throwable ignored) {}
                            return true;
                        } else if (pts[1].getName().toLowerCase().contains("text")) {
                            m.invoke(tr, matrixStack, text, xf / scaleHint, yf / scaleHint, colorRgb);
                            try { if (popM != null) popM.invoke(matrixStack); } catch (Throwable ignored) {}
                            return true;
                        } else if (pts[1] == String.class) {
                            m.invoke(tr, matrixStack, str, xf / scaleHint, yf / scaleHint, colorRgb);
                            try { if (popM != null) popM.invoke(matrixStack); } catch (Throwable ignored) {}
                            return true;
                        }
                    }
                } catch (IllegalArgumentException iae) {
                } catch (InvocationTargetException ite) {
                } catch (NoSuchMethodError nsm) {
                }
            }

            // restore matrix if we scaled but didn't draw
            try { if (scaled && popM != null) popM.invoke(matrixStack); } catch (Throwable ignored) {}

        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * TextRenderer を使った既存の汎用フォールバック（MatrixStack が作れないとき）
     */
    private static boolean tryDrawWithTextRendererFallback(MinecraftClient client, String str, Text text, OrderedText ordered, float xf, float yf, int colorRgb, float scaleHint) {
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
                            m.invoke(tr, matrix, ordered, xf / scaleHint, yf / scaleHint, colorRgb);
                            return true;
                        } else if (pts[1].getName().toLowerCase().contains("text")) {
                            m.invoke(tr, matrix, text, xf / scaleHint, yf / scaleHint, colorRgb);
                            return true;
                        } else if (pts[1] == String.class) {
                            m.invoke(tr, matrix, str, xf / scaleHint, yf / scaleHint, colorRgb);
                            return true;
                        }
                    }
                    if (pts.length == 4 && pts[0] == String.class) {
                        // (String, float, float, int)
                        m.invoke(tr, str, xf, yf, colorRgb);
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

    // MatrixStack を必要とするメソッドに渡すための簡易インスタンスを取得する（可能なら Minecraft の現在の MatrixStack を使う）
    private static Object getDefaultMatrixStack() {
        try {
            Class<?> msClass = Class.forName("net.minecraft.client.util.math.MatrixStack");
            Constructor<?> c = null;
            try {
                c = msClass.getDeclaredConstructor();
                c.setAccessible(true);
                return c.newInstance();
            } catch (NoSuchMethodException nsme) {
                // parameterless ctor 無し -> 作れない
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
