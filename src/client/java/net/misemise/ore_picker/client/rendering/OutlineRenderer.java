package net.misemise.ore_picker.client.rendering;

import com.mojang.blaze3d.systems.RenderSystem;
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
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * OutlineRenderer - Liteminer 方式に近づけるための改良版
 *
 * 変更点：
 *  - 下地（太・半透明）→上線（白・細） の二段描画
 *  - 下地は深度マスクをオフにして GL_GREATER を試す（不成功なら depth-test disabled にフォールバック）
 *  - デバッグログは短時間に大量出力しないよう抑制
 *
 * 注：環境差（VertexConsumer のシグネチャ、MatrixStack の entry の名前など）があるため、
 *     もし表示が出ない/出力が不正な場合はログを貼ってください。追加の互換処理を入れます。
 */
public final class OutlineRenderer {
    private OutlineRenderer() {}

    private static final float BASE_R = 0f, BASE_G = 0f, BASE_B = 0f, BASE_A = 0.55f;
    private static final float TOP_R = 1f, TOP_G = 1f, TOP_B = 1f, TOP_A = 1f;
    private static final float THICKNESS_OFFSET = 0.04f; // 下地の広げ量（見やすくするためやや大きめ）
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

    // メインレンダリング（深度制御バージョン）
    private static void render(WorldRenderContext context) {
        long now = System.currentTimeMillis();
        try {
            if (context == null) return;

            MatrixStack matrices = context.matrixStack();
            VertexConsumerProvider provider = context.consumers();
            net.minecraft.client.render.Camera camera = context.camera();

            if (matrices == null || provider == null) return;

            Vec3d camPos = (camera == null) ? new Vec3d(0, 0, 0) : camera.getPos();
            Set<BlockPos> set = Ore_pickerClient.selectedBlocks;
            if (set == null || set.isEmpty()) return;

            // provider 経由で VertexConsumer を取得（安全経路）
            RenderLayer lineLayer = RenderLayer.getLines();
            VertexConsumer vc;
            try {
                vc = provider.getBuffer(lineLayer);
            } catch (Throwable t) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug && now - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                    lastLogTime = now;
                    System.err.println("[OrePicker][OutlineRenderer DEBUG] provider.getBuffer failed:");
                    t.printStackTrace();
                }
                return;
            }

            Object matrixObj = obtainMatrixFromMatrixStack(matrices);
            if (matrixObj == null) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug && now - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                    lastLogTime = now;
                    System.out.println("[OrePicker][OutlineRenderer DEBUG] matrixObj == null, abort render");
                }
                return;
            }

            // 保存しておく GL 状態（try/finally で復元）
            boolean blendWasEnabled = true; // we will just try to restore reasonable defaults
            try {
                // ---------- (A) 下地パス（太め・半透明） ----------
                RenderSystem.enableBlend();
                // depth test enabled, but don't write to depth buffer
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);

                // 試行1：深度関数を GREATER にして、"奥にあるピクセルにも描画" を狙う
                boolean usedGreater = false;
                try {
                    GL11.glDepthFunc(GL11.GL_GREATER);
                    performEmitForSet(vc, matrixObj, camPos, set, true);
                    usedGreater = true;
                } catch (Throwable t) {
                    // GREATER がうまく動かなかったらフォールバックに切り替える（次へ）
                    if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug && now - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                        lastLogTime = now;
                        System.err.println("[OrePicker][OutlineRenderer DEBUG] GL_GREATER path failed, falling back to disabled-depth draw:");
                        t.printStackTrace();
                    }
                }

                // フォールバック：深度テストを完全に切って描画（常に最前面になるが、代わりに必ず見える）
                if (!usedGreater) {
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    performEmitForSet(vc, matrixObj, camPos, set, true);
                    // re-enable for next pass
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                }

                // ---------- (B) 前面パス（白・細） ----------
                // 通常深度関数（LEQUAL）で上書き
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                performEmitForSet(vc, matrixObj, camPos, set, false);

                // Immediate provider の場合は draw() を呼ぶ（必須）
                if (provider instanceof Immediate) {
                    try {
                        ((Immediate) provider).draw();
                    } catch (Throwable ignored) {}
                }

            } finally {
                // GL 状態を復元
                RenderSystem.disableBlend();
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                // ここでは depth test を有効にしておく（元に戻す）
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            }

            // 最小頻度でデバッグ概要のみ出す（大量ログを抑える）
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug && now - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                lastLogTime = now;
                System.out.println("[OrePicker][OutlineRenderer DEBUG] rendered outline setSize=" + set.size());
            }
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug && now - lastLogTime >= LOG_MIN_INTERVAL_MS) {
                lastLogTime = now;
                System.err.println("[OrePicker][OutlineRenderer DEBUG] render threw:");
                t.printStackTrace();
            }
        }
    }

    // perform emit for selected set; if base==true uses base color/offset otherwise top color
    private static void performEmitForSet(Object vcObj, Object matrixObj, Vec3d camPos, Set<BlockPos> set, boolean base) {
        if (vcObj == null) return;
        VertexConsumer vc = (vcObj instanceof VertexConsumer) ? (VertexConsumer) vcObj : null;
        if (vc == null) return;

        float camX = (float) camPos.x;
        float camY = (float) camPos.y;
        float camZ = (float) camPos.z;

        float r = base ? BASE_R : TOP_R;
        float g = base ? BASE_G : TOP_G;
        float b = base ? BASE_B : TOP_B;
        float a = base ? BASE_A : TOP_A;
        float off = base ? THICKNESS_OFFSET : 0f;

        synchronized (set) {
            for (BlockPos bp : set) {
                if (bp == null) continue;
                double x = bp.getX(), y = bp.getY(), z = bp.getZ();
                Box rel = new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0);

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
                    float x1 = e[0] - camX, y1 = e[1] - camY, z1 = e[2] - camZ;
                    float x2 = e[3] - camX, y2 = e[4] - camY, z2 = e[5] - camZ;

                    // expand endpoints slightly for base to give thickness
                    if (off != 0f) {
                        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
                        float mag = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
                        if (mag != 0f) {
                            float nx = dx / mag, ny = dy / mag, nz = dz / mag;
                            // simple orthogonal offset in xz plane (works to make line thicker visually)
                            float ox = -nz * off, oy = 0f, oz = nx * off;
                            x1 -= ox; y1 -= oy; z1 -= oz;
                            x2 += ox; y2 += oy; z2 += oz;
                        }
                    }

                    // emit two vertices via robust emitter (matrixObj may be null)
                    emitLineRobust(vc, matrixObj, x1, y1, z1, x2, y2, z2, r, g, b, a);
                }
            }
        }
    }

    // obtain matrix from MatrixStack.peek() in a robust way
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

    // 以下は既存の互換的頂点発行ルーチン（成功すれば true）
    private static boolean emitLineRobust(VertexConsumer vc, Object matrixObj,
                                          float x1, float y1, float z1,
                                          float x2, float y2, float z2,
                                          float r, float g, float b, float a) {
        if (vc == null) return false;
        try {
            Class<?> cls = vc.getClass();
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
                    if (!nm.contains("vertex") && !nm.contains("pos") && !nm.contains("put") && !nm.contains("set")) continue;
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
