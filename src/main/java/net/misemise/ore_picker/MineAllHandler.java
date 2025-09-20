package net.misemise.ore_picker;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

/**
 * MineAllHandler
 * - BEFORE イベントから呼ばれる onBlockBreak(...) を提供
 * - breakConnectedOres は実際の一括破壊ループを行い、各ブロックで AutoCollectHandler.collectDrops(...) を呼ぶ
 */
public class MineAllHandler {
    private static final Set<Block> MINABLE_BLOCKS = Set.of(
            Blocks.COAL_ORE,
            Blocks.IRON_ORE,
            Blocks.GOLD_ORE,
            Blocks.DIAMOND_ORE,
            Blocks.COPPER_ORE,
            Blocks.LAPIS_ORE,
            Blocks.REDSTONE_ORE,
            Blocks.EMERALD_ORE
    );

    // 安全上の上限
    private static final int MAX_BLOCKS = 256;

    /**
     * BEFORE イベント向け。true を返すと通常の破壊を継続、false を返すとキャンセルする。
     */
    public static boolean onBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity _blockEntity) {
        if (!(world instanceof ServerWorld serverWorld) || !(player instanceof ServerPlayerEntity serverPlayer)) {
            return true; // クライアント側または非サーバプレイヤーなら通常処理
        }

        Block brokenBlock = state.getBlock();
        if (MINABLE_BLOCKS.contains(brokenBlock)) {
            // 任意のプレイヤー通知（デバッグ）
            try {
                serverPlayer.sendMessage(Text.of("鉱石を壊した！: " + brokenBlock.getName().getString()), false);
            } catch (Throwable ignored) {}

            // 実際の一括破壊処理を実行（これで vanilla の破壊処理はキャンセルする）
            breakConnectedOres(serverWorld, pos, serverPlayer);
            return false; // 元の破壊処理はキャンセル
        }

        return true;
    }

    /**
     * BFS で同じブロック種を探索して壊す（最大 MAX_BLOCKS 個まで）。
     * 各ブロックについて AutoCollectHandler.collectDrops を呼んで処理を委譲する。
     */
    public static void breakConnectedOres(ServerWorld world, BlockPos startPos, ServerPlayerEntity player) {
        BlockState startState = world.getBlockState(startPos);
        Block startBlock = startState.getBlock();

        if (!MINABLE_BLOCKS.contains(startBlock)) return;

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(startPos);
        visited.add(startPos);

        int brokenCount = 0;

        while (!queue.isEmpty() && brokenCount < MAX_BLOCKS) {
            BlockPos pos = queue.poll();
            BlockState state = world.getBlockState(pos);

            if (state.getBlock() == startBlock) {
                // ドロップ回収 + XP 付与 + ブロック削除を AutoCollectHandler に委譲
                try {
                    AutoCollectHandler.collectDrops(world, player, pos, state);
                } catch (Throwable ignored) {}

                brokenCount++;

                // 近傍 3x3x3 を探索
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos neighbor = pos.add(dx, dy, dz);
                            if (!visited.contains(neighbor)) {
                                visited.add(neighbor);
                                queue.add(neighbor);
                            }
                        }
                    }
                }
            }
        }
    }
}
