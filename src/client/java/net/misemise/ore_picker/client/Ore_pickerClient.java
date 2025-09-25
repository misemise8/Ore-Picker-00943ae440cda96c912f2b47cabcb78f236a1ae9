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
 * - register codec on client side (but ignore duplicate registration quietly)
 * - keybind + send hold payload
 * - expose localHold for HUD to read
 */
public class Ore_pickerClient implements ClientModInitializer {
    private static KeyBinding holdKey;
    public static volatile boolean localHold = false;
    private static boolean lastSent = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[OrePickerClient] client init: registering codec (client side) + keybind");

        // register codec on client side playC2S — ignore duplicate registration
        try {
            PayloadTypeRegistry.playC2S().register(HoldC2SPayload.TYPE, HoldC2SPayload.CODEC);
            System.out.println("[OrePickerClient] Registered HoldC2SPayload codec on client side");
        } catch (IllegalArgumentException e) {
            // already registered: suppress noisy stack trace
            System.out.println("[OrePickerClient] HoldC2SPayload already registered on client side (ignored).");
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

        net.misemise.ore_picker.client.ConfigScreenOpener.init();
    }

    private static void sendHoldPayload(boolean pressed) {
        try {
            HoldC2SPayload payload = new HoldC2SPayload(pressed);
            ClientPlayNetworking.send(payload);
        } catch (Throwable t) {
            System.err.println("[OrePickerClient] Failed to send HoldC2SPayload via ClientPlayNetworking.send(payload).");
            t.printStackTrace();
        }
    }
}
