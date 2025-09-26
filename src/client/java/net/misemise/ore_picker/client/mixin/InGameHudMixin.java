package net.misemise.ore_picker.client.mixin;

import net.misemise.ore_picker.client.HoldHudOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 直接クラスを参照して問題が出る場合は文字列ターゲットでも可。
 * 下は compile 時に InGameHud が見つからない場合に備え、文字列ターゲット版のテンプレ。
 */
@Mixin(targets = {"net.minecraft.client.gui.hud.InGameHud"})
public class InGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderTail(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            HoldHudOverlay.renderOnTop(client, matrices, tickDelta); // あなたの実装名に合わせて
        } catch (Throwable ignored) {}
    }
}
