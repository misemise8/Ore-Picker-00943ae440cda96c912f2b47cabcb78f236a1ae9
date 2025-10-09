package net.misemise.ore_picker.client.rendering;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;

/**
 * AdvancedOutlineRenderer - 単純なラッパー。
 * 問題のあった「OutlineRenderer を変数のように参照する」実装を取り除いてあります。
 */
public final class AdvancedOutlineRenderer {
    private AdvancedOutlineRenderer() {}

    /**
     * WorldRenderCallback 等から呼び出すラッパー。
     * 実装は OutlineRenderer.renderOutlines に委譲します。例外が出たら true を返して描画なしにします。
     */
    public static boolean renderIfNeeded(MinecraftClient client, MatrixStack matrices, Camera camera, float tickDelta) {
        try {
            // OutlineRenderer は同パッケージなので直接呼べます。
            // 万一パッケージが違う場合は完全修飾名で呼んでください。
            return net.misemise.ore_picker.client.rendering.OutlineRenderer.renderOutlines(client, matrices, camera, tickDelta);
        } catch (Throwable t) {
            // 安全のためスタックを出す（デバッグ設定によってはログを無効にしても良い）
            t.printStackTrace();
            return true;
        }
    }
}
