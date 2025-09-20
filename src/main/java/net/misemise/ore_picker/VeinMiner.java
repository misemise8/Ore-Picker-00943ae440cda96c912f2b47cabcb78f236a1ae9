package net.misemise.ore_picker;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * VeinMiner:
 * - BFS で同種ブロックを探索し、limit を超えたら探索を停止する。
 * - 発見したブロックは破壊し（world.breakBlock）、CollectScheduler に
 *   allowVein=false でスケジュールする（派生収集のみ。再帰防止）。
 */
public final class VeinMiner {
    private VeinMiner() {}

    public static int mineAndSchedule(ServerWorld world, ServerPlayerEntity player, BlockPos startPos, BlockState originalState, UUID playerUuid, int limit) {
        if (world == null || player == null || originalState == null) return 0;
        Block target = originalState.getBlock();
        if (target == null) return 0;
        if (limit <= 0) return 0;

        Deque<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> toBreak = new ArrayList<>();

        // startPos 自身は既に破壊済みの前提なので隣接6方向から探索
        final int[][] DIRS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : DIRS) {
            BlockPos n = startPos.add(d[0], d[1], d[2]);
            q.add(n);
            visited.add(n);
        }

        while (!q.isEmpty() && toBreak.size() < limit) {
            BlockPos p = q.poll();
            if (p == null) continue;

            BlockState bs = world.getBlockState(p);
            if (bs == null) continue;
            if (bs.getBlock() != target) continue;

            // record for break
            toBreak.add(p);

            // enqueue neighbors (6-direction)
            for (int[] d : DIRS) {
                BlockPos nn = p.add(d[0], d[1], d[2]);
                if (visited.contains(nn)) continue;
                visited.add(nn);
                BlockState ns = world.getBlockState(nn);
                if (ns != null && ns.getBlock() == target) {
                    q.add(nn);
                }
            }
        }

        int broken = 0;
        for (BlockPos p : toBreak) {
            try {
                // 破壊（true = ドロップする。AutoCollectHandler で回収する）
                try { world.breakBlock(p, true, player); } catch (Throwable ignore) {}

                // 重要: ここでスケジュールするが allowVein=false として
                //       派生したブロックから再び VeinMiner を起こさないようにする
                try {
                    CollectScheduler.schedule(world, p, playerUuid, originalState, false);
                } catch (Throwable ignored) {}

                broken++;
            } catch (Throwable t) {
                t.printStackTrace();
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
