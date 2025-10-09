package net.misemise.ore_picker;

import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
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
 *
 * 追加:
 * - サーバー側の安全上限 HARD_VEIN_CAP を導入（クライアントの改竄防止）
 * - Vein 実行前に必ず OreUtils.isOre(state) を確認
 * - 必要に応じて「つるはしのみで実行（requirePickaxeForVein）」のチェックを行う
 * - クリエイティブ適用は ConfigManager.applyInCreative で制御
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

    // サーバー側のハード上限（安全のためサーバーで強制）
    private static final int HARD_VEIN_CAP = 2048;

    /**
     * 既存互換: ツールキャプチャは内部で試行する
     */
    public static void schedule(ServerWorld world, BlockPos pos, UUID playerUuid, BlockState state, boolean allowVein) {
        schedule(world, pos, playerUuid, state, allowVein, null);
    }

    /**
     * ツールを明示してスケジュールする場合はこちら。
     * providedToolStack が null の場合は呼び出し時にプレイヤーの手をキャプチャします（可能ならコピー）。
     */
    public static void schedule(ServerWorld world, BlockPos pos, UUID playerUuid, BlockState state, boolean allowVein, ItemStack providedToolStack) {
        if (world == null || pos == null || playerUuid == null) return;

        ItemStack toolCopy = null;
        if (providedToolStack != null) {
            try { toolCopy = providedToolStack.copy(); } catch (Throwable ignored) { toolCopy = providedToolStack; }
        } else {
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
        }

        PENDING.add(new ScheduledCollect(world, pos, state, playerUuid, allowVein, toolCopy));
        try {
            OrePickerLog.debug("queued collect for " + playerUuid + " at " + pos + " allowVein=" + allowVein + " tool=" + (toolCopy != null));
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
                OrePickerLog.debug("processed " + processed + " scheduled collects this tick.");
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
                // toolStack を渡して AutoCollect を呼ぶ（AutoCollectHandler 内でも isOre 判定あり）
                AutoCollectHandler.collectDrops(sc.world, player, sc.pos, sc.state, sc.toolStack);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        try {
            if (sc.allowVein && sc.world != null && player != null) {
                // ----- 鉱石判定 -----
                boolean isOre = false;
                try { isOre = net.misemise.ore_picker.OreUtils.isOre(sc.state); } catch (Throwable ignored) {}
                if (!isOre) {
                    // 非鉱石なら Vein を行わない
                    return;
                }

                // ----- クリエイティブ適用チェック -----
                boolean applyInCreative = false;
                try {
                    if (ConfigManager.INSTANCE != null) applyInCreative = ConfigManager.INSTANCE.applyInCreative;
                } catch (Throwable ignored) {}
                if (!applyInCreative) {
                    try {
                        if (player.interactionManager != null && player.interactionManager.getGameMode().isCreative()) {
                            return;
                        }
                    } catch (Throwable ignored) {
                        try {
                            if (player.isCreative()) return;
                        } catch (Throwable ignored2) {}
                    }
                }

                // ----- ツール条件（つるはしのみ）チェック -----
                boolean requirePickaxe = true;
                try {
                    if (ConfigManager.INSTANCE != null) requirePickaxe = ConfigManager.INSTANCE.requirePickaxeForVein;
                } catch (Throwable ignored) {}

                if (requirePickaxe) {
                    boolean hasPickaxe = false;
                    try {
                        ItemStack usedTool = sc.toolStack != null ? sc.toolStack : player.getMainHandStack();
                        if (usedTool != null) {
                            try {
                                if (usedTool.getItem() instanceof PickaxeItem) {
                                    hasPickaxe = true;
                                } else {
                                    try {
                                        String cls = usedTool.getItem().getClass().getSimpleName().toLowerCase();
                                        if (cls.contains("pickaxe") || cls.contains("pickaxeitem")) hasPickaxe = true;
                                    } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}

                    if (!hasPickaxe) {
                        return;
                    }
                }

                // ----- limit (config + cap) -----
                int configured = (ConfigManager.INSTANCE != null) ? ConfigManager.INSTANCE.maxVeinSize : 64;
                try {
                    if (ConfigManager.INSTANCE != null) {
                        int cap = ConfigManager.INSTANCE.maxVeinSizeCap;
                        if (cap > 0) configured = Math.min(configured, cap);
                    }
                } catch (Throwable ignored) {}

                int limit = Math.max(0, configured);
                // サーバー側ハード上限を適用（クライアント側設定の改竄保護）
                limit = Math.min(limit, HARD_VEIN_CAP);

                try {
                    int broken = VeinMiner.mineAndSchedule(sc.world, player, sc.pos, sc.state, sc.playerUuid, limit, sc.toolStack);
                    try {
                        OrePickerLog.debug("Vein broken: " + broken + " " + (sc.state != null ? sc.state.getBlock().toString() : "unknown"));
                    } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        } catch (Throwable ignored) {}
    }
}
