package net.misemise.ore_picker.client.rendering;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.misemise.ore_picker.client.Ore_pickerClient;
import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.VertexConsumerProvider.Immediate;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * OutlineRenderer - カメラ座標系補正 + 終端呼び出し強化版
 */
public final class OutlineRenderer {
    private OutlineRenderer() {}

    // 太さ制御（増やすと太くなる）
    private static final int THICKNESS_STEPS = 5;
    private static final float THICKNESS_OFFSET = 0.0045f;

    public static void register() {
        try {
            WorldRenderEvents.AFTER_ENTITIES.register((context) -> {
                try {
                    render(context);
                } catch (Throwable t) {
                    try {
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
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

        float r = 1.0f, g = 1.0f, b = 1.0f, a = 1.0f;
        RenderLayer linesLayer = RenderLayer.getLines();

        VertexConsumer vc;
        try {
            vc = provider.getBuffer(linesLayer);
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
            return;
        }

        Method reflectiveDrawBox = findWorldRendererDrawBox();
        Object matrixObj = obtainMatrixFromMatrixStack(matrices);

        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
            System.out.println("[OrePicker][OutlineRenderer DEBUG] matrixObj=" + (matrixObj == null ? "null" : matrixObj.getClass().getName())
                    + " reflectiveDrawBox=" + (reflectiveDrawBox == null ? "null" : reflectiveDrawBox.getName())
                    + " vc=" + (vc == null ? "null" : vc.getClass().getName())
                    + " selected=" + set.size()
                    + " camPos=" + camPos);
        }

        synchronized (set) {
            float camX = (float) camPos.x;
            float camY = (float) camPos.y;
            float camZ = (float) camPos.z;

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
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                            System.out.println("[OrePicker][OutlineRenderer DEBUG] drawBox invoked reflectively for " + bp);
                    } catch (Throwable t) {
                        drawn = false;
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                            System.err.println("[OrePicker][OutlineRenderer DEBUG] reflective drawBox failed:");
                            t.printStackTrace();
                        }
                    }
                }

                if (!drawn) {
                    float minX = (float) rel.minX, minY = (float) rel.minY, minZ = (float) rel.minZ;
                    float maxX = (float) rel.maxX, maxY = (float) rel.maxY, maxZ = (float) rel.maxZ;

                    float[][] edges = new float[][] {
                            { minX, minY, minZ, maxX, minY, minZ },
                            { minX, maxY, minZ, maxX, maxY, minZ },
                            { minX, minY, maxZ, maxX, minY, maxZ },
                            { minX, maxY, maxZ, maxX, maxY, maxZ },

                            { minX, minY, minZ, minX, maxY, minZ },
                            { maxX, minY, minZ, maxX, maxY, minZ },
                            { minX, minY, maxZ, minX, maxY, maxZ },
                            { maxX, minY, maxZ, maxX, maxY, maxZ },

                            { minX, minY, minZ, minX, minY, maxZ },
                            { maxX, minY, minZ, maxX, minY, maxZ },
                            { minX, maxY, minZ, minX, maxY, maxZ },
                            { maxX, maxY, minZ, maxX, maxY, maxZ }
                    };

                    for (float[] e : edges) {
                        float x1 = e[0], y1 = e[1], z1 = e[2];
                        float x2 = e[3], y2 = e[4], z2 = e[5];

                        // カメラ相対化（重要）：world -> view-relative coords
                        float rx1 = x1 - camX, ry1 = y1 - camY, rz1 = z1 - camZ;
                        float rx2 = x2 - camX, ry2 = y2 - camY, rz2 = z2 - camZ;

                        // 太くするために複数オフセットで重ねる
                        for (int step = 0; step < THICKNESS_STEPS; step++) {
                            float ox = 0f, oy = 0f, oz = 0f;
                            if (step > 0) {
                                float s = (step % 2 == 0) ? 1f : -1f;
                                ox = THICKNESS_OFFSET * s * step;
                                oy = THICKNESS_OFFSET * s * ((step+1)%2);
                                oz = THICKNESS_OFFSET * s * ((step+2)%2);
                            }
                            emitLineRobust(vc, matrixObj,
                                    rx1 + ox, ry1 + oy, rz1 + oz,
                                    rx2 + ox, ry2 + oy, rz2 + oz,
                                    r, g, b, a);
                        }
                    }
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

    private static Object obtainMatrixFromMatrixStack(MatrixStack matrices) {
        try {
            Object entry = matrices.peek();
            if (entry == null) return null;
            String[] candidates = new String[] { "getModel", "getPositionMatrix", "getMatrix", "get", "peek", "method_23761", "method_23760" };
            for (String name : candidates) {
                try {
                    Method gm = entry.getClass().getMethod(name);
                    Object mat = gm.invoke(entry);
                    if (mat != null) return mat;
                } catch (NoSuchMethodException nsme) { }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 頂点発行の互換的実装。
     * - BufferBuilder 実装を特別扱いして、vertex(matrix,..)->color(int,..)->normal(...) -> finalize を確実に行う。
     * - それ以外は従来の反射フォールバックを試行。
     */
    private static void emitLineRobust(VertexConsumer vc, Object matrixObj,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float r, float g, float b, float a) {
        if (vc == null) return;
        Class<?> cls = vc.getClass();

        // まず BufferBuilder 系の具体実装を特別扱い（ログでも BufferBuilder が来ているようなので）
        String clsName = cls.getName().toLowerCase();
        boolean isBufferBuilderLike = clsName.contains("bufferbuilder") || clsName.contains("bufferbuilder$") || clsName.contains("bufferbuilder");

        if (isBufferBuilderLike) {
            if (tryEmitOnBufferBuilder(vc, matrixObj, x1,y1,z1, x2,y2,z2, r,g,b,a)) return;
        }

        // 汎用反射ルート（matrix付き vertex を優先）
        boolean ok = false;
        try {
            if (matrixObj != null) {
                for (Method m : cls.getMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 4 && pts[0].isAssignableFrom(matrixObj.getClass()) && isNumeric(pts[1]) && isNumeric(pts[2]) && isNumeric(pts[3])) {
                        String mn = m.getName().toLowerCase();
                        if (!(mn.contains("vertex") || mn.contains("pos") || mn.contains("method_22918"))) continue;
                        try {
                            Object ret1 = m.invoke(vc, matrixObj, x1, y1, z1);
                            tryInvokeColorOn(ret1 != null ? ret1 : vc, r, g, b, a);
                            tryFinalizeOn(ret1 != null ? ret1 : vc);

                            Object ret2 = m.invoke(vc, matrixObj, x2, y2, z2);
                            tryInvokeColorOn(ret2 != null ? ret2 : vc, r, g, b, a);
                            tryFinalizeOn(ret2 != null ? ret2 : vc);

                            ok = true;
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                                System.out.println("[OrePicker][OutlineRenderer DEBUG] emitted via matrix-sig method " + m + " on " + cls.getName());
                            break;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (ok) return;

        // 次に 3-arg numeric の vertex-like を探す（名前フィルタ付き）
        try {
            for (Method m : cls.getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 3 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2])) {
                    String mn = m.getName().toLowerCase();
                    if (!(mn.contains("vertex") || mn.contains("pos") || mn.contains("put") || mn.contains("method_22918"))) continue;
                    try {
                        Object ret1 = m.invoke(vc, x1, y1, z1);
                        tryInvokeColorOn(ret1 != null ? ret1 : vc, r, g, b, a);
                        tryFinalizeOn(ret1 != null ? ret1 : vc);

                        Object ret2 = m.invoke(vc, x2, y2, z2);
                        tryInvokeColorOn(ret2 != null ? ret2 : vc, r, g, b, a);
                        tryFinalizeOn(ret2 != null ? ret2 : vc);

                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                            System.out.println("[OrePicker][OutlineRenderer DEBUG] emitted via 3-arg vertex-like method " + m + " on " + cls.getName());
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // multi-arg direct
        try {
            for (Method m : cls.getMethods()) {
                String mn = m.getName().toLowerCase();
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 3 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2])) {
                    if (!mn.contains("vertex") && !mn.contains("pos") && !mn.contains("put") && !mn.contains("set")) continue;
                    try {
                        Object[] args1 = buildNumericArgs(pts, x1,y1,z1, r,g,b,a);
                        Object[] args2 = buildNumericArgs(pts, x2,y2,z2, r,g,b,a);
                        m.invoke(vc, args1);
                        m.invoke(vc, args2);
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                            System.out.println("[OrePicker][OutlineRenderer DEBUG] emitted via multi-arg " + m + " on " + cls.getName());
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
            System.out.println("[OrePicker][OutlineRenderer DEBUG] emit failed for vc=" + cls.getName());
        }
    }

    // BufferBuilder系を見つけたらこちらで直接順序を守って呼ぶ（反射だが候補絞り込み強め）
    private static boolean tryEmitOnBufferBuilder(VertexConsumer vc, Object matrixObj,
                                                  float x1, float y1, float z1,
                                                  float x2, float y2, float z2,
                                                  float r, float g, float b, float a) {
        Class<?> cls = vc.getClass();
        try {
            Method vertexMatrix = null;
            Method vertex3 = null;
            Method colorInt = null;
            Method colorFloat = null;
            Method normalFloat = null;
            Method finalizeMethod = null;

            for (Method m : cls.getMethods()) {
                String mn = m.getName().toLowerCase();
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 4 && matrixObj != null && pts[0].isAssignableFrom(matrixObj.getClass()) && isNumeric(pts[1]) && isNumeric(pts[2]) && isNumeric(pts[3])) {
                    if (mn.contains("vertex") || mn.contains("method_22918") || mn.contains("method_22917")) vertexMatrix = m;
                }
                if (pts.length == 3 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2]) && (mn.contains("vertex") || mn.contains("method_22918"))) {
                    vertex3 = m;
                }
                if (pts.length == 4 && (pts[0] == int.class || pts[0] == Integer.class) && (mn.contains("color") || mn.contains("method_1336") || mn.contains("method_22915"))) {
                    colorInt = m;
                }
                if (pts.length == 4 && (isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2]) && isNumeric(pts[3])) && (mn.contains("color") || mn.contains("method_22915"))) {
                    colorFloat = m;
                }
                if (pts.length == 3 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2]) && (mn.contains("normal") || mn.contains("method_60831"))) {
                    normalFloat = m;
                }
                if ((mn.equals("next") || mn.equals("endvertex") || mn.equals("emit") || mn.equals("nextvertex") ||
                        mn.contains("method_22922") || mn.contains("method_22923"))) {
                    if (m.getParameterCount() == 0) finalizeMethod = m;
                }
            }

            // try matrix variant first
            if (vertexMatrix != null) {
                try {
                    Object ret1 = vertexMatrix.invoke(vc, matrixObj, x1, y1, z1);
                    if (colorInt != null) {
                        colorInt.invoke(vc, (int)(r*255f), (int)(g*255f), (int)(b*255f), (int)(a*255f));
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                            System.out.println("[OrePicker][OutlineRenderer DEBUG] color invoked via " + colorInt + " on " + cls.getName());
                    } else if (colorFloat != null) {
                        colorFloat.invoke(vc, r, g, b, a);
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                            System.out.println("[OrePicker][OutlineRenderer DEBUG] color(float) invoked via " + colorFloat + " on " + cls.getName());
                    }

                    if (normalFloat != null) {
                        // compute normal from edge direction
                        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
                        float mag = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                        if (mag != 0) { nx/=mag; ny/=mag; nz/=mag; }
                        try { normalFloat.invoke(vc, nx, ny, nz); } catch (Throwable ignored) {}
                    }

                    // finalize if we can
                    if (finalizeMethod != null) {
                        try { finalizeMethod.invoke(vc); } catch (Throwable ignored) {}
                    }

                    // second vertex
                    Object ret2 = vertexMatrix.invoke(vc, matrixObj, x2, y2, z2);
                    if (colorInt != null) {
                        colorInt.invoke(vc, (int)(r*255f), (int)(g*255f), (int)(b*255f), (int)(a*255f));
                    } else if (colorFloat != null) {
                        colorFloat.invoke(vc, r, g, b, a);
                    }
                    if (normalFloat != null) {
                        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
                        float mag = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                        if (mag != 0) { nx/=mag; ny/=mag; nz/=mag; }
                        try { normalFloat.invoke(vc, nx, ny, nz); } catch (Throwable ignored) {}
                    }
                    if (finalizeMethod != null) {
                        try { finalizeMethod.invoke(vc); } catch (Throwable ignored) {}
                    }

                    if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                        System.out.println("[OrePicker][OutlineRenderer DEBUG] emitted via matrix-sig method " + vertexMatrix + " on " + cls.getName());
                    return true;
                } catch (Throwable t) {
                    if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                        System.err.println("[OrePicker][OutlineRenderer DEBUG] tryEmitOnBufferBuilder(matrix) failed:");
                        t.printStackTrace();
                    }
                }
            }

            // fallback: 3-arg vertex
            if (vertex3 != null) {
                try {
                    vertex3.invoke(vc, x1, y1, z1);
                    if (colorInt != null) colorInt.invoke(vc, (int)(r*255f), (int)(g*255f), (int)(b*255f), (int)(a*255f));
                    else if (colorFloat != null) colorFloat.invoke(vc, r,g,b,a);
                    if (normalFloat != null) {
                        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
                        float mag = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                        if (mag != 0) { nx/=mag; ny/=mag; nz/=mag; }
                        try { normalFloat.invoke(vc, nx, ny, nz); } catch (Throwable ignored) {}
                    }
                    if (finalizeMethod != null) try { finalizeMethod.invoke(vc); } catch (Throwable ignored) {}

                    vertex3.invoke(vc, x2, y2, z2);
                    if (colorInt != null) colorInt.invoke(vc, (int)(r*255f), (int)(g*255f), (int)(b*255f), (int)(a*255f));
                    else if (colorFloat != null) colorFloat.invoke(vc, r,g,b,a);
                    if (normalFloat != null) {
                        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
                        float mag = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                        if (mag != 0) { nx/=mag; ny/=mag; nz/=mag; }
                        try { normalFloat.invoke(vc, nx, ny, nz); } catch (Throwable ignored) {}
                    }
                    if (finalizeMethod != null) try { finalizeMethod.invoke(vc); } catch (Throwable ignored) {}

                    if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                        System.out.println("[OrePicker][OutlineRenderer DEBUG] emitted via 3-arg BufferBuilder vertex on " + cls.getName());
                    return true;
                } catch (Throwable t) {
                    if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                        System.err.println("[OrePicker][OutlineRenderer DEBUG] tryEmitOnBufferBuilder(3-arg) failed:");
                        t.printStackTrace();
                    }
                }
            }

        } catch (Throwable ignored) {}

        return false;
    }

    private static boolean tryInvokeColorOn(Object target, float r, float g, float b, float a) {
        if (target == null) return false;
        Class<?> c = target.getClass();
        try {
            // try int,int,int,int
            for (Method m : c.getMethods()) {
                String mn = m.getName().toLowerCase();
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 4 && (pts[0] == int.class || pts[0] == Integer.class)) {
                    if (!mn.contains("color") && !mn.contains("method_1336") && !mn.contains("method_22915")) continue;
                    try {
                        m.invoke(target, (int)(r*255f), (int)(g*255f), (int)(b*255f), (int)(a*255f));
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                            System.out.println("[OrePicker][OutlineRenderer DEBUG] color invoked via " + m + " on " + c.getName());
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
            // try float,float,float,float
            for (Method m : c.getMethods()) {
                String mn = m.getName().toLowerCase();
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 4 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2]) && isNumeric(pts[3])) {
                    if (!mn.contains("color") && !mn.contains("method_22915")) continue;
                    try {
                        m.invoke(target, r, g, b, a);
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                            System.out.println("[OrePicker][OutlineRenderer DEBUG] color(float) invoked via " + m + " on " + c.getName());
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean tryFinalizeOn(Object obj) {
        if (obj == null) return false;
        String[] names = new String[] {"next", "endvertex", "endVertex", "emit", "nextvertex", "method_22922", "method_22923", "method_60831"};
        for (String n : names) {
            try {
                for (Method m : obj.getClass().getMethods()) {
                    if (!m.getName().equalsIgnoreCase(n)) continue;
                    if (m.getParameterCount() == 0) {
                        try {
                            m.invoke(obj);
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                                System.out.println("[OrePicker][OutlineRenderer DEBUG] finalize method invoked: " + m.getName() + " on " + obj.getClass().getName());
                            return true;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean isNumeric(Class<?> c) {
        return c == float.class || c == double.class || c == Float.class || c == Double.class ||
                c == int.class || c == Integer.class || c == long.class || c == Long.class;
    }

    private static Object[] buildNumericArgs(Class<?>[] pts, float x, float y, float z, float r, float g, float b, float a) {
        Object[] args = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            float val = 0f;
            if (i == 0) val = x;
            else if (i == 1) val = y;
            else if (i == 2) val = z;
            else if (i == 3) val = r;
            else if (i == 4) val = g;
            else if (i == 5) val = b;
            else if (i == 6) val = a;
            Class<?> t = pts[i];
            if (t == float.class || t == Float.class) args[i] = val;
            else if (t == double.class || t == Double.class) args[i] = (double) val;
            else if (t == int.class || t == Integer.class) args[i] = (int) Math.round(val * 255f);
            else if (t == long.class || t == Long.class) args[i] = (long) Math.round(val);
            else args[i] = val;
        }
        return args;
    }
}
