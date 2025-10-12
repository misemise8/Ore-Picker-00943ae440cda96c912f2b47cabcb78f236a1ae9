package net.misemise.ore_picker.client.rendering;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.misemise.ore_picker.client.Ore_pickerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.VertexConsumerProvider.Immediate;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;

/**
 * OutlineRenderer - 互換性重視版（チェイン呼び出しを避け、すべて反射で試行）
 */
public final class OutlineRenderer {
    private OutlineRenderer() {}

    public static void register() {
        try {
            WorldRenderEvents.AFTER_ENTITIES.register((context) -> {
                try {
                    render(context);
                } catch (Throwable t) {
                    try {
                        if (net.misemise.ore_picker.config.ConfigManager.INSTANCE != null &&
                                net.misemise.ore_picker.config.ConfigManager.INSTANCE.debug) t.printStackTrace();
                    } catch (Throwable ignored) {}
                }
            });
            System.out.println("[OrePicker] OutlineRenderer registered (AFTER_ENTITIES).");
        } catch (Throwable t) {
            System.err.println("[OrePicker] OutlineRenderer registration failed:");
            t.printStackTrace();
        }
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        MatrixStack matrices = context.matrixStack();
        if (matrices == null) return;

        VertexConsumerProvider provider = context.consumers();
        if (provider == null) return;

        Camera camera = context.camera();
        Vec3d camPos = (camera == null) ? new Vec3d(0,0,0) : camera.getPos();

        Set<BlockPos> set = Ore_pickerClient.selectedBlocks;
        if (set == null || set.isEmpty()) return;

        float r = 0.6f, g = 1.0f, b = 0.4f, a = 1.0f;
        RenderLayer linesLayer = RenderLayer.getLines();

        VertexConsumer vc;
        try {
            vc = provider.getBuffer(linesLayer);
        } catch (Throwable t) {
            try { if (net.misemise.ore_picker.config.ConfigManager.INSTANCE != null && net.misemise.ore_picker.config.ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
            return;
        }

        // まず vanilla の helper を反射で探す
        Method reflectiveDrawBox = findWorldRendererDrawBox();

        // MatrixStack から得られる行列オブジェクトを可能なら取得しておく（org.joml.Matrix4f / com.mojang.math.Matrix4f 等）
        Object matrixObj = obtainMatrixFromMatrixStack(matrices);

        synchronized (set) {
            for (BlockPos bp : set) {
                if (bp == null) continue;

                double x = bp.getX(), y = bp.getY(), z = bp.getZ();
                Box box = new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                Box rel = box;

                boolean drawn = false;
                if (reflectiveDrawBox != null) {
                    try {
                        reflectiveDrawBox.invoke(null, matrices, vc, rel, r, g, b, a);
                        drawn = true;
                    } catch (IllegalAccessException | InvocationTargetException ignore) {
                        // fallthrough: 手動描画にフォールバック
                        drawn = false;
                    } catch (Throwable t) {
                        drawn = false;
                        try { if (net.misemise.ore_picker.config.ConfigManager.INSTANCE != null && net.misemise.ore_picker.config.ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
                    }
                }

                if (!drawn) {
                    float minX = (float) rel.minX, minY = (float) rel.minY, minZ = (float) rel.minZ;
                    float maxX = (float) rel.maxX, maxY = (float) rel.maxY, maxZ = (float) rel.maxZ;

                    // 12 辺をそれぞれ描画（反射で頂点APIを試行）
                    emitLineRobust(vc, matrixObj, minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
                    emitLineRobust(vc, matrixObj, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
                    emitLineRobust(vc, matrixObj, minX, minY, maxZ, maxX, minY, maxZ, r, g, b, a);
                    emitLineRobust(vc, matrixObj, minX, maxY, maxZ, maxX, maxY, maxZ, r, g, b, a);

                    emitLineRobust(vc, matrixObj, minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
                    emitLineRobust(vc, matrixObj, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
                    emitLineRobust(vc, matrixObj, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);
                    emitLineRobust(vc, matrixObj, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);

                    emitLineRobust(vc, matrixObj, minX, minY, minZ, minX, minY, maxZ, r, g, b, a);
                    emitLineRobust(vc, matrixObj, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
                    emitLineRobust(vc, matrixObj, minX, maxY, minZ, minX, maxY, maxZ, r, g, b, a);
                    emitLineRobust(vc, matrixObj, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
                }
            }
        }

        // Immediate なら明示的フラッシュ
        try {
            if (provider instanceof Immediate) ((Immediate) provider).draw();
        } catch (Throwable ignored) {}
    }

    // WorldRenderer.drawBox の候補を探す（MatrixStack, VertexConsumer, Box, float,float,float,float を期待）
    private static Method findWorldRendererDrawBox() {
        try {
            Class<?> wr = Class.forName("net.minecraft.client.render.WorldRenderer");
            for (Method m : wr.getMethods()) {
                if (!m.getName().equals("drawBox")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 7) return m;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // MatrixStack.peek() から行列オブジェクトを取り出す。見つかれば返す（型はランタイムに任せる）
    private static Object obtainMatrixFromMatrixStack(MatrixStack matrices) {
        try {
            Object entry = matrices.peek();
            if (entry == null) return null;
            String[] candidates = new String[] { "getModel", "getPositionMatrix", "getMatrix", "get", "peek" };
            for (String name : candidates) {
                try {
                    Method gm = entry.getClass().getMethod(name);
                    Object mat = gm.invoke(entry);
                    if (mat != null) return mat;
                } catch (NoSuchMethodException nsme) {
                    // 次へ
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 頂点発行を反射で頑張る関数。
     * - まず vertex(matrixClass, float,float,float) を試し、返り値に color(...) と終端メソッド(next/endVertex/emit)を呼ぶ。
     * - 次に vertex(float,float,float) を試して同様に color/終端。
     * - 次に vertex に大量引数 (x,y,z,r,g,b,a) のようなバリアントを探して直接呼ぶ。
     * - 何も見つからなければ安全に握りつぶす（描画不可）。
     */
    private static void emitLineRobust(VertexConsumer vc, Object matrixObj,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float r, float g, float b, float a) {
        // Helper: try chain-style: invoke vertex(...) -> returnedObj -> color(...) -> finalize()
        try {
            // 1) vertex(matrix, x,y,z)
            if (matrixObj != null) {
                try {
                    Method v = findMethod(vc.getClass(), "vertex", matrixObj.getClass(), float.class, float.class, float.class);
                    if (v != null) {
                        Object ret1 = v.invoke(vc, matrixObj, x1, y1, z1);
                        if (tryColorAndFinalize(ret1, r, g, b, a)) {
                            Object ret2 = v.invoke(vc, matrixObj, x2, y2, z2);
                            tryColorAndFinalize(ret2, r, g, b, a);
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 2) vertex(x,y,z)
            try {
                Method v2 = findMethod(vc.getClass(), "vertex", float.class, float.class, float.class);
                if (v2 != null) {
                    Object ret1 = v2.invoke(vc, x1, y1, z1);
                    if (tryColorAndFinalize(ret1, r, g, b, a)) {
                        Object ret2 = v2.invoke(vc, x2, y2, z2);
                        tryColorAndFinalize(ret2, r, g, b, a);
                        return;
                    }
                }
            } catch (Throwable ignored) {}

            // 3) direct multi-arg variant: look for vertex(double/float x3.. and color args)
            try {
                for (Method m : vc.getClass().getMethods()) {
                    if (!m.getName().equals("vertex")) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    // candidate: vertex(float x, float y, float z, float r, float g, float b, float a)
                    if (pts.length >= 7) {
                        boolean ok1 = isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2]) &&
                                (isNumeric(pts[3]) || pts[3] == int.class || pts[3] == Integer.class);
                        if (ok1) {
                            // try to invoke with float/double appropriately
                            Object[] args1 = buildNumericArgs(pts, x1, y1, z1, r, g, b, a);
                            Object[] args2 = buildNumericArgs(pts, x2, y2, z2, r, g, b, a);
                            try {
                                m.invoke(vc, args1);
                                m.invoke(vc, args2);
                                return;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}

        // 最終フォールバック: 何もしない（描画失敗）。デバッグ出力は別の箇所で行ってください。
    }

    // try to call color(...) on returned object and then call finalize method like next()/endVertex()/emit()
    private static boolean tryColorAndFinalize(Object returnedObj, float r, float g, float b, float a) {
        if (returnedObj == null) return false;
        try {
            // try color(float,float,float,float)
            Method color = findMethod(returnedObj.getClass(), "color", float.class, float.class, float.class, float.class);
            if (color != null) {
                Object afterColor = color.invoke(returnedObj, r, g, b, a);
                // try finalizer names
                String[] finalNames = new String[] {"next", "endVertex", "emit", "nextVertex"};
                for (String fname : finalNames) {
                    try {
                        Method finalM = findMethod(afterColor.getClass(), fname);
                        if (finalM != null) {
                            finalM.invoke(afterColor);
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
                // if no finalizer found, maybe chaining not required; assume success
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    // find method by name and parameter types, tolerant to subclasses (returns first that matches assignability)
    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != params.length) continue;
            boolean ok = true;
            for (int i = 0; i < pts.length; i++) {
                if (params[i] == null) continue;
                if (!primitiveOrAssignable(pts[i], params[i])) { ok = false; break; }
            }
            if (ok) return m;
        }
        return null;
    }

    private static boolean primitiveOrAssignable(Class<?> target, Class<?> src) {
        if (target.isAssignableFrom(src)) return true;
        if (target.isPrimitive()) {
            // common primitive wrappers
            if (target == float.class && (src == Float.class)) return true;
            if (target == double.class && (src == Double.class)) return true;
            if (target == int.class && (src == Integer.class)) return true;
            if (target == long.class && (src == Long.class)) return true;
        }
        return false;
    }

    private static boolean isNumeric(Class<?> c) {
        return c == float.class || c == double.class || c == Float.class || c == Double.class ||
                c == int.class || c == Integer.class || c == long.class || c == Long.class;
    }

    // build argument array for method with parameter types pts, mapping floats to required numeric types
    private static Object[] buildNumericArgs(Class<?>[] pts, float x, float y, float z, float r, float g, float b, float a) {
        Object[] args = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            Class<?> t = pts[i];
            float val = 0f;
            if (i == 0) val = x;
            else if (i == 1) val = y;
            else if (i == 2) val = z;
            else if (i == 3) val = r;
            else if (i == 4) val = g;
            else if (i == 5) val = b;
            else if (i == 6) val = a;
            else val = 0f;

            if (t == float.class || t == Float.class) args[i] = val;
            else if (t == double.class || t == Double.class) args[i] = (double) val;
            else if (t == int.class || t == Integer.class) args[i] = (int) val;
            else if (t == long.class || t == Long.class) args[i] = (long) val;
            else args[i] = val;
        }
        return args;
    }
}
