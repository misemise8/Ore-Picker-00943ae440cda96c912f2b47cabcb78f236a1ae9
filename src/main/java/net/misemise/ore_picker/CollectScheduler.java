package net.misemise.ore_picker;

import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.UUID;

/**
 * CollectScheduler:
 * - スケジュール時点でプレイヤーのメインハンドツールをコピーして保持します。
 * - processPending() 内で AutoCollect と VeinMiner を呼び出す際にそのツールを渡します。
 */
public final class CollectScheduler {
    private CollectScheduler() {}

    private static final Queue<ScheduledCollect> PENDING = new ConcurrentLinkedQueue<>();

    private static final class ScheduledCollect {
        final ServerWorld world;
        final BlockPos pos;
        final BlockState state;
        final UUID playerUuid;
        final boolean allowVein;
        final ItemStack toolStack; // コピーされたツール（null の場合あり）

        ScheduledCollect(ServerWorld world, BlockPos pos, BlockState state, UUID playerUuid, boolean allowVein, ItemStack toolStack) {
            this.world = world;
            this.pos = pos;
            this.state = state;
            this.playerUuid = playerUuid;
            this.allowVein = allowVein;
            this.toolStack = toolStack;
        }
    }

    /**
     * スケジュールする。呼び出し元は world/playerUuid/pos/state を渡す。
     * ここで可能であればプレイヤーを解決して main hand のコピーを取る（Fortune の安定適用用）。
     */
    public static void schedule(ServerWorld world, BlockPos pos, UUID playerUuid, BlockState state, boolean allowVein) {
        if (world == null || pos == null || playerUuid == null) return;

        ItemStack toolCopy = null;
        try {
            if (world.getServer() != null) {
                try {
                    ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(playerUuid);
                    if (p != null) {
                        try {
                            ItemStack main = p.getMainHandStack();
                            if (main != null) toolCopy = main.copy();
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        PENDING.add(new ScheduledCollect(world, pos, state, playerUuid, allowVein, toolCopy));
        try {
            System.out.println("[CollectScheduler] queued collect for " + playerUuid + " at " + pos + " allowVein=" + allowVein + " tool=" + (toolCopy != null));
        } catch (Throwable ignored) {}
    }

    /**
     * 外部 (Ore_picker など) から毎 tick 呼ばれることを想定する互換メソッド。
     */
    public static void tick(MinecraftServer server) {
        processPending();
    }

    public static void processPending() {
        int processed = 0;
        ScheduledCollect sc;
        while ((sc = PENDING.poll()) != null) {
            try {
                processCollect(sc);
                processed++;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        try {
            if (processed > 0) {
                System.out.println("[CollectScheduler] processed " + processed + " scheduled collects this tick.");
            }
        } catch (Throwable ignored) {}
    }

    private static void processCollect(ScheduledCollect sc) {
        if (sc == null || sc.world == null) return;

        try {
            if (ConfigManager.INSTANCE != null && !ConfigManager.INSTANCE.autoCollectEnabled) {
                return;
            }
        } catch (Throwable ignored) {}

        ServerPlayerEntity player = null;
        try {
            if (sc.world.getServer() != null) {
                try {
                    player = sc.world.getServer().getPlayerManager().getPlayer(sc.playerUuid);
                } catch (Throwable ignored) {
                    player = null;
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (sc.state != null && sc.world != null && player != null) {
                AutoCollectHandler.collectDrops(sc.world, player, sc.pos, sc.state);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        try {
            if (sc.allowVein && sc.world != null && player != null) {
                int configured = (ConfigManager.INSTANCE != null) ? ConfigManager.INSTANCE.maxVeinSize : 64;
                try {
                    if (ConfigManager.INSTANCE != null) {
                        int cap = ConfigManager.INSTANCE.maxVeinSizeCap;
                        if (cap > 0) configured = Math.min(configured, cap);
                    }
                } catch (Throwable ignored) {}

                int limit = Math.max(0, configured);

                try {
                    // ツールコピーを渡して Fortune 等を安定させる
                    int broken = VeinMiner.mineAndSchedule(sc.world, player, sc.pos, sc.state, sc.playerUuid, limit, sc.toolStack);
                    try {
                        System.out.println("[CollectScheduler] Vein broken: " + broken + " " + (sc.state != null ? sc.state.getBlock().toString() : "unknown"));
                    } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        } catch (Throwable ignored) {}
    }
}
