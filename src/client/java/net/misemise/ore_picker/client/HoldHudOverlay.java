package net.misemise.ore_picker.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.misemise.ore_picker.OrePickerLog;
import net.misemise.ore_picker.config.ConfigManager;

import java.lang.reflect.*;
import java.util.Objects;

/**
 * HoldHudOverlay - 位置修正版 + ログ出力制限（throttle）
 *
 * 主な改変:
 *  - DrawContext 経路で座標を SCALE で割っていたミスを修正（そのせいで左上に寄っていた）
 *  - ホットバーの上に表示されるように baseBottomOffset を調整（必要なら微調整して）
 *  - debug モードで座標/経路ログを出すが、フレーム毎の大量出力を throttle（間引き）する
 */
public final class HoldHudOverlay {
    private HoldHudOverlay() {}

    private static final long FADE_MS = 1200L; // 1.2 秒
    private static final float SCALE = 1.2f;
    private static final int COLOR_RGB = 0x9AFF66; // RRGGBB

    // 最小可視アルファ（小さすぎると 1 バイト丸めでちらつく）
    private static final float MIN_VISIBLE_OPACITY = 0.03f;

    // hold state
    private static volatile boolean lastHold = false;
    private static volatile long lastChangeTime = System.currentTimeMillis();

    // HUD 登録済みフラグ（HudRenderCallback 経由登録の有無）
    private static volatile boolean registeredViaHudCallback = false;

    // ログの間引き用
    private static volatile long lastHandlerLogMs = 0L;
    private static volatile long lastRenderLogMs = 0L;
    private static final long HANDLER_LOG_THROTTLE_MS = 1000L; // handler invoked は最大 1 秒に 1 回
    private static final long RENDER_LOG_THROTTLE_MS = 250L;  // renderOnTop の詳細は最大 250ms に 1 回

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
     * Fabric の HudRenderCallback に反射で登録（存在しない環境なら mixin で呼び出される想定）。
     */
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
                OrePickerLog.debug("HudRenderCallback.EVENT.register(...) not found - HUD not registered via API.");
                return;
            }

            final Class<?> listenerInterface = hudCallbackCls;

            InvocationHandler handler = (proxy, method, args) -> {
                boolean debug = false;
                try { debug = (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug); } catch (Throwable ignored) {}

                if (debug) {
                    long now = System.currentTimeMillis();
                    if (now - lastHandlerLogMs > HANDLER_LOG_THROTTLE_MS) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("handler invoked - method=").append(method.getName()).append(" args=");
                        if (args == null) sb.append("null");
                        else {
                            sb.append("[");
                            for (int i = 0; i < args.length; i++) {
                                Object a = args[i];
                                sb.append(i).append(":").append(a == null ? "null" : a.getClass().getSimpleName());
                                if (i + 1 < args.length) sb.append(",");
                            }
                            sb.append("]");
                        }
                        OrePickerLog.hud(sb.toString());
                        lastHandlerLogMs = now;
                    }
                }

                try {
                    try { if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) return null; } catch (Throwable ignored) {}

                    // Ore_pickerClient.localHold があれば反映
                    try {
                        Class<?> c = Class.forName("net.misemise.ore_picker.client.Ore_pickerClient");
                        Field f = c.getDeclaredField("localHold");
                        f.setAccessible(true);
                        boolean hold = f.getBoolean(null);
                        onHoldChanged(hold);
                    } catch (Throwable ignored) {}

                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client == null) return null;

                    Object ctx = (args != null && args.length > 0) ? args[0] : null;
                    float tickDelta = 0f;
                    if (args != null && args.length > 1 && args[1] instanceof Float) tickDelta = (Float) args[1];

                    renderOnTop(client, ctx, tickDelta);
                } catch (Throwable t) {
                    try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
                }
                return null;
            };

            Object listenerProxy = Proxy.newProxyInstance(
                    listenerInterface.getClassLoader(),
                    new Class<?>[]{listenerInterface},
                    handler
            );

            try { registerMethod.setAccessible(true); } catch (Throwable ignored) {}
            try {
                registerMethod.invoke(eventObj, listenerProxy);
                registeredViaHudCallback = true;
                OrePickerLog.info("HUD overlay registered (via HudRenderCallback).");
            } catch (Throwable ite) {
                OrePickerLog.debug("HudRenderCallback registration failed (non-fatal).");
                try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) ite.printStackTrace(); } catch (Throwable ignored) {}
            }
        } catch (ClassNotFoundException cnf) {
            OrePickerLog.debug("HudRenderCallback not present at runtime; rely on mixin fallback.");
        } catch (Throwable t) {
            OrePickerLog.error("register() unexpected error:", t);
        }
    }

    /**
     * 描画エントリ。mixin または HudRenderCallback から呼び出して下さい。
     * matrixOrDrawContext は DrawContext / MatrixStack / null など。
     */
    public static void renderOnTop(MinecraftClient client, Object matrixOrDrawContext, float tickDelta) {
        try {
            boolean debug = false;
            try { debug = (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug); } catch (Throwable ignored) {}

            try { if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) {
                if (debug) OrePickerLog.hud("renderOnTop skipped: enableHudOverlay=false");
                return;
            } } catch (Throwable ignored) {}

            float opacity = computeOpacity();
            if (opacity <= 0f) {
                if (debug) {
                    long now = System.currentTimeMillis();
                    if (now - lastRenderLogMs > RENDER_LOG_THROTTLE_MS) {
                        OrePickerLog.hud("opacity <= 0, skip");
                        lastRenderLogMs = now;
                    }
                }
                return;
            }
            if (opacity < MIN_VISIBLE_OPACITY) {
                if (debug) {
                    long now = System.currentTimeMillis();
                    if (now - lastRenderLogMs > RENDER_LOG_THROTTLE_MS) {
                        OrePickerLog.hud("opacity " + opacity + " below MIN_VISIBLE_OPACITY, skip");
                        lastRenderLogMs = now;
                    }
                }
                return;
            }

            if (client == null) return;

            String textStr = "OrePicker: Running";
            Text labelText = Text.literal(textStr);
            OrderedText ordered = labelText.asOrderedText();

            int sw = client.getWindow().getScaledWidth();
            int sh = client.getWindow().getScaledHeight();

            int textWidth = tryGetTextWidth(client, textStr, ordered, labelText);
            int fontH = tryGetFontHeight(client);

            // ここを調整してホットバーの上に出す
            float baseBottomOffset = 44f; // ホットバー + マージン相当。微調整してね。

            float xf = (sw / 2f) - (textWidth * SCALE / 2f);
            float yf = (sh - baseBottomOffset - (fontH * SCALE));

            int alphaByte = Math.round(opacity * 255f);
            alphaByte = alphaByte & 0xFF;
            int tint = (alphaByte << 24) | COLOR_RGB;

            if (debug) {
                long now = System.currentTimeMillis();
                if (now - lastRenderLogMs > RENDER_LOG_THROTTLE_MS) {
                    OrePickerLog.hud("renderOnTop: opacity=" + opacity + " alphaByte=" + alphaByte +
                            " texW=" + textWidth + " sw=" + sw + " sh=" + sh + " xf=" + xf + " yf=" + yf +
                            " ctx=" + (matrixOrDrawContext == null ? "null" : matrixOrDrawContext.getClass().getSimpleName()));
                    lastRenderLogMs = now;
                }
            }

            // depth を無効化して上に描く（多くのケースでチャットより上に出る）
            try { RenderSystem.disableDepthTest(); } catch (Throwable ignored) {}
            try { RenderSystem.enableBlend(); } catch (Throwable ignored) {}

            RenderSystem.setShaderColor(
                    ((COLOR_RGB >> 16) & 0xFF) / 255.0f,
                    ((COLOR_RGB >> 8) & 0xFF) / 255.0f,
                    (COLOR_RGB & 0xFF) / 255.0f,
                    opacity
            );

            boolean drawn = false;

            // matrixStack 系
            if (matrixOrDrawContext != null && matrixOrDrawContext.getClass().getName().toLowerCase().contains("matrix")) {
                drawn = tryDrawWithTextRendererUsingMatrix(client, matrixOrDrawContext, textStr, labelText, ordered, xf, yf, tint, SCALE);
                if (debug) {
                    long now = System.currentTimeMillis();
                    if (now - lastRenderLogMs > RENDER_LOG_THROTTLE_MS) OrePickerLog.hud("tryDrawWithTextRendererUsingMatrix -> " + drawn);
                }
                if (!drawn) {
                    drawn = tryDrawWithTextRendererFallback(client, textStr, labelText, ordered, xf, yf, tint, SCALE);
                    if (debug) {
                        long now = System.currentTimeMillis();
                        if (now - lastRenderLogMs > RENDER_LOG_THROTTLE_MS) OrePickerLog.hud("tryDrawWithTextRendererFallback -> " + drawn);
                    }
                }
            } else {
                // DrawContext（あるいは DrawContext ライク）経路 — ここで座標は SCALE で割らない（重要）
                if (matrixOrDrawContext != null) {
                    drawn = tryDrawWithDrawContextReflection(matrixOrDrawContext, client, textStr, labelText, ordered, xf, yf, tint);
                    if (debug) {
                        long now = System.currentTimeMillis();
                        if (now - lastRenderLogMs > RENDER_LOG_THROTTLE_MS) OrePickerLog.hud("tryDrawWithDrawContextReflection -> " + drawn);
                    }
                }
                if (!drawn) {
                    drawn = tryDrawWithTextRendererFallback(client, textStr, labelText, ordered, xf, yf, tint, SCALE);
                    if (debug) {
                        long now = System.currentTimeMillis();
                        if (now - lastRenderLogMs > RENDER_LOG_THROTTLE_MS) OrePickerLog.hud("tryDrawWithTextRendererFallback (2) -> " + drawn);
                    }
                }
            }

            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            try { RenderSystem.enableDepthTest(); } catch (Throwable ignored) {}
            try { RenderSystem.disableBlend(); } catch (Throwable ignored) {}

            if (debug && !drawn) {
                long now = System.currentTimeMillis();
                if (now - lastRenderLogMs > RENDER_LOG_THROTTLE_MS) OrePickerLog.hud("WARNING: no drawing method succeeded this frame.");
            }

        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
    }

    // ----------------- helpers -----------------
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

    private static boolean tryDrawWithDrawContextReflection(Object drawCtx, MinecraftClient client, String str, Text text, OrderedText ordered, float xi, float yi, int tint) {
        try {
            Method[] methods = drawCtx.getClass().getMethods();
            Object tr = client.textRenderer;
            for (Method m : methods) {
                String n = m.getName().toLowerCase();
                if (!(n.contains("drawtext") || n.equals("draw") || n.contains("drawstring"))) continue;
                Class<?>[] pts = m.getParameterTypes();
                try {
                    if (pts.length >= 5) {
                        try {
                            if (tr != null && (pts[0].isInstance(tr) || pts[0].isAssignableFrom(tr.getClass()))) {
                                if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                                    Object[] args = (pts.length == 6) ? new Object[]{tr, ordered, Math.round(xi), Math.round(yi), tint, false} : new Object[]{tr, ordered, Math.round(xi), Math.round(yi), tint};
                                    m.invoke(drawCtx, args);
                                    return true;
                                }
                                if (pts[1] == String.class) {
                                    Object[] args = (pts.length == 6) ? new Object[]{tr, str, Math.round(xi), Math.round(yi), tint, false} : new Object[]{tr, str, Math.round(xi), Math.round(yi), tint};
                                    m.invoke(drawCtx, args);
                                    return true;
                                }
                                if (pts[1].getName().toLowerCase().contains("text")) {
                                    Object[] args = (pts.length == 6) ? new Object[]{tr, text, Math.round(xi), Math.round(yi), tint, false} : new Object[]{tr, text, Math.round(xi), Math.round(yi), tint};
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

    private static boolean tryDrawWithTextRendererUsingMatrix(MinecraftClient client, Object matrixStack, String str, Text text, OrderedText ordered, float xf, float yf, int tint, float scaleHint) {
        try {
            Object tr = client.textRenderer;
            if (tr == null || matrixStack == null) return false;

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

            boolean scaled = false;
            try {
                if (pushM != null) pushM.invoke(matrixStack);
                if (scaleM != null) { scaleM.invoke(matrixStack, scaleHint, scaleHint, scaleHint); scaled = true; }
            } catch (Throwable ignored) {}

            for (Method m : tr.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (!(n.contains("drawwithshadow") || n.equals("draw") || n.contains("drawstring"))) continue;
                Class<?>[] pts = m.getParameterTypes();
                try {
                    if (pts.length == 5 && pts[0].getName().toLowerCase().contains("matrix")) {
                        if (pts[1].getName().toLowerCase().contains("orderedtext")) {
                            m.invoke(tr, matrixStack, ordered, xf / scaleHint, yf / scaleHint, tint);
                            try { if (popM != null) popM.invoke(matrixStack); } catch (Throwable ignored) {}
                            return true;
                        } else if (pts[1].getName().toLowerCase().contains("text")) {
                            m.invoke(tr, matrixStack, text, xf / scaleHint, yf / scaleHint, tint);
                            try { if (popM != null) popM.invoke(matrixStack); } catch (Throwable ignored) {}
                            return true;
                        } else if (pts[1] == String.class) {
                            m.invoke(tr, matrixStack, str, xf / scaleHint, yf / scaleHint, tint);
                            try { if (popM != null) popM.invoke(matrixStack); } catch (Throwable ignored) {}
                            return true;
                        }
                    }
                } catch (IllegalArgumentException iae) {
                } catch (InvocationTargetException ite) {
                } catch (NoSuchMethodError nsm) {
                }
            }

            try { if (scaled && popM != null) popM.invoke(matrixStack); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
        return false;
    }

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
                            m.invoke(tr, matrix, ordered, xf / scaleHint, yf / scaleHint, tint);
                            return true;
                        } else if (pts[1].getName().toLowerCase().contains("text")) {
                            m.invoke(tr, matrix, text, xf / scaleHint, yf / scaleHint, tint);
                            return true;
                        } else if (pts[1] == String.class) {
                            m.invoke(tr, matrix, str, xf / scaleHint, yf / scaleHint, tint);
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
