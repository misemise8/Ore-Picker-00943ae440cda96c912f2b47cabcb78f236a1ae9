package net.misemise.ore_picker.client;

import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderState;
import net.minecraft.client.gui.DrawableHelper;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import java.lang.reflect.Method;
import java.util.*;

/**
 * BlockOutlineRenderer
 *
 * - 押したキー（toggle）で「ターゲットブロック（クロスヘア）とその Vein をアウトライン表示」
 * - 表示はクライアント世界のブロックを BFS で辿る（maxVeinSize で打ち切り）
 * - 描画は WorldRenderer.drawBox が使えればそれを利用。無ければ簡易ライン描画を試行。
 * - ハイライトに加えて、表示位置（代表位置）に個数ラベルを出す（DrawContext / textRenderer 経路）
 *
 * 互換性のため例外吸収多め。ConfigManager.INSTANCE.debug=true で詳細ログ。
 */
public final class BlockOutlineRenderer {
    private BlockOutlineRenderer() {}

    private static volatile boolean outlineEnabled = false;

    // toggle externally
    public static void toggleOutlineEnabled() {
        outlineEnabled = !outlineEnabled;
        System.out.println("[OrePicker][Outline] outlineEnabled -> " + outlineEnabled);
    }

    public static boolean isOutlineEnabled() {
        return outlineEnabled;
    }

    public static void register() {
        try {
            WorldRenderEvents.AFTER_ENTITIES.register(context -> {
                try {
                    render(context.matrixStack(), context.camera(), context.tickDelta());
                } catch (Throwable t) {
                    try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
                }
            });
            System.out.println("[OrePicker][Outline] registered WorldRenderEvents.AFTER_ENTITIES");
        } catch (Throwable t) {
            System.err.println("[OrePicker][Outline] failed to register world render event (outline disabled):");
            t.printStackTrace();
        }
    }

    private static void render(MatrixStack matrices, Camera camera, float tickDelta) {
        try {
            if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.enableHudOverlay) return; // allow user to disable HUD/outline via same flag
            if (!outlineEnabled) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null) return;

            // get targeted block
            HitResult hr = client.cameraEntity.raycast(8.0, tickDelta, false);
            if (!(hr instanceof BlockHitResult bhr)) return;
            BlockPos targetPos = bhr.getBlockPos();
            if (targetPos == null) return;

            BlockState targetState = client.world.getBlockState(targetPos);
            if (targetState == null) return;

            // ensure it's an ore (use server-side util method if available; fallback to name match)
            boolean isOre = false;
            try {
                isOre = net.misemise.ore_picker.OreUtils.isOre(targetState);
            } catch (Throwable ignored) {
                String s = targetState.getBlock().toString().toLowerCase();
                if (s.contains("ore") || s.contains("ore_")) isOre = true;
            }
            if (!isOre) return;

            // BFS to collect vein blocks (client-side)
            List<BlockPos> vein = collectVein(client, targetPos, targetState, Math.max(1, ConfigManager.INSTANCE != null ? ConfigManager.INSTANCE.maxVeinSize : 64));

            if (vein.isEmpty()) return;

            // draw outlines for each block in vein
            try {
                // prefer WorldRenderer.drawBox(matrix, provider, box, r,g,b,a)
                boolean usedDrawBox = false;
                try {
                    // reflectively call WorldRenderer.drawBox if available
                    Method drawBox = null;
                    for (Method m : WorldRenderer.class.getMethods()) {
                        if (!m.getName().equals("drawBox")) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length >= 5) { drawBox = m; break; }
                    }
                    if (drawBox != null) {
                        // pick color (teal-like)
                        float r = 0.0f;
                        float g = 0.9f;
                        float b = 0.8f;
                        float alpha = 0.6f;
                        for (BlockPos bp : vein) {
                            try {
                                // create a box for that block
                                // new net.minecraft.util.math.Box(x, y, z, x+1, y+1, z+1)
                                Class<?> boxCls = Class.forName("net.minecraft.util.math.Box");
                                Object box = boxCls.getConstructor(double.class,double.class,double.class,double.class,double.class,double.class)
                                        .newInstance(bp.getX(), bp.getY(), bp.getZ(), bp.getX()+1.0, bp.getY()+1.0, bp.getZ()+1.0);
                                drawBox.invoke(null, matrices, (VertexConsumerProvider)client.getBufferBuilders().getEntityVertexConsumers(), box, r, g, b, alpha);
                                usedDrawBox = true;
                            } catch (Throwable ite) {
                                usedDrawBox = false;
                            }
                        }
                    }
                } catch (Throwable t) {
                    usedDrawBox = false;
                }

                if (!usedDrawBox) {
                    // fallback: draw simple wireframes with Tessellator (best-effort)
                    drawWireframeFallback(client, matrices, vein);
                }
            } catch (Throwable t) {
                try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
            }

            // draw a count label at centroid of vein
            try {
                int cnt = vein.size();
                BlockPos centroid = vein.get(0);
                if (!vein.isEmpty()) {
                    long sx = 0, sy = 0, sz = 0;
                    for (BlockPos bp : vein) { sx += bp.getX(); sy += bp.getY(); sz += bp.getZ(); }
                    centroid = new BlockPos(sx / cnt + 0.5, sy / cnt + 1.1, sz / cnt + 0.5);
                }

                // project world coords to screen and draw text
                drawFloatingText(client, matrices, Text.literal("×" + cnt), centroid, tickDelta);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
    }

    private static List<BlockPos> collectVein(MinecraftClient client, BlockPos start, BlockState startState, int limit) {
        List<BlockPos> result = new ArrayList<>();
        try {
            Block target = startState.getBlock();
            Deque<BlockPos> q = new ArrayDeque<>();
            Set<BlockPos> seen = new HashSet<>();
            q.add(start);
            seen.add(start);
            while (!q.isEmpty() && result.size() < limit) {
                BlockPos bp = q.poll();
                if (bp == null) continue;
                BlockState bs = client.world.getBlockState(bp);
                if (bs == null) continue;
                if (bs.getBlock() != target) continue;
                result.add(bp);
                // 6-neighbors
                int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
                for (int[] d : dirs) {
                    BlockPos nb = bp.add(d[0], d[1], d[2]);
                    if (seen.contains(nb)) continue;
                    seen.add(nb);
                    q.add(nb);
                }
            }
        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
        return result;
    }

    // fallback crude wireframe draw (best-effort)
    private static void drawWireframeFallback(MinecraftClient client, MatrixStack matrices, List<BlockPos> blocks) {
        try {
            // This is a naive attempt using Tessellator. In many mappings it will work; if not it fails silently.
            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuffer();
            // disable texture & depth? we want it visible — follow user's pattern to disable depth test earlier if needed
            try { net.minecraft.client.render.RenderSystem.disableDepthTest(); } catch (Throwable ignored) {}
            try { net.minecraft.client.render.RenderSystem.enableBlend(); } catch (Throwable ignored) {}

            // line width is not exposed easily in MC; many renderers ignore it.
            for (BlockPos bp : blocks) {
                double x = bp.getX(), y = bp.getY(), z = bp.getZ();
                // draw 12 edges as lines (GL_LINE loop)
                // we'll use VertexFormats.POSITION_COLOR
                buf.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
                // 8 corners
                double[][] c = {
                        {x, y, z}, {x+1, y, z}, {x+1, y+1, z}, {x, y+1, z},
                        {x, y, z+1}, {x+1, y, z+1}, {x+1, y+1, z+1}, {x, y+1, z+1}
                };
                // edges (pairs)
                int[][] edges = {
                        {0,1},{1,2},{2,3},{3,0},
                        {4,5},{5,6},{6,7},{7,4},
                        {0,4},{1,5},{2,6},{3,7}
                };
                float r = 0.0f, g = 0.9f, b = 0.8f, a = 0.9f;
                for (int[] e : edges) {
                    double[] p1 = c[e[0]];
                    double[] p2 = c[e[1]];
                    buf.vertex(p1[0]-client.getEntityRenderDispatcher().camera.getPos().x, p1[1]-client.getEntityRenderDispatcher().camera.getPos().y, p1[2]-client.getEntityRenderDispatcher().camera.getPos().z).color(r,g,b,a).next();
                    buf.vertex(p2[0]-client.getEntityRenderDispatcher().camera.getPos().x, p2[1]-client.getEntityRenderDispatcher().camera.getPos().y, p2[2]-client.getEntityRenderDispatcher().camera.getPos().z).color(r,g,b,a).next();
                }
                tess.draw();
            }

            try { net.minecraft.client.render.RenderSystem.enableDepthTest(); } catch (Throwable ignored) {}
            try { net.minecraft.client.render.RenderSystem.disableBlend(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
    }

    // draw floating text above a BlockPos (screen-space projection)
    private static void drawFloatingText(MinecraftClient client, MatrixStack matrices, Text text, BlockPos worldPos, float tickDelta) {
        try {
            // Project 3D -> 2D. Use Camera.project if available, otherwise approximate.
            double x = worldPos.getX();
            double y = worldPos.getY();
            double z = worldPos.getZ();

            // compute screen coordinates via camera/window projection if possible
            boolean drawn = false;
            try {
                // Try to use built-in camera API to project
                Object camera = client.gameRenderer.getCamera();
                Method projMethod = null;
                for (Method m : camera.getClass().getMethods()) {
                    if (m.getName().equals("project") && m.getParameterCount() == 6) {
                        projMethod = m;
                        break;
                    }
                    if (m.getName().equals("worldToScreen") && m.getParameterCount() == 4) {
                        projMethod = m;
                        break;
                    }
                }
                // fallback: compute naive screen pos using camera pos and player's view; easier path: compute relative and use DrawableHelper to center with simple offset
            } catch (Throwable ignored) {}

            // naive: draw text at approx center-top of screen but offset by player yaw/pitch (not perfect)
            int sw = client.getWindow().getScaledWidth();
            int sh = client.getWindow().getScaledHeight();
            int cx = sw / 2;
            int cy = sh / 2 - 40; // above crosshair
            // better: project worldPos to screen using built-in method if available
            // but to keep compatibility, fallback to simple approach
            try {
                // try to actually project using MinecraftClient.getEntityRenderDispatcher().camera.getPos() and GameRenderer.getCamera().project (if available)
                // For simplicity: offset based on relative difference to player
                double px = client.player.getX();
                double py = client.player.getY();
                double pz = client.player.getZ();
                double dx = x - px;
                double dy = y - py;
                double dz = z - pz;
                // use small heuristic to offset screen pos by angles
                float yaw = client.player.yaw;
                float pitch = client.player.pitch;
                // naive projection:
                int sx = cx + (int) (dx * 10 - dz * 10);
                int sy = cy - (int) (dy * 10 + pitch / 2.0);
                DrawableHelper.drawCenteredText(matrices, client.textRenderer, text.asOrderedText(), sx, sy, 0x9AFF66);
                drawn = true;
            } catch (Throwable ignored) {}

            if (!drawn) {
                DrawableHelper.drawCenteredText(matrices, client.textRenderer, text.asOrderedText(), client.getWindow().getScaledWidth() / 2, client.getWindow().getScaledHeight() / 2 - 40, 0x9AFF66);
            }
        } catch (Throwable t) {
            try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
        }
    }
}
