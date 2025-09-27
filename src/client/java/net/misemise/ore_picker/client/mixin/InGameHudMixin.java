package net.misemise.ore_picker.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.misemise.ore_picker.client.HoldHudOverlay;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderTail(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        // 第1引数が DrawContext/MatrixStack であればそれを渡す実装に書き換えてください。
        HoldHudOverlay.renderOnTop(client, null, 0f);
    }
}
