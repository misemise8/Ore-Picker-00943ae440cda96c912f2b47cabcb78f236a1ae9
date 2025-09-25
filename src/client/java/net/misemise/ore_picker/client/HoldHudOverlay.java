package net.misemise.ore_picker.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.misemise.ore_picker.config.ConfigManager;

import java.lang.reflect.*;
import java.util.Locale;
import java.util.function.Function;

/**
 * HoldHudOverlay - 修正版
 *
 * - フェードは時間ベース（2秒）で行い、アルファではなく RGB を暗くして見た目のフェードにする
 * - Draw-hook を反射で登録（成功すれば DrawContext 経路を使う）
 * - DrawContext 経路では 1.2x スケーリングを試みる
 * - setOverlayMessage のフォールバックも残す（色は近似）
 */
public final class HoldHudOverlay {
    private HoldHudOverlay() {}

    // フェード時間（ミリ秒）
    private static final long FADE_MS = 2000L;

    // 基本の黄緑 RGB
    private static final int BASE_R = 0x9A;
    private static final int BASE_G = 0xFF;
    private static final int BASE_B = 0x66;

    // scale for DrawContext path (1.2x)
    private static final float SCALE = 1.2f;

    // last change tracking (時間ベース)
    private static volatile boolean lastHoldState = false;
    private static volatile long lastHoldChangeTime = System.currentTimeMillis();

    public static void register() {
        boolean drawHookRegistered = tryRegisterDrawHook();
        if (drawHookRegistered) {
            System.out.println("[OrePicker] HUD overlay registered (draw-hook).");
            return;
        }

        // tick-based fallback (テスト用かつ互換用)
        try {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                try {
                    if (client == null) return;
                    try { if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) return; } catch (Throwable ignored) {}

                    boolean hold = Ore_pickerClient.localHold;
                    updateHoldState(hold);

                    float opacity = computeOpacity();

                    if (opacity <= 0f) return;

                    int r = Math.max(0, Math.min(255, (int) (BASE_R * opacity)));
                    int g = Math.max(0, Math.min(255, (int) (BASE_G * opacity)));
                    int b = Math.max(0, Math.min(255, (int) (BASE_B * opacity)));
                    int rgbScaled = (r << 16) | (g << 8) | b;

                    // フォールバック: inGameHud#setOverlayMessage(Text, boolean) を色付き Text で試す
                    Object inGameHud = client.inGameHud;
                    if (inGameHud != null) {
                        String s = "OrePicker: Running";
                        trySetOverlayMessageWithColor(inGameHud, s, rgbScaled);
                    }
                } catch (Throwable t) {
                    try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
                }
            });

            System.out.println("[OrePicker] HUD overlay registered (tick-based).");
        } catch (Throwable t) {
            System.err.println("[OrePicker] Failed to register HUD overlay (both draw-hook and tick-based). HUD disabled.");
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
        }
    }

    // update hold state/time
    private static void updateHoldState(boolean hold) {
        if (hold != lastHoldState) {
            lastHoldState = hold;
            lastHoldChangeTime = System.currentTimeMillis();
        }
    }

    // compute opacity based on last change time (1.0 .. 0.0)
    private static float computeOpacity() {
        if (lastHoldState) return 1.0f;
        long elapsed = System.currentTimeMillis() - lastHoldChangeTime;
        if (elapsed >= FADE_MS) return 0f;
        return 1.0f - (float) elapsed / (float) FADE_MS;
    }

    // ---------------- Draw-hook registration ----------------
    private static boolean tryRegisterDrawHook() {
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
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    System.err.println("[OrePicker] HudRenderCallback.EVENT.register(...) not found - draw-hook disabled.");
                }
                return false;
            }

            Class<?> listenerType = registerMethod.getParameterTypes()[0];
            if (!listenerType.isInterface()) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    System.err.println("[OrePicker] HudRenderCallback listener type is not an interface; cannot create proxy.");
                }
                return false;
            }

            Object listenerProxy = Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[]{listenerType},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                if (args == null || args.length == 0) return null;
                                try { if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) return null; } catch (Throwable ignored) {}

                                boolean hold = Ore_pickerClient.localHold;
                                updateHoldState(hold);

                                float opacity = computeOpacity();
                                if (opacity <= 0f) return null;

                                MinecraftClient client = MinecraftClient.getInstance();
                                if (client == null) return null;

                                String textStr = "OrePicker: Running";

                                int r = Math.max(0, Math.min(255, (int) (BASE_R * opacity)));
                                int g = Math.max(0, Math.min(255, (int) (BASE_G * opacity)));
                                int b = Math.max(0, Math.min(255, (int) (BASE_B * opacity)));
                                int rgbScaled = (r << 16) | (g << 8) | b;

                                Object firstArg = args[0];
                                if (firstArg != null) {
                                    if (tryDrawWithContext(firstArg, client, textStr, rgbScaled, SCALE)) {
                                        return null; // 描画成功
                                    }
                                }

                                // フォールバック: inGameHud
                                trySetOverlayViaInGameHud(client, textStr, rgbScaled);
                            } catch (Throwable t) {
                                try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
                            }
                            return null;
                        }
                    }
            );

            try {
                registerMethod.invoke(eventObj, listenerProxy);
                return true;
            } catch (InvocationTargetException ite) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    System.err.println("[OrePicker] draw-hook registration InvocationTargetException cause:");
                    ite.getCause().printStackTrace();
                }
                return false;
            } catch (IllegalAccessException iae) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    System.err.println("[OrePicker] draw-hook registration IllegalAccessException:");
                    iae.printStackTrace();
                }
                return false;
            }
        } catch (ClassNotFoundException cnf) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                System.err.println("[OrePicker] HudRenderCallback class not found; draw-hook not available.");
            }
            return false;
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                System.err.println("[OrePicker] Unexpected while attempting draw-hook registration:");
                t.printStackTrace();
            }
            return false;
        }
    }

    // Draw via DrawContext-like object using scaled RGB (no alpha).
    private static boolean tryDrawWithContext(Object drawCtx, MinecraftClient client, String textStr, int rgbScaled, float scale) {
        try {
            // try to obtain matrices if available (push/scale/pop)
            Object matrices = null;
            try {
                Method getMatrices = drawCtx.getClass().getMethod("getMatrices");
                matrices = getMatrices.invoke(drawCtx);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
                matrices = drawCtx; // そのまま MatrixStack っぽい扱い
            }
            Method push = null;
            Method pop = null;
            Method scaleMethod = null;
            if (matrices != null) {
                Class<?> mcls = matrices.getClass();
                try { push = mcls.getMethod("push"); } catch (NoSuchMethodException ignored) {}
                try { pop = mcls.getMethod("pop"); } catch (NoSuchMethodException ignored) {}
                try { scaleMethod = mcls.getMethod("scale", float.class, float.class, float.class); } catch (NoSuchMethodException ignored) {}
            }

            if (push != null) {
                try { push.invoke(matrices); } catch (Throwable ignored) {}
            }
            if (scaleMethod != null) {
                try { scaleMethod.invoke(matrices, scale, scale, scale); } catch (Throwable ignored) {}
            }

            // 探索して最初に成功した drawText/draw を実行する
            Method[] methods = drawCtx.getClass().getMethods();
            for (Method m : methods) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("draw") && !name.contains("text")) continue;

                Class<?>[] pts = m.getParameterTypes();
                // 典型パターン: (TextRenderer, String/Text/OrderedText, int x, int y, int color) [+ boolean shadow]
                if (pts.length < 5 || pts.length > 7) continue;

                Object tr = client.textRenderer;
                if (!(pts[0].isAssignableFrom(tr.getClass()) || pts[0].isInstance(tr))) continue;

                Object second = null;
                if (pts[1] == String.class) {
                    second = textStr;
                } else if (isTextType(pts[1])) {
                    try {
                        Method literal = Text.class.getMethod("literal", String.class);
                        second = literal.invoke(null, textStr);
                    } catch (Throwable ignored) {}
                } else {
                    // try OrderedText
                    Object ordered = tryAsOrderedText(Text.literal(textStr));
                    if (ordered != null && pts[1].isInstance(ordered)) second = ordered;
                }
                if (second == null) continue;

                int sw = client.getWindow().getScaledWidth();
                int sh = client.getWindow().getScaledHeight();
                int textWidth = tryComputeTextWidth(client, Text.literal(textStr));
                int x = sw / 2 - (int) (textWidth * scale / 2.0f);
                int y = (int) (sh - 50 - 8 * scale);

                Object[] callArgs = new Object[pts.length];
                callArgs[0] = tr;
                callArgs[1] = second;
                // common positions
                if (pts.length >= 5) {
                    callArgs[2] = x;
                    callArgs[3] = y;
                    callArgs[4] = rgbScaled;
                }
                if (pts.length >= 6) {
                    Class<?> p5 = pts[5];
                    if (p5 == boolean.class || p5 == Boolean.class) {
                        callArgs[5] = Boolean.TRUE;
                    } else {
                        // 他の型なら 0 を入れてみる（安全策）
                        callArgs[5] = 0;
                    }
                }
                // invoke
                try {
                    m.invoke(drawCtx, callArgs);
                    if (pop != null) {
                        try { pop.invoke(matrices); } catch (Throwable ignored) {}
                    }
                    return true;
                } catch (IllegalArgumentException iae) {
                    // 型ミスマッチなら次を探す
                    continue;
                }
            }

            if (pop != null) {
                try { pop.invoke(matrices); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
        }
        return false;
    }

    // try setOverlayMessage(Text, boolean) with colored Text if possible
    private static boolean trySetOverlayMessageWithColor(Object inGameHud, String textStr, int rgbScaled) {
        try {
            Text t = buildColoredTextBestEffort(textStr, rgbScaled);
            for (Method m : inGameHud.getClass().getMethods()) {
                if (!"setOverlayMessage".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && isTextType(pts[0]) && (pts[1] == boolean.class || pts[1] == Boolean.class)) {
                    try {
                        m.invoke(inGameHud, t, true);
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                System.err.println("[OrePicker] trySetOverlayMessageWithColor failed:");
                t.printStackTrace();
            }
        }
        return false;
    }

    private static void trySetOverlayViaInGameHud(MinecraftClient client, String textStr, int rgbScaled) {
        try {
            Object inGameHud = client.inGameHud;
            if (inGameHud == null) return;
            Text t = buildColoredTextBestEffort(textStr, rgbScaled);
            for (Method m : inGameHud.getClass().getMethods()) {
                if (!"setOverlayMessage".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && isTextType(pts[0]) && (pts[1] == boolean.class || pts[1] == Boolean.class)) {
                    try {
                        m.invoke(inGameHud, t, true);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ex) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                System.err.println("[OrePicker] trySetOverlayViaInGameHud failed:");
                ex.printStackTrace();
            }
        }
    }

    // Build Text with color if possible; fallback to Formatting.GREEN
    private static Text buildColoredTextBestEffort(String s, int rgbScaled) {
        try {
            Class<?> textColorCls = Class.forName("net.minecraft.text.TextColor");
            Method fromRgb = textColorCls.getMethod("fromRgb", int.class);
            Object textColor = fromRgb.invoke(null, rgbScaled);

            Method literal = Text.class.getMethod("literal", String.class);
            Text base = (Text) literal.invoke(null, s);

            try {
                Method styled = Text.class.getMethod("styled", Function.class);
                Function<Object, Object> func = styleObj -> {
                    try {
                        Method withColor = styleObj.getClass().getMethod("withColor", textColorCls);
                        return withColor.invoke(styleObj, textColor);
                    } catch (Throwable ex) {
                        return styleObj;
                    }
                };
                return (Text) styled.invoke(base, func);
            } catch (NoSuchMethodException ns) {
                // fall through
            }
        } catch (Throwable ignored) {}

        // fallback: Formatting.GREEN
        try {
            Text t = Text.literal(s);
            return (Text) Text.class.getMethod("formatted", Enum.class).invoke(t, Enum.valueOf((Class<Enum>) Class.forName("net.minecraft.util.Formatting"), "GREEN"));
        } catch (Throwable ignored) {}

        return Text.literal(s);
    }

    // ---------------- helpers ----------------
    private static boolean isTextType(Class<?> cls) {
        if (cls == null) return false;
        String name = cls.getName();
        return name.equals("net.minecraft.text.Text") || name.endsWith(".Text") || name.toLowerCase(Locale.ROOT).contains("text");
    }

    private static Object tryAsOrderedText(Text label) {
        try {
            Method m = Text.class.getMethod("asOrderedText");
            return m.invoke(label);
        } catch (Throwable ignored) {}
        return null;
    }

    private static int tryComputeTextWidth(MinecraftClient client, Text label) {
        try {
            Method getWidthStr = client.textRenderer.getClass().getMethod("getWidth", String.class);
            return (Integer) getWidthStr.invoke(client.textRenderer, label.getString());
        } catch (Throwable ignored) {}
        try {
            Method getWidthText = client.textRenderer.getClass().getMethod("getWidth", Text.class);
            return (Integer) getWidthText.invoke(client.textRenderer, label);
        } catch (Throwable ignored) {}
        try {
            return label.getString().length() * 6;
        } catch (Throwable ignored) {
            return 6;
        }
    }
}
