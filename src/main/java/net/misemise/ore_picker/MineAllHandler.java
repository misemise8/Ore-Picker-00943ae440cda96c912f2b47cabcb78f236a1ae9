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
 * - 以前は独自の breakConnectedOres を持っていたが、そこではドロップ生成や除去が不完全だったため
 *   この実装では VeinMiner へ委譲するように変更した（単純化）。
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

            // VeinMiner に委譲して一括破壊（VeinMiner は実際にブロックを壊してドロップ処理を行う）
            try {
                int limit = 64;
                try {
                    if (net.misemise.ore_picker.config.ConfigManager.INSTANCE != null) {
                        limit = net.misemise.ore_picker.config.ConfigManager.INSTANCE.maxVeinSize;
                        int cap = net.misemise.ore_picker.config.ConfigManager.INSTANCE.maxVeinSizeCap;
                        if (cap > 0) limit = Math.min(limit, cap);
                    }
                } catch (Throwable ignored) {}

                int broken = VeinMiner.mineAndSchedule(serverWorld, serverPlayer, pos, state, serverPlayer.getUuid(), limit, player.getMainHandStack());
                try {
                    System.out.println("[MineAllHandler] VeinMiner broken count: " + broken);
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                t.printStackTrace();
            }

            return false; // 元の破壊処理はキャンセル
        }

        return true;
    }
}
