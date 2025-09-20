package net.misemise.ore_picker;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side key state holder.
 * - setHolding(...) を Ore_picker の receiver から呼ぶ。
 * - isHolding(...) を MineAllHandler 等で参照する。
 */
public class KeybindHandler {
    private static final Map<UUID, Boolean> HOLDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_PRESSED_MS = new ConcurrentHashMap<>();

    // ミリ秒ウィンドウ — 必要なら変更
    private static final long SHORT_HOLD_WINDOW = 700L;

    public static void setHolding(UUID id, boolean holding) {
        if (holding) {
            HOLDING.put(id, true);
            LAST_PRESSED_MS.put(id, System.currentTimeMillis());
        } else {
            HOLDING.remove(id);
            LAST_PRESSED_MS.remove(id);
        }
    }

    /**
     * ホールド判定。
     * 1) 明示的に HOLDING に true があれば true
     * 2) ない場合でも最後に押してから SHORT_HOLD_WINDOW ミリ秒以内なら true（遅延吸収）
     */
    public static boolean isHolding(UUID playerId) {
        Boolean b = HOLDING.get(playerId);
        if (b != null && b) return true;

        Long last = LAST_PRESSED_MS.get(playerId);
        if (last != null) {
            long delta = System.currentTimeMillis() - last;
            if (delta >= 0 && delta <= SHORT_HOLD_WINDOW) {
                return true;
            } else {
                LAST_PRESSED_MS.remove(playerId);
            }
        }
        return false;
    }
}
