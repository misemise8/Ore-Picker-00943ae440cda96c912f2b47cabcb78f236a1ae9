package net.misemise.ore_picker;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.misemise.ore_picker.network.HoldC2SPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.block.entity.BlockEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side initializer (logging-enabled).
 */
public class Ore_picker implements ModInitializer {
    public static final Map<UUID, Boolean> playerHoldState = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        System.out.println("[OrePicker] server initializing (logging-enabled)");

        // register payload codec (server-side)
        try {
            PayloadTypeRegistry.playC2S().register(HoldC2SPayload.TYPE, HoldC2SPayload.CODEC);
            System.out.println("[OrePicker] Registered HoldC2SPayload codec for " + HoldC2SPayload.ID);
        } catch (Throwable t) {
            System.err.println("[OrePicker] Failed to register codec (server):");
            t.printStackTrace();
        }

        // register receiver
        try {
            ServerPlayNetworking.registerGlobalReceiver(HoldC2SPayload.TYPE, (payload, context) -> {
                boolean hold;
                try {
                    hold = payload.pressed();
                } catch (Throwable t) {
                    return;
                }

                ServerPlayerEntity player = context.player();
                UUID uuid = player.getUuid();
                playerHoldState.put(uuid, hold);

                // update on server thread and notify
                context.server().execute(() -> {
                    player.sendMessage(Text.of("[OrePicker] auto-collect: " + (hold ? "ON" : "OFF")), false);
                    System.out.println("[OrePicker] Received hold state from " + player.getGameProfile().getName() + ": " + hold);
                });
            });
            System.out.println("[OrePicker] Registered payload receiver.");
        } catch (Throwable t) {
            System.err.println("[OrePicker] Failed to register payload receiver:");
            t.printStackTrace();
        }

        // register block-break AFTER -> schedule next-tick collection
        PlayerBlockBreakEvents.AFTER.register((World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity) -> {
            try {
                // we only schedule on server thread
                if (!(world instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
                    // debug log for non-server calls
                    //System.out.println("[OrePicker] AFTER event called on client side or non-server world.");
                    return;
                }
                if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                    //System.out.println("[OrePicker] AFTER event player is not server player.");
                    return;
                }

                UUID uuid = player.getUuid();
                boolean enabled = playerHoldState.getOrDefault(uuid, false);
                System.out.println("[OrePicker] AFTER event: player=" + uuid + " enabled=" + enabled + " pos=" + pos);

                if (!enabled) return;

                // schedule for next tick
                // ----- 修正点：最後の boolean 引数を追加 -----
                CollectScheduler.schedule(serverWorld, pos, uuid, state, true);
                System.out.println("[OrePicker] Scheduled collect for next tick: " + pos + " for player " + uuid);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });

        // register server tick to flush scheduled collects
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                CollectScheduler.tick(server);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            // keep any other server-tick tasks here (VeinMineTracker timeouts etc)
            try {
                VeinMineTracker.handleTimeouts(uuid -> {
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        if (p.getUuid().equals(uuid)) return p;
                    }
                    return null;
                });
            } catch (Throwable ignored) {}
        });

        System.out.println("[OrePicker] server initialization complete");
    }
}
