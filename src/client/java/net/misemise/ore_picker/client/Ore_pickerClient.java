package net.misemise.ore_picker.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

import net.misemise.ore_picker.Ore_picker;
import net.misemise.ore_picker.network.HoldC2SPayload;

/**
 * Client initializer:
 * - register (optional) codec on client side
 * - register keybind (V) and send HoldC2SPayload when toggled
 * - expose localHold for HUD to read
 */
public class Ore_pickerClient implements ClientModInitializer {
    private static KeyBinding holdKey;
    public static volatile boolean localHold = false;
    private static boolean lastSent = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[OrePickerClient] client init: registering codec (client side) + keybind");

        // register codec on client side playC2S too — some environments require codec present on both sides
        try {
            PayloadTypeRegistry.playC2S().register(HoldC2SPayload.TYPE, HoldC2SPayload.CODEC);
            System.out.println("[OrePickerClient] Registered HoldC2SPayload codec on client side");
        } catch (Throwable t) {
            System.err.println("[OrePickerClient] Could not register codec on client side (non-fatal):");
            t.printStackTrace();
        }

        holdKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.orepicker.hold",
                GLFW.GLFW_KEY_V,
                "category.orepicker"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            boolean pressed = holdKey.isPressed();
            localHold = pressed;

            if (pressed != lastSent) {
                lastSent = pressed;
                sendHoldPayload(pressed);
            }
        });

        // HUD
        HoldHudOverlay.register();
    }

    private static void sendHoldPayload(boolean pressed) {
        try {
            HoldC2SPayload payload = new HoldC2SPayload(pressed);
            // Use ClientPlayNetworking.send(CustomPayload) — available in modern Fabric client API
            ClientPlayNetworking.send(payload);
        } catch (Throwable t) {
            System.err.println("[OrePickerClient] Failed to send HoldC2SPayload via ClientPlayNetworking.send(payload).");
            t.printStackTrace();
            // fallback: if necessary we could send PacketByteBuf raw here — but avoid unless needed
        }
    }
}
