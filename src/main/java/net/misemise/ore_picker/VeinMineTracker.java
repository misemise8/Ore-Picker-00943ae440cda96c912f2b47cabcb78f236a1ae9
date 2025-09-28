package net.misemise.ore_picker;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.misemise.ore_picker.config.ConfigManager;

/**
 * VeinMineTracker
 * - AutoCollectHandler.increment(...) を呼ぶことでカウントする（壊したごとにインクリメント）
 * - 一定時間（INACTIVITY_TIMEOUT_MS）破壊イベントが来なければ「終了」とみなして通知する
 * - startTracking/stopAndNotify を提供（将来の明示的 start/stop 用）
 *
 * 修正:
 * - チャット送信は ConfigManager.INSTANCE.logToChat または ConfigManager.INSTANCE.debug が true のときのみ行う
 */
public final class VeinMineTracker {
    private VeinMineTracker() {}

    // player UUID -> count
    private static final Map<UUID, Integer> COUNTS = new ConcurrentHashMap<>();
    // player UUID -> first-broken-block-id (string)
    private static final Map<UUID, String> FIRST_ID = new ConcurrentHashMap<>();
    // player UUID -> millis of last increment
    private static final Map<UUID, Long> LAST_ACTION_MS = new ConcurrentHashMap<>();

    // inactivity timeout in milliseconds; tuneable
    private static final long INACTIVITY_TIMEOUT_MS = 300L; // 推奨: 200 - 500

    /** 明示的にトラッキングを開始したい場合に呼ぶ */
    public static void startTracking(UUID playerUuid) {
        COUNTS.put(playerUuid, 0);
        FIRST_ID.remove(playerUuid);
        LAST_ACTION_MS.put(playerUuid, System.currentTimeMillis());
    }

    /** 破壊が確定したら AutoCollectHandler などから呼ぶ（必須ではないが推奨） */
    public static void increment(UUID playerUuid, String blockId) {
        COUNTS.compute(playerUuid, (k, v) -> (v == null) ? 1 : v + 1);
        FIRST_ID.putIfAbsent(playerUuid, blockId);
        LAST_ACTION_MS.put(playerUuid, System.currentTimeMillis());
    }

    /** 明示的にトラッキングを終了して通知したい場合に呼ぶ */
    public static void stopAndNotify(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        Integer cnt = COUNTS.remove(uuid);
        String id = FIRST_ID.remove(uuid);
        LAST_ACTION_MS.remove(uuid);
        if (cnt == null) cnt = 0;
        if (id == null) id = "unknown";

        // チャット出力は設定依存にする
        boolean allowChat = false;
        try {
            if (ConfigManager.INSTANCE != null) {
                allowChat = ConfigManager.INSTANCE.logToChat || ConfigManager.INSTANCE.debug;
            }
        } catch (Throwable ignored) {}

        try {
            if (allowChat) {
                player.sendMessage(Text.literal("Broke " + cnt + " " + id), false);
            }
        } catch (Throwable ignored) {}

        // コンソールには常に出す（サーバーログ）
        try {
            System.out.println("[VeinMineTracker] Finalized for " + uuid + " -> Broke " + cnt + " " + id);
        } catch (Throwable ignored) {}
    }

    /**
     * サーバ Tick 側から定期的に呼ぶメソッド。
     * playerLookup: UUID -> ServerPlayerEntity (may return null if player not online)
     * このメソッドはサーバスレッド上で呼ぶこと（ServerTickEvents 内で呼ぶ想定）
     */
    public static void handleTimeouts(Function<UUID, ServerPlayerEntity> playerLookup) {
        long now = System.currentTimeMillis();
        // 固有のキー配列を作る（ConcurrentHashMap を回しているため CME を避ける）
        UUID[] keys = LAST_ACTION_MS.keySet().toArray(new UUID[0]);
        for (UUID uuid : keys) {
            Long last = LAST_ACTION_MS.get(uuid);
            if (last == null) continue;
            if (now - last >= INACTIVITY_TIMEOUT_MS) {
                // finalize
                Integer cnt = COUNTS.remove(uuid);
                String id = FIRST_ID.remove(uuid);
                LAST_ACTION_MS.remove(uuid);
                if (cnt == null) cnt = 0;
                if (id == null) id = "unknown";

                ServerPlayerEntity player = playerLookup.apply(uuid);
                if (player != null) {
                    // チャット出力は設定依存にする
                    boolean allowChat = false;
                    try {
                        if (ConfigManager.INSTANCE != null) {
                            allowChat = ConfigManager.INSTANCE.logToChat || ConfigManager.INSTANCE.debug;
                        }
                    } catch (Throwable ignored) {}

                    try {
                        if (allowChat) {
                            player.sendMessage(Text.literal("Broke " + cnt + " " + id), false);
                        }
                    } catch (Throwable ignored) {}

                    // コンソールには常に出す（デバッグ用）
                    try {
                        System.out.println("[VeinMineTracker] Timeout finalize for " + uuid + ": Broke " + cnt + " " + id);
                    } catch (Throwable ignored) {}
                }
            }
        }
    }
}
