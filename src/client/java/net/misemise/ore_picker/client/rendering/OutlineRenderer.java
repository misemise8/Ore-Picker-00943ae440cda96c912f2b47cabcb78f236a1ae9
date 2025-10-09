package net.misemise.ore_picker.client.rendering;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.misemise.ore_picker.client.Ore_pickerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * OutlineRenderer
 *
 * - WorldRenderEvents.AFTER_ENTITIES にフックして選択済みブロック集合のアウトラインを描画する
 * - 描画は「ブロックの枠線」を RenderLayer.getLines() に描いて貫通表示にする
 * - Ore_pickerClient.selectedBlocks を参照（必要に応じて同期）
 */
public final class OutlineRenderer {
    private OutlineRenderer() {}

    public static void register() {
        try {
            WorldRenderEvents.AFTER_ENTITIES.register((context) -> {
                try {
                    render(context);
                } catch (Throwable t) {
                    if (MinecraftClient.getInstance() != null && MinecraftClient.getInstance().player != null) {
                        // swallow, but print in debug mode
                        try {
                            if (net.misemise.ore_picker.config.ConfigManager.INSTANCE != null &&
                                    net.misemise.ore_picker.config.ConfigManager.INSTANCE.debug) t.printStackTrace();
                        } catch (Throwable ignored) {}
                    }
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

        VertexConsumerProvider.Immediate consumers = context.consumers();
        if (consumers == null) return;

        Camera camera = context.camera();
        Vec3d camPos = (camera == null) ? new Vec3d(0,0,0) : camera.getPos();

        Set<BlockPos> set = Ore_pickerClient.selectedBlocks;
        if (set == null || set.isEmpty()) return;

        // 描画色（緑系）お好みで変更可
        float r = 0.6f;
        float g = 1.0f;
        float b = 0.4f;
        float a = 1.0f;

        // get a line render layer and vertex consumer
        RenderLayer linesLayer = RenderLayer.getLines();
        VertexConsumer vc = consumers.getBuffer(linesLayer);

        // iterate thread-safely
        synchronized (set) {
            for (BlockPos bp : set) {
                if (bp == null) continue;

                double x = bp.getX();
                double y = bp.getY();
                double z = bp.getZ();

                // world-space box for the block
                Box box = new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                // offset so that coordinates are relative to camera as many vanilla helpers expect
                Box rel = box.offset(-camPos.x, -camPos.y, -camPos.z);

                // WorldRenderer.drawBox takes MatrixStack, VertexConsumer, Box, r,g,b,a
                try {
                    WorldRenderer.drawBox(matrices, vc, rel, r, g, b, a);
                } catch (Throwable t) {
                    // fallback: draw small wireframe via direct consumer if drawBox signature differs
                    try {
                        WorldRenderer.drawBox(matrices, vc, rel, r, g, b, a);
                    } catch (Throwable ignored) {}
                }
            }
        }

        // flush
        consumers.draw();
    }
}
