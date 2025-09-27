package net.misemise.ore_picker.client.mixin;

import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin: InGameHud.render の TAIL に差し込む（現在の mappings でのシグネチャに合わせる）
 *  -> DrawContext, RenderTickCounter が渡される環境用
 *
 *  ここから HoldHudOverlay.renderOnTop(...) を呼び出す。
 */
@Environment(EnvType.CLIENT)
@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(DrawContext drawContext, RenderTickCounter renderTickCounter, CallbackInfo ci) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;

            // HoldHudOverlay に DrawContext + RenderTickCounter 経路を追加してあるのでそれを呼ぶ
            net.misemise.ore_picker.client.HoldHudOverlay.renderOnTop(client, drawContext, renderTickCounter);
        } catch (Throwable ignored) {}
    }
}
