package net.misemise.ore_picker.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.lwjgl.opengl.GL11;

/**
 * BlockOutlineRenderer - GL11 フォールバック版
 *
 * - reflection を使ってカメラ位置を取得し、ワールド座標 -> カメラ相対座標に変換して
 *   org.lwjgl.opengl.GL11 の immediate-mode でラインを描画します。
 * - 多くの環境でコンパイル通ることを優先しています（mappings に依存する import を避ける）。
 * - lineWidth はドライバ依存なので期待どおりにならない環境もあります。
 *
 * 注意:
 * - レンダースレッドから呼んでください（HUD/Render イベント内）。
 * - depth の扱い：デフォルトでは深度テストは触りません。常に手前に出したければ
 *   `GL11.glDisable(GL11.GL_DEPTH_TEST);` を有効化してください（推奨はしませんが選択肢として）。
 */
public final class BlockOutlineRenderer {
    private BlockOutlineRenderer() {}

    /**
     * BlockPos に対する 1x1x1 のボックスワイヤーフレームを描画する簡易ヘルパー。
     */
    public static void renderBlockPosOutline(MatrixStack matrices, BlockPos pos, float r, float g, float b, float a, float lineWidth) {
        if (pos == null) return;
        Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
        renderBoxOutline(matrices, box, r, g, b, a, lineWidth);
    }

    /**
     * AABB (Box) のワイヤーフレームを描画する。
     * matrices は使わずカメラ位置のみ参照して相対座標で描画します（互換性重視）。
     */
    public static void renderBoxOutline(MatrixStack matrices, Box box, float r, float g, float b, float a, float lineWidth) {
        if (box == null) return;

        // カメラ位置を反射で安全に取得（mappings による違い回避）
        double camX = 0.0, camY = 0.0, camZ = 0.0;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) {
                Object gameRenderer = null;
                try {
                    // try getter
                    gameRenderer = mc.getClass().getMethod("getGameRenderer").invoke(mc);
                } catch (Throwable ignored) {
                    try {
                        // or access field
                        java.lang.reflect.Field grField = mc.getClass().getField("gameRenderer");
                        gameRenderer = grField.get(mc);
                    } catch (Throwable ignored2) {}
                }

                if (gameRenderer != null) {
                    Object camera = null;
                    try {
                        camera = gameRenderer.getClass().getMethod("getCamera").invoke(gameRenderer);
                    } catch (Throwable ignored) {
                        try {
                            java.lang.reflect.Method m[] = gameRenderer.getClass().getMethods();
                            for (java.lang.reflect.Method mm : m) {
                                if (mm.getName().toLowerCase().contains("camera") && mm.getParameterCount() == 0) {
                                    camera = mm.invoke(gameRenderer);
                                    break;
                                }
                            }
                        } catch (Throwable ignored2) {}
                    }

                    if (camera != null) {
                        // camera.getPos() -> Vec3d (or Vector3d) ; use getX/getY/getZ or x/y/z fields
                        try {
                            Object posVec = camera.getClass().getMethod("getPos").invoke(camera);
                            if (posVec != null) {
                                try {
                                    java.lang.reflect.Method gx = posVec.getClass().getMethod("x");
                                    // some mappings have x() y() z()
                                    camX = ((Number) gx.invoke(posVec)).doubleValue();
                                    camY = ((Number) posVec.getClass().getMethod("y").invoke(posVec)).doubleValue();
                                    camZ = ((Number) posVec.getClass().getMethod("z").invoke(posVec)).doubleValue();
                                } catch (Throwable e1) {
                                    try {
                                        camX = ((Number) posVec.getClass().getMethod("getX").invoke(posVec)).doubleValue();
                                        camY = ((Number) posVec.getClass().getMethod("getY").invoke(posVec)).doubleValue();
                                        camZ = ((Number) posVec.getClass().getMethod("getZ").invoke(posVec)).doubleValue();
                                    } catch (Throwable e2) {
                                        try {
                                            // fields fallback (x,y,z)
                                            java.lang.reflect.Field fx = posVec.getClass().getField("x");
                                            java.lang.reflect.Field fy = posVec.getClass().getField("y");
                                            java.lang.reflect.Field fz = posVec.getClass().getField("z");
                                            camX = ((Number) fx.get(posVec)).doubleValue();
                                            camY = ((Number) fy.get(posVec)).doubleValue();
                                            camZ = ((Number) fz.get(posVec)).doubleValue();
                                        } catch (Throwable ignored3) {}
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        // box のコーナー（ワールド座標）
        double x0 = box.minX;
        double y0 = box.minY;
        double z0 = box.minZ;
        double x1 = box.maxX;
        double y1 = box.maxY;
        double z1 = box.maxZ;

        // カメラ相対座標に変換
        double rx0 = x0 - camX, ry0 = y0 - camY, rz0 = z0 - camZ;
        double rx1 = x1 - camX, ry1 = y1 - camY, rz1 = z1 - camZ;

        // OpenGL immediate (glBegin/glEnd) — 描画状態を最小限で変更して復帰
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            // depth を必ず無効にしたければ次行のコメントを外す（通常は深度テストありの方が自然）
            // GL11.glDisable(GL11.GL_DEPTH_TEST);

            // 線幅
            try {
                GL11.glLineWidth(lineWidth);
            } catch (Throwable ignored) {}

            GL11.glColor4f(r, g, b, a);
            GL11.glBegin(GL11.GL_LINES);

            // bottom rectangle
            GL11.glVertex3d(rx0, ry0, rz0); GL11.glVertex3d(rx1, ry0, rz0);
            GL11.glVertex3d(rx1, ry0, rz0); GL11.glVertex3d(rx1, ry0, rz1);
            GL11.glVertex3d(rx1, ry0, rz1); GL11.glVertex3d(rx0, ry0, rz1);
            GL11.glVertex3d(rx0, ry0, rz1); GL11.glVertex3d(rx0, ry0, rz0);

            // top rectangle
            GL11.glVertex3d(rx0, ry1, rz0); GL11.glVertex3d(rx1, ry1, rz0);
            GL11.glVertex3d(rx1, ry1, rz0); GL11.glVertex3d(rx1, ry1, rz1);
            GL11.glVertex3d(rx1, ry1, rz1); GL11.glVertex3d(rx0, ry1, rz1);
            GL11.glVertex3d(rx0, ry1, rz1); GL11.glVertex3d(rx0, ry1, rz0);

            // vertical edges
            GL11.glVertex3d(rx0, ry0, rz0); GL11.glVertex3d(rx0, ry1, rz0);
            GL11.glVertex3d(rx1, ry0, rz0); GL11.glVertex3d(rx1, ry1, rz0);
            GL11.glVertex3d(rx1, ry0, rz1); GL11.glVertex3d(rx1, ry1, rz1);
            GL11.glVertex3d(rx0, ry0, rz1); GL11.glVertex3d(rx0, ry1, rz1);

            GL11.glEnd();

            // restore depth test if you disabled it earlier (we didn't change it here)
        } finally {
            // restore previous states
            try { GL11.glLineWidth(1.0f); } catch (Throwable ignored) {}
            GL11.glPopAttrib();
        }
    }
}
