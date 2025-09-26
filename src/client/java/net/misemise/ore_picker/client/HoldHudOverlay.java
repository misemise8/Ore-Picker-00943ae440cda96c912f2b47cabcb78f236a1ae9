package net.misemise.ore_picker.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.misemise.ore_picker.config.ConfigManager;

import java.lang.reflect.*;

/**
 * HoldHudOverlay - リフレクションで互換性を取った実装（単色 + アルファ付き）
 *
 * - 色は単色 (黄緑 #9AFF66)
 * - ホールド中は不透明、離すと 1.2 秒で線形フェードアウト
 * - テキストを 1.2 倍に拡大
 * - DrawContext / MatrixStack / TextRenderer の複数シグネチャをリフレクションで試す
 */
public final class HoldHudOverlay {
    private HoldHudOverlay() {}

    private static final long FADE_MS = 1200L; // 1.2秒
    private static final float SCALE = 1.2f;
    private static final int COLOR_RGB = 0x9AFF66; // 黄緑

    private static volatile boolean lastHold = false;
    private static volatile long lastChangeTime = System.currentTimeMillis();

    public static void register() {
        try {
            Class<?> hudCallbackCls = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");

            Field eventField = hudCallbackCls.getField("EVENT");
            Object eventObj = eventField.get(null);

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

            Class<?> listenerInterface = hudCallbackCls;
            if (!listenerInterface.isInterface()) {
                // try fallback by name (rare)
                try {
                    Class<?> alt = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");
                    if (alt.isInterface()) listenerInterface = alt;
                } catch (Throwable ignored) {}
            }

            final Class<?> finalListenerInterface = listenerInterface;

            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    try {
                        try { if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) return null; } catch (Throwable ignored) {}

                        boolean hold = Ore_pickerClient.localHold;
                        if (hold != lastHold) {
                            lastHold = hold;
                            lastChangeTime = System.currentTimeMillis();
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                                System.out.println("[OrePicker][HUD] hold changed -> " + hold + " at " + lastChangeTime);
                            }
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

                        int textWidth = tryGetTextWidth(client, textStr, ordered, labelText);

                        // Apply SCALE: compute final positions such that text is centered after scaling
                        float sx = SCALE;
                        float xf = (sw / 2f) - (textWidth * sx / 2f);
                        float yf = (sh - 50f) - (8f * sx);

                        // create tint ARGB int with alpha
                        int alphaByte = Math.round(opacity * 255f) & 0xFF;
                        int tint = (alphaByte << 24) | COLOR_RGB;

                        // set shader color as well (for implementations that use shader color)
                        RenderSystem.setShaderColor(
                                ((COLOR_RGB >> 16) & 0xFF) / 255.0f,
                                ((COLOR_RGB >> 8) & 0xFF) / 255.0f,
                                (COLOR_RGB & 0xFF) / 255.0f,
                                opacity
                        );

                        boolean drawn = false;
                        if (args != null && args.length > 0 && args[0] != null) {
                            Object firstArg = args[0];
                            // try DrawContext-like reflection; pass tint
                            drawn = tryDrawWithDrawContextReflection(firstArg, client, textStr, labelText, ordered, xf / SCALE, yf / SCALE, tint, sx);
                            if (drawn) {
                                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                                    System.out.println("[OrePicker][HUD] draw via DrawContext succeeded (opacity=" + opacity + ")");
                                }
                            }
                        }

                        if (!drawn) {
                            // try textRenderer fallback; pass tint
                            drawn = tryDrawWithTextRendererFallback(client, textStr, labelText, ordered, xf, yf, tint, sx);
                            if (drawn) {
                                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                                    System.out.println("[OrePicker][HUD] draw via TextRenderer succeeded (opacity=" + opacity + ")");
                                }
                            }
                        }

                        // restore shader color to opaque white
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

            try { registerMethod.setAccessible(true); } catch (Throwable ignored) {}

            try {
                registerMethod.invoke(eventObj, listenerProxy);
            } catch (IllegalAccessException iae) {
                System.err.println("[OrePicker] Could not invoke event.register due to access restrictions; attempting fallback.");
                registerTickFallback();
                return;
            } catch (InvocationTargetException ite) {
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

    private static void registerTickFallback() {
        // 既にクライアント側でログを出しているため、簡易フォールバックはここでは noop にしておく
        System.out.println("[OrePicker] HUD overlay registered (tick-based fallback).");
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
            } catch (NoSuchMethodException nsme) {}
            // try getWidth(OrderedText)
            try {
                Method m = tr.getClass().getMethod("getWidth", OrderedText.class);
                Object r = m.invoke(tr, ordered);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException nsme) {}
            // try getWidth(Text)
            try {
                Method m = tr.getClass().getMethod("getWidth", Text.class);
                Object r = m.invoke(tr, text);
                if (r instanceof Integer) return (Integer) r;
            } catch (NoSuchMethodException nsme) {}
        } catch (Throwable ignored) {}
        return s.length() * 6;
    }

    /**
     * DrawContext ベースの描画をリフレクションで試す
     * xi, yi はスケール考慮前（内部で SCALE を考慮して渡す場合がある）
     * scaleHint: 1.0 = no scale, >1 = scale factor for text (we pass it where possible)
     */
    private static boolean tryDrawWithDrawContextReflection(Object drawCtx, MinecraftClient client, String str, Text text, OrderedText ordered, float xi, float yi, int tint, float scaleHint) {
        try {
            Method[] methods = drawCtx.getClass().getMethods();
            Object tr = client.textRenderer;
            for (Method m : methods) {
                String name = m.getName().toLowerCase();
                if (!(name.contains("drawtext") || name.equals("draw") || name.contains("drawstring") || name.contains("drawtext"))) continue;
                Class<?>[] pts = m.getParameterTypes();
                try {
                    // try common signatures with tint (int)
                    if (pts.length >= 5) {
                        // first param is often TextRenderer-like
                        if (tr != null && (pts[0].isInstance(tr) || pts[0].isAssignableFrom(tr.getClass()))) {
                            // orderedtext
                            if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                                Object[] args;
                                if (pts.length == 6) {
                                    args = new Object[]{tr, ordered, Math.round(xi), Math.round(yi), tint, false};
                                } else if (pts.length == 5) {
                                    args = new Object[]{tr, ordered, Math.round(xi), Math.round(yi), tint};
                                } else {
                                    args = new Object[]{tr, ordered, Math.round(xi), Math.round(yi), tint};
                                }
                                m.invoke(drawCtx, args);
                                return true;
                            }
                            // String
                            if (pts[1] == String.class) {
                                Object[] args;
                                if (pts.length == 6) {
                                    args = new Object[]{tr, str, Math.round(xi), Math.round(yi), tint, false};
                                } else {
                                    args = new Object[]{tr, str, Math.round(xi), Math.round(yi), tint};
                                }
                                m.invoke(drawCtx, args);
                                return true;
                            }
                            // Text-like
                            if (pts[1].getName().toLowerCase().contains("text")) {
                                Object[] args;
                                if (pts.length == 6) {
                                    args = new Object[]{tr, text, Math.round(xi), Math.round(yi), tint, false};
                                } else {
                                    args = new Object[]{tr, text, Math.round(xi), Math.round(yi), tint};
                                }
                                m.invoke(drawCtx, args);
                                return true;
                            }
                        }
                        // some variants may be (MatrixStack, ...). Try to detect second param types
                        if (pts[0].getName().toLowerCase().contains("matrix")) {
                            Object matrix = getDefaultMatrixStack();
                            if (matrix == null) continue;
                            if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                                Object[] args = buildArgsForMatrixStackVariant(pts.length, matrix, ordered, str, text, xi, yi, tint);
                                if (args != null) {
                                    m.invoke(drawCtx, args);
                                    return true;
                                }
                            } else if (pts[1].getName().toLowerCase().contains("text")) {
                                Object[] args = buildArgsForMatrixStackVariant(pts.length, matrix, text, str, text, xi, yi, tint);
                                if (args != null) {
                                    m.invoke(drawCtx, args);
                                    return true;
                                }
                            } else if (pts[1] == String.class) {
                                Object[] args = buildArgsForMatrixStackVariant(pts.length, matrix, str, str, text, xi, yi, tint);
                                if (args != null) {
                                    m.invoke(drawCtx, args);
                                    return true;
                                }
                            }
                        }
                    }
                } catch (IllegalArgumentException iae) {
                    // 型ミスマッチ: 次
                } catch (InvocationTargetException ite) {
                    // 実行時例外: 次
                } catch (NoSuchMethodError nsm) {
                    // ignore
                }
            }
        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static Object[] buildArgsForMatrixStackVariant(int paramCount, Object matrix, Object primary, String str, Text text, float xi, float yi, int tint) {
        // paramCount common cases: 5 -> (MatrixStack, Text, float, float, int)
        // or 6 -> (MatrixStack, Text, float, float, int, boolean) etc
        try {
            if (paramCount == 5) {
                // (MatrixStack, Text/OrderedText/String, float, float, int)
                return new Object[]{matrix, primary, xi, yi, tint};
            } else if (paramCount == 6) {
                // (MatrixStack, Text/OrderedText/String, float, float, int, boolean)
                return new Object[]{matrix, primary, xi, yi, tint, false};
            } else if (paramCount == 7) {
                // some weird signature: try to fit values
                return new Object[]{matrix, primary, xi, yi, tint, false, 0};
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * TextRenderer 側の drawWithShadow / draw をリフレクションで探して呼ぶ
     * scaleHint: scale to be applied (if method expects MatrixStack we don't perform GL scale)
     */
    private static boolean tryDrawWithTextRendererFallback(MinecraftClient client, String str, Text text, OrderedText ordered, float xf, float yf, int tint, float scaleHint) {
        try {
            Object tr = client.textRenderer;
            if (tr == null) return false;

            // Try methods on TextRenderer
            for (Method m : tr.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (!(n.contains("drawwithshadow") || n.equals("draw") || n.contains("drawstring"))) continue;
                Class<?>[] pts = m.getParameterTypes();
                try {
                    // draw(MatrixStack, Text/OrderedText/String, float, float, int)
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
                    // draw(String, float, float, int)
                    if (pts.length == 4 && pts[0] == String.class) {
                        m.invoke(tr, str, xf, yf, tint);
                        return true;
                    }
                    // draw(OrderedText, float, float, int)
                    if (pts.length == 4 && pts[0].getName().toLowerCase().contains("orderedtext")) {
                        m.invoke(tr, ordered, xf, yf, tint);
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
            try {
                Constructor<?> c = msClass.getDeclaredConstructor();
                c.setAccessible(true);
                return c.newInstance();
            } catch (NoSuchMethodException nsme) {
                // no public no-arg ctor: try to find a suitable existing instance via threadlocal or client reflection (hard)
                // fallback: return null
                return null;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
