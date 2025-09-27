package net.misemise.ore_picker.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.misemise.ore_picker.client.HoldHudOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChatHud に TAIL で差し込む（ログに出ている DrawContext,int,int,int,boolean のシグネチャへ）
 * この一つだけにしておけば「期待されたシグネチャ」と一致し、InvalidInjectionException を回避できます。
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIIZ)V", at = @At("TAIL"), require = 1)
    private void onRenderDrawContextInts(DrawContext drawContext, int p1, int p2, int p3, boolean p4, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        // DrawContext (drawContext) をそのまま HoldHudOverlay に渡す（renderOnTop は Object を受けるよう作ってあるはず）
        HoldHudOverlay.renderOnTop(client, drawContext, 0f);
    }
}
