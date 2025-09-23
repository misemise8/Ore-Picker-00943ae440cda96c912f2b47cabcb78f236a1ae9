package net.misemise.ore_picker;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * VeinMiner - 同一鉱石群をまとめて破壊するユーティリティ。
 *
 * - 26近傍（斜め含む）を採用して、斜め接続も含める。
 * - CollectScheduler が呼ぶ mineAndSchedule(...) を提供（ラッパ）。
 */
public final class VeinMiner {
    private VeinMiner() {}

    /**
     * CollectScheduler から呼ばれる想定のラッパ。
     *
     * @param world ServerWorld（破壊を行うワールド）
     * @param player 操作プレイヤー（ドロップハンドリング等に渡す）
     * @param startPos 開始座標
     * @param originalState 開始ブロックの BlockState（未使用だが呼び出し元が渡す）
     * @param playerUuid プレイヤー UUID（未使用だが呼び出し元に合わせる）
     * @param cap 最大破壊数
     * @param toolStack 使用中のツール（必要なら将来使う）
     * @return 破壊したブロック数
     */
    public static int mineAndSchedule(ServerWorld world,
                                      ServerPlayerEntity player,
                                      BlockPos startPos,
                                      BlockState originalState,
                                      UUID playerUuid,
                                      int cap,
                                      ItemStack toolStack) {
        // 現状は単純に breakVein を呼ぶラッパ実装（将来的にスケジューリング等を追加可能）
        return breakVein(world, startPos, player, cap);
    }

    /**
     * 開始座標から BFS で同一鉱石を cap まで破壊する（斜め含む）。
     *
     * @param world ServerWorld
     * @param startPos 開始座標
     * @param player 操作プレイヤー
     * @param cap 最大破壊数
     * @return 破壊したブロック数
     */
    public static int breakVein(ServerWorld world, BlockPos startPos, ServerPlayerEntity player, int cap) {
        int broken = 0;

        BlockState originalState = world.getBlockState(startPos);

        // 鉱石でないなら何もしない
        if (!OreUtils.isOre(originalState)) return 0;

        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        queue.add(startPos);
        visited.add(startPos);

        while (!queue.isEmpty() && broken < cap) {
            BlockPos pos = queue.pollFirst();
            BlockState state = world.getBlockState(pos);

            if (state.isAir()) continue;
            if (!OreUtils.isOre(state)) continue;

            try {
                boolean success = world.breakBlock(pos, true, player);
                if (success) broken++;
            } catch (Throwable t) {
                t.printStackTrace();
            }

            // 26近傍（斜め含む）
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos nb = pos.add(dx, dy, dz);
                        if (!visited.contains(nb)) {
                            visited.add(nb);
                            queue.add(nb);
                        }
                    }
                }
            }
        }

        if (broken > 0) {
            try {
                String blockId = originalState.getBlock().toString();
                player.sendMessage(net.minecraft.text.Text.of("Vein broken: " + broken + " " + blockId), false);
            } catch (Throwable ignored) {}
        }

        return broken;
    }
}
