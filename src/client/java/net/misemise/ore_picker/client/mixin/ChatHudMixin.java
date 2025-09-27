package net.misemise.ore_picker.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.misemise.ore_picker.client.HoldHudOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChatHud の render の末尾に描画を追加することで、チャットより上に HUD を出す。
 * 1) DrawContext 版
 * 2) MatrixStack 版
 *
 * どちらのシグネチャが実行環境にあるか分からないので両方を用意しています。
 * @Inject の method には明示的にデスクリプタを指定しています。
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {

    // DrawContext 版 (多くの modern mapping ではこちら)
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;F)V", at = @At("TAIL"))
    private void onRenderDrawContextTail(Object drawContext, float tickDelta, CallbackInfo ci) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                // HoldHudOverlay 側で matrix/drawContext を受け取るオーバーロードを用意している想定
                HoldHudOverlay.renderOnTop(client, drawContext, tickDelta);
            }
        } catch (Throwable ignored) {}
    }

    // MatrixStack 版 (古い mapping / 互換性のため)
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;F)V", at = @At("TAIL"))
    private void onRenderMatrixStackTail(Object matrixStack, float tickDelta, CallbackInfo ci) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                HoldHudOverlay.renderOnTop(client, matrixStack, tickDelta);
            }
        } catch (Throwable ignored) {}
    }
}
