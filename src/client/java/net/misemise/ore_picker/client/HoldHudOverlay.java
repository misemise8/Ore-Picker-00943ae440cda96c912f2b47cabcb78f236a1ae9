package net.misemise.ore_picker.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * HoldHudOverlay (reflection-resilient)
 *
 * - HudRenderCallback.EVENT をリフレクションで見つけて登録します（DrawContext / MatrixStack の違いを吸収）。
 * - 描画は TextRenderer の drawWithShadow 系メソッドを反射で複数パターン試します:
 *     - (DrawContext, Text, int, int, int)
 *     - (MatrixStack, Text, int, int, int)
 *     - (String, int, int, int)
 *     - (Text, float, float, int)
 *     - (String, float, float, int)
 *   等に対応します。失敗してもクラッシュしないように保護しています。
 */
public final class HoldHudOverlay {
    private HoldHudOverlay() {}

    public static void register() {
        try {
            // Find the HudRenderCallback class reflectively so we don't need to import DrawContext/MatrixStack.
            Class<?> hudCallbackCls = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");
            // Get public static EVENT field
            var eventField = hudCallbackCls.getField("EVENT");
            Object eventObj = eventField.get(null);

            // Find register method on the EVENT object
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

            // Create dynamic proxy implementing the listener interface
            Object listenerProxy = Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[]{listenerType},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                // args can be: (DrawContext, float) or (MatrixStack, float) or (MatrixStack) etc.
                                if (args == null || args.length == 0) return null;
                                final Object firstArg = args[0];
                                // tickDelta might be args[1] (float/double) or absent
                                float tickDelta = 0f;
                                if (args.length >= 2) {
                                    if (args[1] instanceof Float) tickDelta = (Float) args[1];
                                    else if (args[1] instanceof Double) tickDelta = ((Double) args[1]).floatValue();
                                }

                                // Only draw when holding the key
                                if (!Ore_pickerClient.localHold) return null;

                                MinecraftClient client = MinecraftClient.getInstance();
                                if (client == null) return null;

                                String text = "Holding";
                                int color = 0xFFFFFF;

                                // compute position: center above hotbar
                                int sw = client.getWindow().getScaledWidth();
                                int sh = client.getWindow().getScaledHeight();

                                int textWidth = 0;
                                try {
                                    // prefer getWidth(String)
                                    Method getWidthStr = client.textRenderer.getClass().getMethod("getWidth", String.class);
                                    textWidth = (Integer) getWidthStr.invoke(client.textRenderer, text);
                                } catch (Throwable ex) {
                                    try {
                                        // try getWidth(Text)
                                        Method getWidthText = client.textRenderer.getClass().getMethod("getWidth", Text.class);
                                        textWidth = (Integer) getWidthText.invoke(client.textRenderer, Text.of(text));
                                    } catch (Throwable ignored) {
                                        // fallback width
                                        textWidth = text.length() * 6;
                                    }
                                }

                                int x = sw / 2 - textWidth / 2;
                                int y = sh - 50;

                                // Try to find and invoke an appropriate drawWithShadow method on TextRenderer
                                Method[] methods = client.textRenderer.getClass().getMethods();
                                boolean drawn = false;

                                for (Method m : methods) {
                                    if (!"drawWithShadow".equals(m.getName())) continue;
                                    Class<?>[] pts = m.getParameterTypes();

                                    try {
                                        // case: drawWithShadow(DrawContext, Text, int, int, int)
                                        if (pts.length == 5) {
                                            if (firstArg != null && pts[0].isInstance(firstArg)
                                                    && Text.class.isAssignableFrom(pts[1])
                                                    && (pts[2] == int.class || pts[2] == Integer.class)) {
                                                // invoke with (firstArg, Text, x, y, color)
                                                try {
                                                    m.invoke(client.textRenderer, firstArg, Text.of(text), x, y, color);
                                                    drawn = true;
                                                    break;
                                                } catch (IllegalArgumentException iae) {
                                                    // maybe expects (MatrixStack, String, int,int,int) - try fallback further down
                                                }
                                            }
                                        }

                                        // case: drawWithShadow(Text, float, float, int)
                                        if (pts.length == 4 && Text.class.isAssignableFrom(pts[0])
                                                && (pts[1] == float.class || pts[1] == Float.class)) {
                                            m.invoke(client.textRenderer, Text.of(text), (float) x, (float) y, color);
                                            drawn = true;
                                            break;
                                        }

                                        // case: drawWithShadow(String, float, float, int)
                                        if (pts.length == 4 && pts[0] == String.class && (pts[1] == float.class || pts[1] == Float.class)) {
                                            m.invoke(client.textRenderer, text, (float) x, (float) y, color);
                                            drawn = true;
                                            break;
                                        }

                                        // case: drawWithShadow(String, int, int, int)
                                        if (pts.length == 4 && pts[0] == String.class && pts[1] == int.class) {
                                            m.invoke(client.textRenderer, text, x, y, color);
                                            drawn = true;
                                            break;
                                        }

                                        // case: drawWithShadow(MatrixStack, String, int, int, int)
                                        if (pts.length == 5 && firstArg != null && pts[0].isInstance(firstArg) && pts[1] == String.class) {
                                            m.invoke(client.textRenderer, firstArg, text, x, y, color);
                                            drawn = true;
                                            break;
                                        }

                                    } catch (Throwable inner) {
                                        // ignore and try next method
                                    }
                                }

                                // If none drawn yet, try a simple fallback draw (draw(String,int,int,int) or draw(Text,...))
                                if (!drawn) {
                                    try {
                                        Method drawStr = client.textRenderer.getClass().getMethod("draw", String.class, int.class, int.class, int.class);
                                        drawStr.invoke(client.textRenderer, text, x, y, color);
                                    } catch (Throwable ignored) {
                                        try {
                                            Method drawText = client.textRenderer.getClass().getMethod("draw", Text.class, float.class, float.class, int.class);
                                            drawText.invoke(client.textRenderer, Text.of(text), (float) x, (float) y, color);
                                        } catch (Throwable ignored2) {
                                            // give up silently
                                        }
                                    }
                                }

                            } catch (Throwable t) {
                                // swallow any exceptions to avoid crashing the client render loop
                                t.printStackTrace();
                            }
                            return null;
                        }
                    }
            );

            // register
            registerMethod.invoke(eventObj, listenerProxy);
            System.out.println("[OrePicker] HUD overlay registered (reflection-resilient)");
        } catch (ClassNotFoundException cnf) {
            // HudRenderCallback class not present
            System.err.println("[OrePicker] HudRenderCallback class not found; HUD overlay disabled.");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
