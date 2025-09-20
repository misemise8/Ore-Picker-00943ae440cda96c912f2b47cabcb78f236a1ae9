package net.misemise.ore_picker;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * CollectScheduler:
 * - schedule(..., allowVein) で位置をキューに入れる。allowVein==true のエントリのみ
 *   tick 時に VeinMiner を起動する（これが最初の破壊イベント由来のエントリ）。
 * - 重複スケジュールを防ぐためグローバルな SCHEDULED_POSITIONS を維持。
 */
public final class CollectScheduler {
    private CollectScheduler() {}

    private static final class Entry {
        final ServerWorld world;
        final BlockPos pos;
        final UUID playerUuid;
        final BlockState state;
        final boolean allowVein;
        Entry(ServerWorld w, BlockPos p, UUID u, BlockState s, boolean allowVein) { world = w; pos = p; playerUuid = u; state = s; this.allowVein = allowVein; }
    }

    private static final Queue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    // 既にスケジュール済みの座標集合（重複投入防止）
    private static final Set<BlockPos> SCHEDULED_POSITIONS = ConcurrentHashMap.newKeySet();
    // プレイヤー単位で処理中かどうか
    private static final Set<UUID> IN_PROGRESS = ConcurrentHashMap.newKeySet();

    /**
     * schedule: world/pos/player/state を enqueue する。
     * - allowVein: true の場合 tick 時に VeinMiner を呼ぶ（初回破壊由来）
     *               false の場合は VeinMiner を呼ばない（派生破壊由来）
     */
    public static void schedule(ServerWorld world, BlockPos pos, UUID playerUuid, BlockState state, boolean allowVein) {
        if (world == null || pos == null || playerUuid == null) return;

        // 重複スケジュールチェック
        // BlockPos のインスタンス等で等価判定されるので、同じ座標の二重投入を防げる
        boolean already = !SCHEDULED_POSITIONS.add(pos);
        if (already) {
            // 既にスケジュール済み -> スキップ
            return;
        }

        QUEUE.add(new Entry(world, pos, playerUuid, state, allowVein));
        System.out.println("[CollectScheduler] queued collect for " + playerUuid + " at " + pos + " allowVein=" + allowVein);
    }

    /**
     * tick: サーバースレッドから叩くこと（例: ServerTickEvents.END_SERVER_TICK）
     */
    public static void tick(MinecraftServer server) {
        Entry e;
        int processed = 0;

        while ((e = QUEUE.poll()) != null) {
            try {
                UUID puid = e.playerUuid;
                // 同一プレイヤーで同時処理を防ぐ
                boolean acquired = IN_PROGRESS.add(puid);
                if (!acquired) {
                    // 再スケジュールして後で処理する（簡易耐久）
                    try { QUEUE.add(e); } catch (Throwable ignored) {}
                    continue;
                }

                try {
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(puid);
                    if (player == null) {
                        System.out.println("[CollectScheduler] player not found for uuid " + puid);
                        IN_PROGRESS.remove(puid);
                        // 処理終わりに scheduled set から削除
                        SCHEDULED_POSITIONS.remove(e.pos);
                        continue;
                    }

                    System.out.println("[CollectScheduler] processing collect for " + puid + " at " + e.pos + " allowVein=" + e.allowVein);

                    // 1) allowVein が true の場合にのみ VeinMiner を呼ぶ（初回のみ）
                    if (e.allowVein) {
                        try {
                            int limit = net.misemise.ore_picker.config.ConfigManager.INSTANCE != null
                                    ? net.misemise.ore_picker.config.ConfigManager.INSTANCE.maxVeinSize
                                    : 128;
                            VeinMiner.mineAndSchedule(e.world, player, e.pos, e.state, e.playerUuid, limit);
                        } catch (Throwable vm) {
                            vm.printStackTrace();
                        }
                    }

                    // 2) 元の pos の回収処理（AutoCollectHandler.collectDrops）
                    try {
                        AutoCollectHandler.collectDrops(e.world, player, e.pos, e.state);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }

                } finally {
                    IN_PROGRESS.remove(puid);
                    // 処理が終わったら scheduled set から除去して同座標の将来的な再スケジュールを許可
                    SCHEDULED_POSITIONS.remove(e.pos);
                }

                processed++;
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }

        if (processed > 0) {
            System.out.println("[CollectScheduler] processed " + processed + " scheduled collects this tick.");
        }
    }
}
