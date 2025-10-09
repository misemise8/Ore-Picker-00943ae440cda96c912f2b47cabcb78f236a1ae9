package net.misemise.ore_picker;

import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.misemise.ore_picker.network.HoldC2SPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.text.Text;

/**
 * Server-side initializer (logging-enabled).
 */
public class Ore_picker implements ModInitializer {
    public static final Map<UUID, Boolean> playerHoldState = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        System.out.println("[OrePicker] server initializing (logging-enabled)");

        // Load config first (creates config/orepicker.properties if missing)
        try {
            ConfigManager.load();
        } catch (Throwable t) {
            System.err.println("[OrePicker] Failed to load config:");
            t.printStackTrace();
        }

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
                if (player == null) return;
                UUID uuid = player.getUuid();

                // 保守的に両方更新（互換性確保）
                playerHoldState.put(uuid, hold);
                try { KeybindHandler.setHolding(uuid, hold); } catch (Throwable ignored) {}

                // update on server thread and notify
                context.server().execute(() -> {
                    try {
                        // チャットでの表示は debug フラグが true のときだけ
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                            try {
                                player.sendMessage(Text.of("[OrePicker] auto-collect: " + (hold ? "ON" : "OFF")), false);
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}

                    // 常にコンソールには出す（デバッグの有無に関係なくログを見たい場合）
                    try {
                        System.out.println("[OrePicker] Received hold state from " + player.getGameProfile().getName() + ": " + hold);
                    } catch (Throwable ignored) {}
                });
            });
            System.out.println("[OrePicker] Registered payload receiver.");
        } catch (Throwable t) {
            System.err.println("[OrePicker] Failed to register payload receiver:");
            t.printStackTrace();
        }

        // register block-break BEFORE -> allow cancellation & schedule collect
        PlayerBlockBreakEvents.BEFORE.register((World world, net.minecraft.entity.player.PlayerEntity player, BlockPos pos, BlockState state, net.minecraft.block.entity.BlockEntity blockEntity) -> {
            try {
                // server-only behaviour
                if (!(world instanceof ServerWorld serverWorld)) return true;
                if (!(player instanceof ServerPlayerEntity serverPlayer)) return true;

                UUID uuid = serverPlayer.getUuid();
                boolean enabled = false;
                try {
                    // 優先して KeybindHandler を参照（より堅牢）
                    enabled = KeybindHandler.isHolding(uuid);
                } catch (Throwable ignored) {}

                // 互換性: playerHoldState も確認
                try {
                    if (!enabled) enabled = playerHoldState.getOrDefault(uuid, false);
                } catch (Throwable ignored) {}

                System.out.println("[OrePicker] BEFORE event: player=" + uuid + " enabled=" + enabled + " pos=" + pos);

                if (!enabled) {
                    return true; // 通常の破壊を続行
                }

                // schedule for next tick (safe: copy toolstack)
                ItemStack toolCopy = null;
                try {
                    ItemStack main = serverPlayer.getMainHandStack();
                    if (main != null) toolCopy = main.copy();
                } catch (Throwable ignored) {}

                // allowVein = true so CollectScheduler will run VeinMiner
                CollectScheduler.schedule(serverWorld, pos, uuid, state, true, toolCopy);
                System.out.println("[OrePicker] Scheduled collect for next tick (VEIN) at: " + pos + " for player " + uuid);

                // cancel vanilla handling (we perform vein mining instead)
                return false;
            } catch (Throwable t) {
                t.printStackTrace();
                return true;
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
