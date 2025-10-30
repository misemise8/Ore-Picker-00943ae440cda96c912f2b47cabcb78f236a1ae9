package net.misemise.ore_picker.client.rendering;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.misemise.ore_picker.client.Ore_pickerClient;
import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.client.render.*;
import net.minecraft.client.render.VertexConsumerProvider.Immediate;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * OutlineRenderer - 透ける下地 + 少し太めの白ラインを二重に重ねる安定版
 *
 * Pass A (透過下地):  GL_GREATER, depthMask=false, 半透明太め
 * Pass B (白ライン)  :  GL_LEQUAL,  depthMask=true,  不透明やや太め
 *
 * VertexConsumer への呼び出しは反射で互換性を保つ（キャッシュあり）。
 */
public final class OutlineRenderer {
    private OutlineRenderer() {}

    // 下地 (黒・半透明)
    private static final float BASE_R = 0f, BASE_G = 0f, BASE_B = 0f, BASE_A = 0.45f;
    // 上線 (白・不透明)
    private static final float TOP_R  = 1f, TOP_G  = 1f, TOP_B  = 1f, TOP_A  = 1f;

    // 下地の半幅（ワールド座標単位） — 視認性に合わせて調整
    private static final float BASE_HALF_WIDTH = 0.05f;
    // 上線の半幅（白） — 少し太め
    private static final float TOP_HALF_WIDTH = 0.014f;

    // 描画最大距離（距離が遠いものは描かない。FPS対策）
    private static final double MAX_DRAW_DISTANCE = 48.0;
    private static final double MAX_DRAW_DISTANCE_SQ = MAX_DRAW_DISTANCE * MAX_DRAW_DISTANCE;

    private static final long LOG_MIN_INTERVAL_MS = 300;
    private static long lastLogTime = 0L;

    // VC クラス -> 発行用メソッドのキャッシュ（discover をシンプルに）
    private static final Map<Class<?>, VCMethods> vcMethodsCache = new HashMap<>();

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
        long now = System.currentTimeMillis();
        try {
            if (context == null) return;

            MatrixStack matrices = context.matrixStack();
            VertexConsumerProvider provider = context.consumers();
            net.minecraft.client.render.Camera camera = context.camera();

            if (matrices == null || provider == null) return;

            Vec3d camPos = (camera == null) ? new Vec3d(0,0,0) : camera.getPos();
            Set<BlockPos> set = Ore_pickerClient.selectedBlocks;
            if (set == null || set.isEmpty()) return;

            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                if (now - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                    lastLogTime = now;
                    System.out.println("[OrePicker][OutlineRenderer DEBUG] setSize=" + set.size());
                }
            }

            // VertexConsumer を provider 経由で取得（まず Lines を試す）
            VertexConsumer vc;
            try {
                vc = provider.getBuffer(RenderLayer.getLines());
            } catch (Throwable t1) {
                try {
                    vc = provider.getBuffer(RenderLayer.getTranslucent());
                } catch (Throwable t2) {
                    if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                        long tms = System.currentTimeMillis();
                        if (tms - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                            lastLogTime = tms;
                            System.err.println("[OrePicker][OutlineRenderer DEBUG] provider.getBuffer failed (lines & translucent).");
                            t2.printStackTrace();
                        }
                    }
                    return;
                }
            }
            if (vc == null) return;

            // MatrixStack.peek() から Matrix (Matrix4f など) を反射的に取得（あれば使う）
            Object matrixObj = obtainMatrixFromMatrixStack(matrices);

            // VC メソッドキャッシュ（反射発行を高速化）
            VCMethods methods = vcMethodsCache.get(vc.getClass());
            if (methods == null) {
                methods = VCMethods.discover(vc);
                vcMethodsCache.put(vc.getClass(), methods);
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    System.out.println("[OrePicker][OutlineRenderer DEBUG] discovered VC methods for " + vc.getClass().getName());
                }
            }

            // ---------- PASS A: 透過の下地（奥にも見えるように） ----------
            try {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);             // 深度へ書き込まない
                GL11.glDepthFunc(GL11.GL_GREATER);   // 奥のピクセルにも描画して透けを作る

                synchronized (set) {
                    for (BlockPos bp : set) {
                        if (bp == null) continue;
                        // 距離カリング
                        double cx = bp.getX() + 0.5, cy = bp.getY() + 0.5, cz = bp.getZ() + 0.5;
                        double dx = cx - camPos.x, dy = cy - camPos.y, dz = cz - camPos.z;
                        if (dx*dx + dy*dy + dz*dz > MAX_DRAW_DISTANCE_SQ) continue;

                        emitEdgesAsRibbon(vc, methods, matrixObj, camPos, bp, BASE_HALF_WIDTH, BASE_R, BASE_G, BASE_B, BASE_A);
                    }
                }

                if (provider instanceof Immediate) {
                    try { ((Immediate) provider).draw(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    long tms = System.currentTimeMillis();
                    if (tms - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                        lastLogTime = tms;
                        System.err.println("[OrePicker][OutlineRenderer DEBUG] PASS A threw:");
                        t.printStackTrace();
                    }
                }
            } finally {
                // depthMask を元に戻す（次のパスで true にする）
                GL11.glDepthMask(true);
            }

            // ---------- PASS B: 白ラインを上から描く ----------
            try {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(true);
                GL11.glDepthFunc(GL11.GL_LEQUAL); // 通常の深度比較

                synchronized (set) {
                    for (BlockPos bp : set) {
                        if (bp == null) continue;
                        double cx = bp.getX() + 0.5, cy = bp.getY() + 0.5, cz = bp.getZ() + 0.5;
                        double dx = cx - camPos.x, dy = cy - camPos.y, dz = cz - camPos.z;
                        if (dx*dx + dy*dy + dz*dz > MAX_DRAW_DISTANCE_SQ) continue;

                        emitEdgesAsRibbon(vc, methods, matrixObj, camPos, bp, TOP_HALF_WIDTH, TOP_R, TOP_G, TOP_B, TOP_A);
                    }
                }

                if (provider instanceof Immediate) {
                    try { ((Immediate) provider).draw(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    long tms = System.currentTimeMillis();
                    if (tms - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                        lastLogTime = tms;
                        System.err.println("[OrePicker][OutlineRenderer DEBUG] PASS B threw:");
                        t.printStackTrace();
                    }
                }
            } finally {
                RenderSystem.disableBlend();
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
            }

            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                long tms = System.currentTimeMillis();
                if (tms - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                    lastLogTime = tms;
                    System.out.println("[OrePicker][OutlineRenderer DEBUG] rendered two-pass outline (setSize=" + set.size() + ")");
                }
            }
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                long tms = System.currentTimeMillis();
                if (tms - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                    lastLogTime = tms;
                    System.err.println("[OrePicker][OutlineRenderer DEBUG] render threw:");
                    t.printStackTrace();
                }
            }
        }
    }

    // ブロック 12 エッジを「リボン（三角形2つ）として」発行する（視認性のため幅を持たせる）
    private static void emitEdgesAsRibbon(VertexConsumer vc, VCMethods methods, Object matrixObj, Vec3d camPos, BlockPos bp,
                                          float halfWidth, float r, float g, float b, float a) {
        double x = bp.getX(), y = bp.getY(), z = bp.getZ();
        Box box = new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0);

        float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
        float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;

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

        float camX = (float) camPos.x, camY = (float) camPos.y, camZ = (float) camPos.z;

        for (float[] e : edges) {
            float x1 = e[0] - camX, y1 = e[1] - camY, z1 = e[2] - camZ;
            float x2 = e[3] - camX, y2 = e[4] - camY, z2 = e[5] - camZ;

            if (halfWidth > 0f) {
                float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
                float mag = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                if (mag != 0f) {
                    float nx = dx / mag, ny = dy / mag, nz = dz / mag;
                    // xz 平面で簡易直交ベクトルを作る（シンプルで効果がある）
                    float ox = -nz * halfWidth, oy = 0f, oz = nx * halfWidth;
                    x1 -= ox; y1 -= oy; z1 -= oz;
                    x2 += ox; y2 += oy; z2 += oz;
                }
            }

            // リボンは 2 三角形で表現するが、簡潔化のため
            // 頂点を 6 個投げて Triangles として描ける VC に依存しない形で出す（robust 発行）
            // 三角形 1 (v0, v1, v2), 三角形 2 (v2, v1, v3)
            float v0x = x1, v0y = y1, v0z = z1;
            float v1x = x1, v1y = y1, v1z = z1; // will modify below for other corner
            // We'll compute a simple 'ribbon' across edge by generating small quad: (x1 - perp, x1 + perp) etc.
            // For simplicity reuse the previously offset endpoints: we have effectively two points expanded on each side.
            // Build four corners
            // cornerA = x1 - perp, cornerB = x1 + perp, cornerC = x2 - perp, cornerD = x2 + perp
            // We already used x1/x2 offset above such that x1 == cornerA, x2 == cornerD
            // So reconstruct corners explicitly:
            float dxFull = e[3] - e[0], dyFull = e[4] - e[1], dzFull = e[5] - e[2];
            float lenFull = (float)Math.sqrt(dxFull*dxFull + dyFull*dyFull + dzFull*dzFull);
            if (lenFull == 0f) continue;
            float exn = dxFull / lenFull, eyn = dyFull / lenFull, ezn = dzFull / lenFull;
            float px = -ezn, py = 0f, pz = exn;
            float pmag = (float)Math.sqrt(px*px + py*py + pz*pz);
            if (pmag == 0f) continue;
            px /= pmag; py /= pmag; pz /= pmag;

            float half = halfWidth;
            // world-space corners (based on original endpoints)
            float wx1 = e[0] - px*half, wy1 = e[1] - py*half, wz1 = e[2] - pz*half;
            float wx2 = e[0] + px*half, wy2 = e[1] + py*half, wz2 = e[2] + pz*half;
            float wx3 = e[3] - px*half, wy3 = e[4] - py*half, wz3 = e[5] - pz*half;
            float wx4 = e[3] + px*half, wy4 = e[4] + py*half, wz4 = e[5] + pz*half;

            // convert to camera-relative (matrixObj handling assumes matrix contains model-view; we subtract cam earlier in coordinates above)
            float cvx0 = wx1 - camX, cvy0 = wy1 - camY, cvz0 = wz1 - camZ;
            float cvx1 = wx2 - camX, cvy1 = wy2 - camY, cvz1 = wz2 - camZ;
            float cvx2 = wx3 - camX, cvy2 = wy3 - camY, cvz2 = wz3 - camZ;
            float cvx3 = wx4 - camX, cvy3 = wy4 - camY, cvz3 = wz4 - camZ;

            // Triangle 1: (cvx0, cvx1, cvx2)
            emitVertexRobust(vc, methods, matrixObj, cvx0, cvy0, cvz0, r,g,b,a);
            emitVertexRobust(vc, methods, matrixObj, cvx1, cvy1, cvz1, r,g,b,a);
            emitVertexRobust(vc, methods, matrixObj, cvx2, cvy2, cvz2, r,g,b,a);
            // Triangle 2: (cvx2, cvx1, cvx3)
            emitVertexRobust(vc, methods, matrixObj, cvx2, cvy2, cvz2, r,g,b,a);
            emitVertexRobust(vc, methods, matrixObj, cvx1, cvy1, cvz1, r,g,b,a);
            emitVertexRobust(vc, methods, matrixObj, cvx3, cvy3, cvz3, r,g,b,a);
        }
    }

    // 単一頂点を robust に発行（matrix-aware / numeric / multi-arg を試す）
    private static boolean emitVertexRobust(VertexConsumer vc, VCMethods methods, Object matrixObj,
                                            float x, float y, float z, float r, float g, float b, float a) {
        if (vc == null || methods == null) return false;
        try {
            // matrix-aware
            if (methods.vertexMatrix != null && matrixObj != null) {
                Object ret = methods.vertexMatrix.invoke(vc, matrixObj, x, y, z);
                invokeColor(methods, ret != null ? ret : vc, r,g,b,a);
                invokeEnd(methods, ret != null ? ret : vc);
                return true;
            }
        } catch (Throwable ignored) {}

        try {
            // numeric 3-arg
            if (methods.vertexXYZ != null) {
                Object ret = methods.vertexXYZ.invoke(vc, x, y, z);
                invokeColor(methods, ret != null ? ret : vc, r,g,b,a);
                invokeEnd(methods, ret != null ? ret : vc);
                return true;
            }
        } catch (Throwable ignored) {}

        try {
            // multi-arg fallback
            if (methods.multiVertex != null) {
                Object[] args = buildNumericArgs(methods.multiVertex.getParameterTypes(), x,y,z,r,g,b,a);
                methods.multiVertex.invoke(vc, args);
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    // color の呼び出し（int / float どちらも試す）
    private static void invokeColor(VCMethods m, Object target, float r, float g, float b, float a) {
        if (m == null || target == null) return;
        try {
            if (m.colorInt != null) {
                int ir = (int)(r*255f), ig = (int)(g*255f), ib = (int)(b*255f), ia = (int)(a*255f);
                m.colorInt.invoke(target, ir, ig, ib, ia);
                return;
            }
            if (m.colorFloat != null) {
                m.colorFloat.invoke(target, r,g,b,a);
                return;
            }
        } catch (Throwable ignored) {}
    }

    // end/emit 呼び出し
    private static void invokeEnd(VCMethods m, Object target) {
        if (m == null || target == null) return;
        try {
            if (m.end != null) m.end.invoke(target);
        } catch (Throwable ignored) {}
    }

    // MatrixStack.peek() から matrix-like object を探す
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

    // VCMethods simple container (discovery)
    private static class VCMethods {
        Method vertexMatrix; // (Matrix4f, float, float, float)
        Method vertexXYZ;    // (float, float, float)
        Method multiVertex;  // any multi-arg pos+...
        Method colorInt;     // (int,int,int,int)
        Method colorFloat;   // (float,float,float,float)
        Method end;          // endVertex / next / emit

        static VCMethods discover(VertexConsumer vc) {
            VCMethods m = new VCMethods();
            Class<?> cls = vc.getClass();
            for (Method mm : cls.getMethods()) {
                String name = mm.getName().toLowerCase();
                Class<?>[] pts = mm.getParameterTypes();
                // matrix-aware
                if (pts.length == 4) {
                    try {
                        Class<?> c0 = pts[0];
                        if (c0.getName().toLowerCase().contains("matrix") && isNumeric(pts[1]) && isNumeric(pts[2]) && isNumeric(pts[3])) {
                            m.vertexMatrix = mm;
                        }
                    } catch (Throwable ignored) {}
                }
                // numeric 3-arg
                if (pts.length == 3 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2])) {
                    m.vertexXYZ = mm;
                }
                // multi-arg fallback
                if (pts.length >= 3 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2])) {
                    String nm = mm.getName().toLowerCase();
                    if (nm.contains("vertex") || nm.contains("pos") || nm.contains("put") || nm.contains("add")) {
                        m.multiVertex = mm;
                    }
                }
                // color int
                if (pts.length == 4 && (pts[0] == int.class || pts[0] == Integer.class)) {
                    String nm = name;
                    if (nm.contains("color") || nm.contains("method_1336") || nm.contains("method_22915")) {
                        m.colorInt = mm;
                    }
                }
                // color float
                if (pts.length == 4 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2]) && isNumeric(pts[3])) {
                    String nm = name;
                    if (nm.contains("color") || nm.contains("method_22915")) {
                        m.colorFloat = mm;
                    }
                }
                // end
                if (mm.getParameterCount() == 0) {
                    String nm = name;
                    if (nm.equals("endvertex") || nm.equals("next") || nm.equals("emit") || nm.equals("end") || nm.equals("nextvertex")) {
                        m.end = mm;
                    }
                }
            }
            return m;
        }
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
