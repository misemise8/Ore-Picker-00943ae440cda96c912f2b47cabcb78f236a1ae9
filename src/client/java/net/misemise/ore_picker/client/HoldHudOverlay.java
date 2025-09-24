package net.misemise.ore_picker.client;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * HoldHudOverlay - reflection-first implementation (no compile-time dependency on DrawContext/TextRenderer)
 *
 * - HudRenderCallback.EVENT をリフレクションで取得して登録する（DrawContext / MatrixStack の差を吸収）
 * - TextRenderer の複数の draw/drawWithShadow シグネチャに対応して描画する（すべて reflection）
 * - Ore_pickerClient.localHold をリフレクション/直接参照して判定（client パッケージ / top-level の両方対応）
 *
 * これにより、ラムダの型不一致や TextRenderer のシグネチャ差でのコンパイルエラーを防ぎます。
 */
public final class HoldHudOverlay {
    private HoldHudOverlay() {}

    public static void register() {
        try {
            // HudRenderCallback を反射で取得
            Class<?> hudCallbackCls = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");
            Field eventField = hudCallbackCls.getField("EVENT");
            Object eventObj = eventField.get(null);

            // EVENT.register(listener) を探す
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

            Class<?> listenerType = registerMethod.getParameterTypes()[0];

            // 動的プロキシでリスナーを作る（ラムダの型不一致を避ける）
            Object listenerProxy = Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[]{listenerType},
                    new InvocationHandler() {
                        // キャッシュ用
                        volatile Field clientTextField = null;
                        volatile Method clientGetTextMethod = null;
                        volatile Method textRendererGetWidthText = null;
                        volatile Method textRendererGetWidthString = null;
                        volatile Method preferredDrawMethod = null;
                        volatile Method drawContextDrawMethod = null;

                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                if (args == null || args.length == 0) return null;

                                // Config を確認（存在しなければロード）
                                if (ConfigManager.INSTANCE == null) {
                                    try { ConfigManager.load(); } catch (Throwable ignored) {}
                                }
                                if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) return null;

                                // localHold を確認（client パッケージ / top-level の両方を試す）
                                boolean localHold = false;
                                try {
                                    // client package
                                    try {
                                        Class<?> cls = Class.forName("net.misemise.ore_picker.client.Ore_pickerClient");
                                        try {
                                            java.lang.reflect.Field f = cls.getField("localHold");
                                            Object v = f.get(null);
                                            localHold = Boolean.TRUE.equals(v);
                                        } catch (Throwable ignored) {}
                                    } catch (Throwable ignored) {}

                                    // fallback top-level
                                    if (!localHold) {
                                        try {
                                            Class<?> cls2 = Class.forName("net.misemise.ore_picker.Ore_pickerClient");
                                            try {
                                                java.lang.reflect.Field f2 = cls2.getField("localHold");
                                                Object v2 = f2.get(null);
                                                localHold = Boolean.TRUE.equals(v2);
                                            } catch (Throwable ignored) {}
                                        } catch (Throwable ignored) {}
                                    }
                                } catch (Throwable t) { localHold = false; }

                                if (!localHold) return null;

                                MinecraftClient client = MinecraftClient.getInstance();
                                if (client == null) return null;

                                // ローカライズされた文字列を取得（Text.translatable -> getString）
                                String msgStr = "OrePicker: Active";
                                try {
                                    Text t = Text.translatable("hud.ore_picker.active");
                                    msgStr = t.getString();
                                } catch (Throwable ignored) {}

                                Text msgText = Text.literal(msgStr);

                                // textRenderer オブジェクトを反射で取得する
                                Object tr = resolveTextRenderer(client);
                                if (tr == null) {
                                    // なんらかの理由で取得できなければ描画せずに戻る
                                    return null;
                                }

                                // ウィンドウサイズ
                                int sw = client.getWindow().getScaledWidth();
                                int sh = client.getWindow().getScaledHeight();

                                int textWidth = getTextWidth(tr, msgText, msgStr);

                                int x = sw / 2 - textWidth / 2;
                                int y = sh - 48; // ホットバー上あたり

                                // Try DrawContext path if provided in args[0]
                                Object firstArg = args[0];
                                if (firstArg != null) {
                                    Method dcMethod = findDrawContextDrawText(firstArg.getClass());
                                    if (dcMethod != null) {
                                        try {
                                            // 期待されるのは (TextRenderer, Text, int, int, int, boolean) 等
                                            // できるだけ柔軟に呼び出す
                                            try {
                                                dcMethod.invoke(firstArg, tr, msgText, x, y, 0xFFFFFF, true);
                                                return null;
                                            } catch (IllegalArgumentException iae) {
                                                try { dcMethod.invoke(firstArg, tr, msgText, (float)x, (float)y, 0xFFFFFF, true); return null; } catch (Throwable ignored) {}
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }

                                // 直接 textRenderer 上の draw/drawWithShadow 系を探して呼ぶ
                                if (preferredDrawMethod != null) {
                                    try {
                                        // cached method may still be valid
                                        callPreferredDraw(preferredDrawMethod, tr, msgText, msgStr, x, y);
                                        return null;
                                    } catch (Throwable e) {
                                        preferredDrawMethod = null; // invalidate cache
                                    }
                                }

                                // 探索
                                for (Method m : tr.getClass().getMethods()) {
                                    String nm = m.getName();
                                    Class<?>[] pts = m.getParameterTypes();
                                    try {
                                        // drawWithShadow(Text, float, float, int)
                                        if ("drawWithShadow".equals(nm) && pts.length == 4 && Text.class.isAssignableFrom(pts[0])
                                                && (pts[1] == float.class || pts[1] == Float.class)) {
                                            m.invoke(tr, msgText, (float)x, (float)y, 0xFFFFFF);
                                            preferredDrawMethod = m;
                                            return null;
                                        }
                                        // drawWithShadow(String, float, float, int)
                                        if ("drawWithShadow".equals(nm) && pts.length == 4 && pts[0] == String.class) {
                                            m.invoke(tr, msgStr, (float)x, (float)y, 0xFFFFFF);
                                            preferredDrawMethod = m;
                                            return null;
                                        }
                                        // draw(Text, float, float, int) or draw(Text, int, int, int)
                                        if ("draw".equals(nm) && pts.length == 4 && Text.class.isAssignableFrom(pts[0])) {
                                            if (pts[1] == float.class || pts[1] == Float.class) {
                                                m.invoke(tr, msgText, (float)x, (float)y, 0xFFFFFF);
                                            } else {
                                                m.invoke(tr, msgText, x, y, 0xFFFFFF);
                                            }
                                            preferredDrawMethod = m;
                                            return null;
                                        }
                                        // draw(String, int, int, int)
                                        if ("draw".equals(nm) && pts.length == 4 && pts[0] == String.class && (pts[1] == int.class || pts[1] == Integer.class)) {
                                            m.invoke(tr, msgStr, x, y, 0xFFFFFF);
                                            preferredDrawMethod = m;
                                            return null;
                                        }
                                    } catch (Throwable inner) {
                                        // try next
                                    }
                                }

                                // 最後の手段: try a common method signature
                                try {
                                    Method fallback = tr.getClass().getMethod("drawWithShadow", String.class, float.class, float.class, int.class);
                                    fallback.invoke(tr, msgStr, (float)x, (float)y, 0xFFFFFF);
                                } catch (Throwable ignored) {}

                            } catch (Throwable t) {
                                // swallow exceptions to avoid crashing render loop
                                t.printStackTrace();
                            }
                            return null;
                        }

                        private Object resolveTextRenderer(MinecraftClient client) {
                            try {
                                // try field "textRenderer"
                                if (clientTextField == null) {
                                    try {
                                        Field f = client.getClass().getField("textRenderer");
                                        f.setAccessible(true);
                                        clientTextField = f;
                                    } catch (NoSuchFieldException nsf) {
                                        // try declared
                                        try {
                                            Field f2 = client.getClass().getDeclaredField("textRenderer");
                                            f2.setAccessible(true);
                                            clientTextField = f2;
                                        } catch (NoSuchFieldException ignored) {
                                            clientTextField = null;
                                        }
                                    }
                                }
                                if (clientTextField != null) {
                                    try {
                                        Object tr = clientTextField.get(client);
                                        if (tr != null) return tr;
                                    } catch (Throwable ignored) {}
                                }

                                // try method getTextRenderer()
                                if (clientGetTextMethod == null) {
                                    try {
                                        Method gm = client.getClass().getMethod("getTextRenderer");
                                        gm.setAccessible(true);
                                        clientGetTextMethod = gm;
                                    } catch (NoSuchMethodException ignored) { clientGetTextMethod = null; }
                                }
                                if (clientGetTextMethod != null) {
                                    try {
                                        Object tr = clientGetTextMethod.invoke(client);
                                        if (tr != null) return tr;
                                    } catch (Throwable ignored) {}
                                }

                                // search declared fields in class hierarchy
                                for (Class<?> c = client.getClass(); c != null; c = c.getSuperclass()) {
                                    try {
                                        Field f = c.getDeclaredField("textRenderer");
                                        f.setAccessible(true);
                                        Object tr = f.get(client);
                                        if (tr != null) return tr;
                                    } catch (Throwable ignored) {}
                                }
                            } catch (Throwable t) {}
                            return null;
                        }

                        private int getTextWidth(Object tr, Text textObj, String textStr) {
                            try {
                                if (textRendererGetWidthText == null) {
                                    try { textRendererGetWidthText = tr.getClass().getMethod("getWidth", Text.class); } catch (NoSuchMethodException e) { textRendererGetWidthText = null; }
                                }
                                if (textRendererGetWidthText != null) {
                                    try {
                                        Object o = textRendererGetWidthText.invoke(tr, textObj);
                                        if (o instanceof Number) return ((Number)o).intValue();
                                    } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}

                            try {
                                if (textRendererGetWidthString == null) {
                                    try { textRendererGetWidthString = tr.getClass().getMethod("getWidth", String.class); } catch (NoSuchMethodException e) { textRendererGetWidthString = null; }
                                }
                                if (textRendererGetWidthString != null) {
                                    try {
                                        Object o2 = textRendererGetWidthString.invoke(tr, textStr);
                                        if (o2 instanceof Number) return ((Number)o2).intValue();
                                    } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}

                            return textStr.length() * 6;
                        }

                        private Method findDrawContextDrawText(Class<?> drawContextCls) {
                            try {
                                for (Method m : drawContextCls.getMethods()) {
                                    String name = m.getName();
                                    if (!"drawText".equals(name) && !"draw".equals(name) && !"drawTextWithShadow".equals(name)) continue;
                                    Class<?>[] pts = m.getParameterTypes();
                                    if (pts.length >= 5) {
                                        boolean hasTR = false;
                                        boolean hasText = false;
                                        for (Class<?> p : pts) {
                                            String pn = p.getSimpleName();
                                            if (pn.contains("TextRenderer") || pn.contains("Font") || pn.contains("FontRenderer")) hasTR = true;
                                            if (Text.class.isAssignableFrom(p)) hasText = true;
                                        }
                                        if (hasTR && hasText) return m;
                                    }
                                }
                            } catch (Throwable t) {}
                            return null;
                        }

                        private void callPreferredDraw(Method m, Object tr, Text t, String s, int x, int y) throws Exception {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length == 4) {
                                if (pts[0] == String.class) {
                                    m.invoke(tr, s, (float)x, (float)y, 0xFFFFFF);
                                } else if (Text.class.isAssignableFrom(pts[0])) {
                                    if (pts[1] == float.class) m.invoke(tr, t, (float)x, (float)y, 0xFFFFFF);
                                    else m.invoke(tr, t, x, y, 0xFFFFFF);
                                } else {
                                    // fallback attempt
                                    m.invoke(tr, s, (float)x, (float)y, 0xFFFFFF);
                                }
                            } else {
                                // generic invoke try
                                m.invoke(tr, s, (float)x, (float)y, 0xFFFFFF);
                            }
                        }
                    }
            );

            // register listener proxy
            registerMethod.invoke(eventObj, listenerProxy);
            System.out.println("[OrePicker] HUD overlay registered (reflection-first).");
        } catch (ClassNotFoundException cnf) {
            System.err.println("[OrePicker] HudRenderCallback class not found; HUD overlay disabled.");
        } catch (Throwable t) {
            System.err.println("[OrePicker] Failed to register HUD overlay (unexpected).");
            t.printStackTrace();
        }
    }
}
