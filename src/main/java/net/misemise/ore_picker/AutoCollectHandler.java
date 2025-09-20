package net.misemise.ore_picker;

import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;

/**
 * AutoCollectHandler:
 * - 近接にスポーンした ItemEntity をプレイヤーのインベントリへ挿入（成功した分はエンティティ削除）
 * - 経験値はホワイトリストに基づいてのみプレイヤーへ直接付与（オーブは生成しない）
 */
public class AutoCollectHandler {
    // 簡易ホワイトリスト（必要なら ConfigManager 経由に拡張可能）
    private static final Set<Block> XP_WHITELIST = makeWhitelist();

    private static Set<Block> makeWhitelist() {
        Set<Block> s = new HashSet<>();
        s.add(Blocks.COAL_ORE);
        s.add(Blocks.DIAMOND_ORE);
        s.add(Blocks.LAPIS_ORE);
        s.add(Blocks.NETHER_QUARTZ_ORE);
        s.add(Blocks.REDSTONE_ORE);
        s.add(Blocks.EMERALD_ORE);
        // iron/gold intentionally excluded (ユーザの要望によりXP付与しない)
        return s;
    }

    public static void collectDrops(World world, PlayerEntity player, BlockPos pos, BlockState state) {
        System.out.println("[AutoCollectHandler] collectDrops called for player=" + (player != null ? player.getUuid() : "null") + " pos=" + pos + " block=" + (state != null ? state.getBlock().toString() : "null"));
        if (!(world instanceof ServerWorld serverWorld)) return;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;

        boolean anyInserted = false;

        // 1) アイテム回収
        try {
            Box box = new Box(pos.getX() - 1.5, pos.getY() - 1.5, pos.getZ() - 1.5,
                    pos.getX() + 1.5, pos.getY() + 1.5, pos.getZ() + 1.5);
            List<ItemEntity> items = serverWorld.getEntitiesByClass(ItemEntity.class, box, e -> true);
            System.out.println("[AutoCollectHandler] found " + items.size() + " item entities near " + pos);
            for (ItemEntity ie : items) {
                try {
                    ItemStack stack = ie.getStack();
                    if (stack == null || stack.isEmpty()) continue;

                    ItemStack copy = stack.copy();
                    boolean inserted = serverPlayer.getInventory().insertStack(copy);
                    if (inserted) {
                        anyInserted = true;
                        try {
                            ie.remove(net.minecraft.entity.Entity.RemovalReason.DISCARDED);
                        } catch (Throwable ex) {
                            try { ie.discard(); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable exItem) {
                    exItem.printStackTrace();
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // 2) 回収音
        if (anyInserted) {
            try {
                serverWorld.playSound(null,
                        player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.2f, 1.0f);
            } catch (Throwable ignored) {}
        }

        // 3) XP（ホワイトリスト判定）
        int xp = 0;
        try {
            Block blk = state.getBlock();
            if (blk != null && XP_WHITELIST.contains(blk)) {
                xp = estimateXpForBlock(blk, serverWorld);
            } else {
                xp = 0;
            }
        } catch (Throwable ignored) {
            xp = 0;
        }

        if (xp > 0) {
            // 近傍のオーブを削除してポップを抑え（念のため）
            try {
                Box orbBox = new Box(pos.getX() - 2.5, pos.getY() - 2.5, pos.getZ() - 2.5,
                        pos.getX() + 2.5, pos.getY() + 2.5, pos.getZ() + 2.5);
                List<ExperienceOrbEntity> orbs = serverWorld.getEntitiesByClass(ExperienceOrbEntity.class, orbBox, e -> true);
                System.out.println("[AutoCollectHandler] found " + orbs.size() + " xp orbs near " + pos);
                for (ExperienceOrbEntity orb : orbs) {
                    try {
                        orb.remove(net.minecraft.entity.Entity.RemovalReason.DISCARDED);
                    } catch (Throwable ex) {
                        try { orb.discard(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            // プレイヤーへ直接付与
            try {
                serverPlayer.addExperience(xp);
                try {
                    serverWorld.playSound(null,
                            player.getX(), player.getY(), player.getZ(),
                            SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.3f, 1.0f);
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                // addExperience が環境で無い等は無視
            }
        }

        // 4) 破壊トラッキング（VeinMineTrackerの仕様に合わせて引数を渡す）
        try {
            String blockIdString = state.getBlock().toString();
            // VeinMineTracker.increment(UUID, String) のようなシグネチャを仮定
            VeinMineTracker.increment(serverPlayer.getUuid(), blockIdString);
        } catch (Throwable ignored) {}
    }

    private static int estimateXpForBlock(Block block, ServerWorld world) {
        Random rnd = new Random();
        if (block == Blocks.COAL_ORE) return rnd.nextInt(3);
        if (block == Blocks.DIAMOND_ORE) return 3 + rnd.nextInt(5);
        if (block == Blocks.LAPIS_ORE) return 2 + rnd.nextInt(4);
        if (block == Blocks.NETHER_QUARTZ_ORE) return 2 + rnd.nextInt(4);
        if (block == Blocks.REDSTONE_ORE) return 1 + rnd.nextInt(3);
        if (block == Blocks.EMERALD_ORE) return 2 + rnd.nextInt(3);
        return 0;
    }
}
