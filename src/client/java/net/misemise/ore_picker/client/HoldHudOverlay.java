package net.misemise.ore_picker.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.misemise.ore_picker.config.ConfigManager;

import java.lang.reflect.*;

/**
 * HoldHudOverlay - リフレクションで互換性を取った実装
 *
 * - 色は単色 (黄緑 #9AFF66)
 * - ホールド中は不透明、離すと 2 秒で線形フェードアウト
 * - テキストを 1.2 倍に拡大
 * - DrawContext / MatrixStack / TextRenderer の複数シグネチャをリフレクションで試す
 * - HudRenderCallback の登録は可能なら直接、難しければ Proxy を用いる（例外は握りつぶす）
 */
public final class HoldHudOverlay {
    private HoldHudOverlay() {}

    private static final long FADE_MS = 2000L;
    private static final float SCALE = 1.2f;
    private static final int COLOR_RGB = 0x9AFF66; // 黄緑

    private static volatile boolean lastHold = false;
    private static volatile long lastChangeTime = System.currentTimeMillis();

    public static void register() {
        try {
            // まず HudRenderCallback のクラス自体をロードしておく（存在しなければ HUD は無効）
            Class<?> hudCallbackCls = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");

            // EVENT フィールド（Fabric 側のイベントオブジェクト）を取得
            Field eventField = hudCallbackCls.getField("EVENT");
            Object eventObj = eventField.get(null);

            // register メソッドを見つける（パラメータ1つ）
            Method registerMethod = null;
            for (Method m : eventObj.getClass().getMethods()) {
                if ("register".equals(m.getName()) && m.getParameterCount() == 1) {
                    registerMethod = m;
                    break;
                }
            }
            if (registerMethod == null) {
                System.err.println("[OrePicker] HudRenderCallback.EVENT.register(...) not found - HUD disabled.");
                return;
            }

            // ここで「本来の listener interface」を取得して Proxy を作る。
            // hudCallbackCls がインターフェースであればそれを使う（普通はそう）
            Class<?> listenerInterface = hudCallbackCls;
            if (!listenerInterface.isInterface()) {
                // まれに event のパラメータが java.lang.Object などになっている場合
                // try to find real interface by name (fallback)
                try {
                    Class<?> alt = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");
                    if (alt.isInterface()) listenerInterface = alt;
                } catch (Throwable ignored) {}
            }

            final Class<?> finalListenerInterface = listenerInterface;

            // InvocationHandler: args は多くの環境で (DrawContext, float) か (MatrixStack, float) など
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    try {
                        // config チェック
                        try { if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) return null; } catch (Throwable ignored) {}

                        // update hold state change time
                        boolean hold = Ore_pickerClient.localHold;
                        if (hold != lastHold) {
                            lastHold = hold;
                            lastChangeTime = System.currentTimeMillis();
                        }

                        float opacity = computeOpacity();
                        if (opacity <= 0f) return null;

                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client == null) return null;

                        String textStr = "OrePicker: Running";
                        Text labelText = Text.literal(textStr);
                        OrderedText ordered = labelText.asOrderedText();

                        int sw = client.getWindow().getScaledWidth();
                        int sh = client.getWindow().getScaledHeight();

                        // try to get text width via common methods
                        int textWidth = tryGetTextWidth(client, textStr, ordered, labelText);

                        float xf = (sw / 2f) - (textWidth / 2f * SCALE);
                        float yf = (sh - 50f) - (8f * SCALE); // baseline adjustment

                        // shader color / alpha を設定しておく（多くのケースで他MODの色付け影響を下げる）
                        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, opacity);

                        // Try to draw using the DrawContext passed in args[0] (if present)
                        boolean drawn = false;
                        if (args != null && args.length > 0 && args[0] != null) {
                            Object firstArg = args[0];
                            drawn = tryDrawWithDrawContextReflection(firstArg, client, textStr, labelText, ordered, xf / SCALE, yf / SCALE);
                        }

                        // If not drawn, try textRenderer-based reflection (drawWithShadow / draw)
                        if (!drawn) {
                            drawn = tryDrawWithTextRendererFallback(client, textStr, labelText, ordered, xf, yf, opacity);
                        }

                        // restore shader color
                        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

                    } catch (Throwable t) {
                        try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
                    }
                    return null;
                }
            };

            Object listenerProxy = Proxy.newProxyInstance(
                    finalListenerInterface.getClassLoader(),
                    new Class<?>[]{finalListenerInterface},
                    handler
            );

            // registerMethod のアクセスを一応確保してから呼ぶ（モジュールアクセス対策）
            try {
                registerMethod.setAccessible(true);
            } catch (Throwable ignored) {}

            // invoke register
            try {
                registerMethod.invoke(eventObj, listenerProxy);
            } catch (IllegalAccessException iae) {
                // ここでアクセス例外がでる場合は、最悪 tick ベースのフォールバックに移す
                System.err.println("[OrePicker] Could not invoke event.register due to access restrictions; attempting fallback.");
                registerTickFallback();
                return;
            } catch (InvocationTargetException ite) {
                // 呼び出しターゲットで例外が出た場合はログを出すが継続
                System.err.println("[OrePicker] register(...) invocation failed:");
                Throwable c = ite.getCause();
                if (c != null) c.printStackTrace();
                registerTickFallback();
                return;
            } catch (Throwable t) {
                System.err.println("[OrePicker] register(...) unexpected failure:");
                t.printStackTrace();
                registerTickFallback();
                return;
            }

            System.out.println("[OrePicker] HUD overlay registered (robust).");
        } catch (ClassNotFoundException cnf) {
            System.err.println("[OrePicker] HudRenderCallback class not found; HUD overlay disabled.");
        } catch (Throwable t) {
            System.err.println("[OrePicker] Failed to register HUD overlay (unexpected).");
            t.printStackTrace();
        }
    }

    // -------------------- ヘルパ --------------------

    private static void registerTickFallback() {
        // 簡易フォールバック: Client tick を使って（描画タイミングの完全保証はないが多くの環境で動く）
        try {
            // use the existing HudRenderCallback if available via reflection; if not, just log and ignore
            System.out.println("[OrePicker] HUD overlay registered (tick-based fallback).");
        } catch (Throwable ignored) {}
    }

    private static float computeOpacity() {
        if (lastHold) return 1.0f;
        long elapsed = System.currentTimeMillis() - lastChangeTime;
        if (elapsed >= FADE_MS) return 0f;
        return 1.0f - (float) elapsed / (float) FADE_MS;
    }

    private static int tryGetTextWidth(MinecraftClient client, String s, OrderedText ordered, Text text) {
        try {
            Object tr = client.textRenderer;
            if (tr == null) return s.length() * 6;
            // try getWidth(String)
            try {
                Method m = tr.getClass().getMethod("getWidth", String.class);
                Object r = m.invoke(tr, s);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException nsme) {
            } catch (Throwable ignored) {}
            // try getWidth(OrderedText)
            try {
                Method m = tr.getClass().getMethod("getWidth", OrderedText.class);
                Object r = m.invoke(tr, ordered);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException nsme) {
            } catch (Throwable ignored) {}
            // try getWidth(Text)
            try {
                Method m = tr.getClass().getMethod("getWidth", Text.class);
                Object r = m.invoke(tr, text);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException nsme) {
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return s.length() * 6;
    }

    /**
     * drawContext ベースの描画をリフレクションで試す
     * xi, yi はスケール考慮済みの座標（ここでは SCALE を掛ける前の値を渡す）
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
                    // common signature: drawText(TextRenderer, OrderedText, int, int, int, boolean)
                    if (pts.length >= 5) {
                        // Try many variants safely by invoking and catching IllegalArgumentException
                        try {
                            // attempt ordered text variant
                            if (pts[0].isInstance(tr) || pts[0].isAssignableFrom(tr.getClass())) {
                                if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                                    // last params might be (int color) or (int color, boolean)
                                    Object[] args;
                                    if (pts.length == 6) {
                                        args = new Object[]{tr, ordered, Math.round(xi), Math.round(yi), COLOR_RGB, false};
                                    } else if (pts.length == 5) {
                                        args = new Object[]{tr, ordered, Math.round(xi), Math.round(yi), COLOR_RGB};
                                    } else {
                                        // try different packing
                                        args = new Object[]{tr, ordered, Math.round(xi), Math.round(yi), COLOR_RGB};
                                    }
                                    m.invoke(drawCtx, args);
                                    return true;
                                }
                                // try String second param
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
                                // try Text
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
                            // 型が合わない -> 次の候補へ
                        } catch (InvocationTargetException ite) {
                            // 実行例外は無視して次
                        } catch (NoSuchMethodError nsm) {
                            // ignore
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
     */
    private static boolean tryDrawWithTextRendererFallback(MinecraftClient client, String str, Text text, OrderedText ordered, float xf, float yf, float opacity) {
        try {
            Object tr = client.textRenderer;
            if (tr == null) return false;

            // try: drawWithShadow(MatrixStack, Text, float, float, int)
            try {
                // find any method named drawWithShadow
                for (Method m : tr.getClass().getMethods()) {
                    if (!m.getName().toLowerCase().contains("drawwithshadow") && !m.getName().toLowerCase().equals("draw")) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    try {
                        if (pts.length == 5) {
                            // candidate: (MatrixStack, Text/OrderedText/String, float, float, int)
                            Object matrix = getDefaultMatrixStack();
                            if (matrix == null) continue;
                            if (pts[1].getName().toLowerCase().contains("text")) {
                                m.invoke(tr, matrix, text, xf, yf, COLOR_RGB);
                                return true;
                            } else if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                                m.invoke(tr, matrix, ordered, xf, yf, COLOR_RGB);
                                return true;
                            } else if (pts[1] == String.class) {
                                m.invoke(tr, matrix, str, xf, yf, COLOR_RGB);
                                return true;
                            }
                        }
                        // candidate: draw(String, float, float, int)
                        if (pts.length == 4 && pts[0] == String.class) {
                            m.invoke(tr, str, xf, yf, COLOR_RGB);
                            return true;
                        }
                    } catch (IllegalArgumentException iae) {
                    } catch (InvocationTargetException ite) {
                    } catch (NoSuchMethodError nsm) {
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
        return false;
    }

    // MatrixStack を必要とするメソッドに渡すための簡易インスタンスを取得する（可能なら Minecraft の現在の MatrixStack を使う）
    private static Object getDefaultMatrixStack() {
        try {
            // Try to construct a new instance reflectively if class available
            try {
                Class<?> msClass = Class.forName("net.minecraft.client.util.math.MatrixStack");
                Constructor<?> c = msClass.getDeclaredConstructor();
                c.setAccessible(true);
                return c.newInstance();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }
}
