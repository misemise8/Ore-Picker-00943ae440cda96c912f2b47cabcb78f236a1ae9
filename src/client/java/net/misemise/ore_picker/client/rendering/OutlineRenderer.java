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
import java.util.Set;

/**
 * OutlineRenderer - provider.getBuffer() を使う安定版
 *
 * - provider.getBuffer(RenderLayer.getLines()) で VertexConsumer を取得して発行
 * - 2 パス（base: 太め半透明 GL_GREATER / top: 細め不透明 GL_LEQUAL）
 * - デバッグログは短時間間隔で抑制
 * - 発行は反射ベースの robust emitter（いろんな VertexConsumer 実装に対応）
 */
public final class OutlineRenderer {
    private OutlineRenderer() {}

    private static final float BASE_R = 0f, BASE_G = 0f, BASE_B = 0f, BASE_A = 0.55f;
    private static final float TOP_R  = 1f, TOP_G  = 1f, TOP_B  = 1f, TOP_A  = 1f;
    private static final float BASE_HALF_WIDTH = 0.04f;
    private static final float TOP_HALF_WIDTH  = 0.008f;

    private static final long LOG_MIN_INTERVAL_MS = 250;
    private static long lastLogTime = 0L;

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
                    System.out.println("[OrePicker][OutlineRenderer DEBUG] selectedBlocks.size=" + set.size());
                }
            }

            // provider 経由で VertexConsumer を取得（安全）
            RenderLayer layer = RenderLayer.getLines();
            VertexConsumer vc;
            try {
                vc = provider.getBuffer(layer);
            } catch (Throwable t) {
                // fallback: translucent
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

            if (vc == null) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    long tms = System.currentTimeMillis();
                    if (tms - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                        lastLogTime = tms;
                        System.out.println("[OrePicker][OutlineRenderer DEBUG] VertexConsumer is null");
                    }
                }
                return;
            }

            // Matrix4f を MatrixStack から取得（反射で互換対応）
            Object matrixObj = obtainMatrixFromMatrixStack(matrices);
            if (matrixObj == null) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    long tms = System.currentTimeMillis();
                    if (tms - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                        lastLogTime = tms;
                        System.out.println("[OrePicker][OutlineRenderer DEBUG] matrixObj == null");
                    }
                }
                // matrix が無い場合でも VertexConsumer に numeric vertex を投げて試す（続行）
            }

            // ---------- パスA: base（太め・半透明） ----------
            try {
                RenderSystem.enableBlend();
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);           // depth buffer へ書き込まない
                GL11.glDepthFunc(GL11.GL_GREATER); // 奥にあるものにも描画 -> 透けて見える効果

                for (BlockPos bp : set) {
                    if (bp == null) continue;
                    emitEdgesForBlock(vc, matrixObj, camPos, bp, BASE_HALF_WIDTH, BASE_R, BASE_G, BASE_B, BASE_A);
                }

                // Immediate provider なら明示的に draw()
                if (provider instanceof Immediate) {
                    try { ((Immediate) provider).draw(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    long tms = System.currentTimeMillis();
                    if (tms - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                        lastLogTime = tms;
                        System.err.println("[OrePicker][OutlineRenderer DEBUG] base pass threw:");
                        t.printStackTrace();
                    }
                }
            } finally {
                // restore depth write then adjust for next pass
                GL11.glDepthMask(true);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
            }

            // ---------- パスB: top（細め・不透明） ----------
            try {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(true);
                GL11.glDepthFunc(GL11.GL_LEQUAL);

                for (BlockPos bp : set) {
                    if (bp == null) continue;
                    emitEdgesForBlock(vc, matrixObj, camPos, bp, TOP_HALF_WIDTH, TOP_R, TOP_G, TOP_B, TOP_A);
                }

                if (provider instanceof Immediate) {
                    try { ((Immediate) provider).draw(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    long tms = System.currentTimeMillis();
                    if (tms - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                        lastLogTime = tms;
                        System.err.println("[OrePicker][OutlineRenderer DEBUG] top pass threw:");
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
                    System.out.println("[OrePicker][OutlineRenderer DEBUG] rendered two passes.");
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

    // ブロックの 12 エッジを emit（頂点は robust emitter に任せる）
    private static void emitEdgesForBlock(VertexConsumer vc, Object matrixObj, Vec3d camPos, BlockPos bp,
                                          float halfWidth, float r, float g, float b, float a) {
        double x = bp.getX(), y = bp.getY(), z = bp.getZ();
        Box box = new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0);
        float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
        float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;

        float[][] edges = new float[][]{
                {minX, minY, minZ, maxX, minY, minZ},
                {minX, maxY, minZ, maxX, maxY, minZ},
                {minX, minY, maxZ, maxX, minY, maxZ},
                {minX, maxY, maxZ, maxX, maxY, maxZ},

                {minX, minY, minZ, minX, maxY, minZ},
                {maxX, minY, minZ, maxX, maxY, minZ},
                {minX, minY, maxZ, minX, maxY, maxZ},
                {maxX, minY, maxZ, maxX, maxY, maxZ},

                {minX, minY, minZ, minX, minY, maxZ},
                {maxX, minY, minZ, maxX, minY, maxZ},
                {minX, maxY, minZ, minX, maxY, maxZ},
                {maxX, maxY, minZ, maxX, maxY, maxZ}
        };

        float camX = (float) camPos.x;
        float camY = (float) camPos.y;
        float camZ = (float) camPos.z;

        for (float[] e : edges) {
            float x1 = e[0] - camX, y1 = e[1] - camY, z1 = e[2] - camZ;
            float x2 = e[3] - camX, y2 = e[4] - camY, z2 = e[5] - camZ;

            if (halfWidth > 0f) {
                float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
                float mag = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (mag != 0f) {
                    float nx = dx / mag, ny = dy / mag, nz = dz / mag;
                    float ox = -nz * halfWidth, oy = 0f, oz = nx * halfWidth;
                    x1 -= ox; y1 -= oy; z1 -= oz;
                    x2 += ox; y2 += oy; z2 += oz;
                }
            }

            emitLineRobust(vc, matrixObj, x1, y1, z1, x2, y2, z2, r, g, b, a);
        }
    }

    // robust emitter: VertexConsumer によらずいろんな実装に対応して頂点を投げる
    private static boolean emitLineRobust(VertexConsumer vc, Object matrixObj,
                                          float x1, float y1, float z1,
                                          float x2, float y2, float z2,
                                          float r, float g, float b, float a) {
        if (vc == null) return false;
        try {
            Class<?> cls = vc.getClass();
            // try matrix-aware signature: vertex(Matrix4f, float, float, float)
            if (matrixObj != null) {
                for (Method m : cls.getMethods()) {
                    if (m.getParameterCount() == 4) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts[0].isAssignableFrom(matrixObj.getClass()) && isNumeric(pts[1]) && isNumeric(pts[2]) && isNumeric(pts[3])) {
                            try {
                                Object ret1 = m.invoke(vc, matrixObj, x1, y1, z1);
                                tryInvokeColorOn(ret1 != null ? ret1 : vc, r,g,b,a);
                                tryFinalizeOn(ret1 != null ? ret1 : vc);

                                Object ret2 = m.invoke(vc, matrixObj, x2, y2, z2);
                                tryInvokeColorOn(ret2 != null ? ret2 : vc, r,g,b,a);
                                tryFinalizeOn(ret2 != null ? ret2 : vc);
                                return true;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // fallback: numeric vertex(x,y,z)
        try {
            Class<?> cls = vc.getClass();
            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() == 3) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2])) {
                        try {
                            Object ret1 = m.invoke(vc, x1, y1, z1);
                            tryInvokeColorOn(ret1 != null ? ret1 : vc, r,g,b,a);
                            tryFinalizeOn(ret1 != null ? ret1 : vc);

                            Object ret2 = m.invoke(vc, x2, y2, z2);
                            tryInvokeColorOn(ret2 != null ? ret2 : vc, r,g,b,a);
                            tryFinalizeOn(ret2 != null ? ret2 : vc);
                            return true;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        // multi-arg fallback
        try {
            Class<?> cls = vc.getClass();
            for (Method m : cls.getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 3 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2])) {
                    String nm = m.getName().toLowerCase();
                    if (!nm.contains("vertex") && !nm.contains("pos") && !nm.contains("put") && !nm.contains("add")) continue;
                    try {
                        Object[] args1 = buildNumericArgs(pts, x1,y1,z1, r,g,b,a);
                        Object[] args2 = buildNumericArgs(pts, x2,y2,z2, r,g,b,a);
                        m.invoke(vc, args1);
                        m.invoke(vc, args2);
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private static boolean tryInvokeColorOn(Object target, float r, float g, float b, float a) {
        if (target == null) return false;
        Class<?> c = target.getClass();
        try {
            for (Method m : c.getMethods()) {
                String mn = m.getName().toLowerCase();
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 4 && (pts[0] == int.class || pts[0] == Integer.class) && (mn.contains("color") || mn.contains("method_1336") || mn.contains("method_22915"))) {
                    try {
                        m.invoke(target, (int)(r*255f), (int)(g*255f), (int)(b*255f), (int)(a*255f));
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
            for (Method m : c.getMethods()) {
                String mn = m.getName().toLowerCase();
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 4 && isNumeric(pts[0]) && isNumeric(pts[1]) && isNumeric(pts[2]) && isNumeric(pts[3]) && (mn.contains("color") || mn.contains("method_22915"))) {
                    try {
                        m.invoke(target, r, g, b, a);
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean tryFinalizeOn(Object obj) {
        if (obj == null) return false;
        String[] names = new String[] {"next", "endvertex", "endVertex", "emit", "nextvertex", "method_22922", "method_22923"};
        for (String n : names) {
            try {
                for (Method m : obj.getClass().getMethods()) {
                    if (!m.getName().equalsIgnoreCase(n)) continue;
                    if (m.getParameterCount() == 0) {
                        try {
                            m.invoke(obj);
                            return true;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
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
