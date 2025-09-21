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
 * - Silk Touch を使っている場合は XP を与えない（vanilla 準拠）
 */
public class AutoCollectHandler {
    // 簡易ホワイトリスト（必要なら ConfigManager 経由に拡張可能）
    private static final Set<Block> XP_WHITELIST = makeWhitelist();

    private static Set<Block> makeWhitelist() {
        Set<Block> s = new HashSet<>();
        // バニラ通常鉱石
        s.add(Blocks.COAL_ORE);
        s.add(Blocks.DIAMOND_ORE);
        s.add(Blocks.LAPIS_ORE);
        s.add(Blocks.NETHER_QUARTZ_ORE);
        s.add(Blocks.REDSTONE_ORE);
        s.add(Blocks.EMERALD_ORE);

        // deepslate 系（1.17以降に追加された深層版。1.21.4 環境では定義されています）
        try {
            s.add(Blocks.DEEPSLATE_COAL_ORE);
            s.add(Blocks.DEEPSLATE_DIAMOND_ORE);
            s.add(Blocks.DEEPSLATE_LAPIS_ORE);
            s.add(Blocks.DEEPSLATE_REDSTONE_ORE);
            s.add(Blocks.DEEPSLATE_EMERALD_ORE);
            // nether quartz に deepslate 版はないため追加していない
        } catch (Throwable ignored) {
            // 万が一古い環境で定数が無くても実行時に安全フォールバック
        }

        // iron/gold intentionally excluded (ユーザの要望によりXP付与しない)
        return s;
    }

    public static void collectDrops(World world, PlayerEntity player, BlockPos pos, BlockState state) {
        System.out.println("[AutoCollectHandler] collectDrops called pos=" + pos + " block=" + (state != null ? state.getBlock().toString() : "null"));

        if (!(world instanceof ServerWorld serverWorld)) return;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;

        // 追加: 自動回収は「鉱石」に対してのみ行う（OreUtilsを導入済み）
        try {
            if (!net.misemise.ore_picker.OreUtils.isOre(state)) {
                return;
            }
        } catch (Throwable t) {
            t.printStackTrace();
            return;
        }

        // ---------- Silk Touch check (reflection/NBT-based, mapping-safe) ----------
        boolean hasSilkTouch = false;
        try {
            try {
                ItemStack main = serverPlayer.getMainHandStack();
                if (main != null && stackHasSilkTouchViaNbt(main)) hasSilkTouch = true;
            } catch (Throwable ignored) {}
            if (!hasSilkTouch) {
                try {
                    ItemStack off = serverPlayer.getOffHandStack();
                    if (off != null && stackHasSilkTouchViaNbt(off)) hasSilkTouch = true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        // -------------------------------------------------------------------------

        boolean anyInserted = false;

        // 1) アイテム回収
        try {
            double radius = 1.5d;
            // pickupRadius を config に合わせたい場合は ConfigManager.INSTANCE.pickupRadius を参照
            try {
                if (net.misemise.ore_picker.config.ConfigManager.INSTANCE != null) {
                    radius = net.misemise.ore_picker.config.ConfigManager.INSTANCE.pickupRadius;
                }
            } catch (Throwable ignored) {}

            Box box = new Box(pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                    pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius);
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

        // 3) XP（ホワイトリスト判定 + フォールバック）
        int xp = 0;
        try {
            Block blk = state.getBlock();
            if (blk != null && XP_WHITELIST.contains(blk)) {
                xp = estimateXpForBlock(blk, serverWorld);
            } else {
                try {
                    if (net.misemise.ore_picker.OreUtils.isOre(state)) {
                        try {
                            Class<?> edClass = Class.forName("net.minecraft.block.ExperienceDroppingBlock");
                            if (edClass.isAssignableFrom(blk.getClass())) {
                                xp = estimateXpForBlock(blk, serverWorld);
                            }
                        } catch (ClassNotFoundException cnfe) {
                            // フォールバックで0
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            xp = 0;
        }

        // Silk Touch: バニラに合わせて XP は与えない
        if (hasSilkTouch) {
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
            VeinMineTracker.increment(serverPlayer.getUuid(), blockIdString);
        } catch (Throwable ignored) {}
    }

    /**
     * リフレクションで ItemStack の NBT を読み、Enchantments リストに silk_touch を含むかを調べる。
     * - 互換性確保のため getNbt / getTag 等の異なるメソッド名を順に試す。
     * - 見つからなければ false を返す（Silk Touch を検出できない限り XP を与える動作になる）。
     */
    private static boolean stackHasSilkTouchViaNbt(ItemStack st) {
        if (st == null) return false;
        try {
            // try multiple possible method names for retrieving NBT: getNbt, getTag, toTag, getOrCreateNbt
            Object nbt = null;
            String[] candidates = new String[] {"getNbt", "getTag", "toTag", "getOrCreateNbt"};
            for (String mname : candidates) {
                try {
                    java.lang.reflect.Method m = st.getClass().getMethod(mname);
                    if (m != null) {
                        nbt = m.invoke(st);
                        if (nbt != null) break;
                    }
                } catch (NoSuchMethodException nsme) {
                    // try next
                }
            }
            if (nbt == null) return false;

            // try to get the enchantments list: getList("Enchantments", 10)
            java.lang.reflect.Method getListMethod = null;
            try {
                getListMethod = nbt.getClass().getMethod("getList", String.class, int.class);
            } catch (NoSuchMethodException nsme) {
                // maybe method name differs; try "get" then check type
                try {
                    java.lang.reflect.Method getMethod = nbt.getClass().getMethod("get", String.class);
                    Object maybe = getMethod.invoke(nbt, "Enchantments");
                    if (maybe == null) return false;
                    // if maybe has size() / getCompound(int) we'll treat it like a list
                    // set listObj to maybe
                    Object listObj = maybe;
                    int size = 0;
                    try {
                        java.lang.reflect.Method sizeM = listObj.getClass().getMethod("size");
                        size = ((Number)sizeM.invoke(listObj)).intValue();
                    } catch (NoSuchMethodException e) {
                        return false;
                    }
                    for (int i = 0; i < size; i++) {
                        try {
                            java.lang.reflect.Method getCompound = listObj.getClass().getMethod("getCompound", int.class);
                            Object comp = getCompound.invoke(listObj, i);
                            if (comp == null) continue;
                            java.lang.reflect.Method getString = comp.getClass().getMethod("getString", String.class);
                            String id = (String) getString.invoke(comp, "id");
                            if (id != null && id.toLowerCase().contains("silk_touch")) return true;
                        } catch (NoSuchMethodException ex2) {
                            // try generic get(int)
                            try {
                                java.lang.reflect.Method getM = listObj.getClass().getMethod("get", int.class);
                                Object comp = getM.invoke(listObj, i);
                                if (comp == null) continue;
                                java.lang.reflect.Method getString = comp.getClass().getMethod("getString", String.class);
                                String id = (String) getString.invoke(comp, "id");
                                if (id != null && id.toLowerCase().contains("silk_touch")) return true;
                            } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    }
                    return false;
                } catch (NoSuchMethodException | IllegalAccessException ex) {
                    return false;
                }
            }

            // if getListMethod exists
            Object enchList = getListMethod.invoke(nbt, "Enchantments", 10);
            if (enchList == null) return false;
            int size = 0;
            try {
                java.lang.reflect.Method sizeM = enchList.getClass().getMethod("size");
                size = ((Number)sizeM.invoke(enchList)).intValue();
            } catch (NoSuchMethodException ns) {
                return false;
            }
            for (int i = 0; i < size; i++) {
                try {
                    java.lang.reflect.Method getCompound = enchList.getClass().getMethod("getCompound", int.class);
                    Object comp = getCompound.invoke(enchList, i);
                    if (comp == null) continue;
                    java.lang.reflect.Method getString = comp.getClass().getMethod("getString", String.class);
                    String id = (String) getString.invoke(comp, "id");
                    if (id != null && id.toLowerCase().contains("silk_touch")) return true;
                } catch (NoSuchMethodException ex2) {
                    try {
                        java.lang.reflect.Method getM = enchList.getClass().getMethod("get", int.class);
                        Object comp = getM.invoke(enchList, i);
                        if (comp == null) continue;
                        java.lang.reflect.Method getString = comp.getClass().getMethod("getString", String.class);
                        String id = (String) getString.invoke(comp, "id");
                        if (id != null && id.toLowerCase().contains("silk_touch")) return true;
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }

        } catch (Throwable ignored) {
            // anything goes wrong -> assume no silk touch
            return false;
        }
        return false;
    }

    private static int estimateXpForBlock(Block block, ServerWorld world) {
        Random rnd = new Random();

        // 名前ベース判定（deepslate 版にも対応）
        String name = "";
        try {
            name = block.toString().toLowerCase();
        } catch (Throwable ignored) {}

        // 明示的に XP を与えたくないもの（iron/gold は除外）
        if (name.contains("iron") || name.contains("gold")) {
            return 0;
        }

        // バニラの範囲に合わせる
        if (name.contains("coal")) {
            // 0 - 2
            return rnd.nextInt(3);
        }
        if (name.contains("diamond")) {
            // 3 - 7
            return 3 + rnd.nextInt(5);
        }
        if (name.contains("lapis")) {
            // 2 - 5
            return 2 + rnd.nextInt(4);
        }
        if (name.contains("quartz")) {
            // Nether quartz: 2 - 5
            return 2 + rnd.nextInt(4);
        }
        if (name.contains("redstone")) {
            // 1 - 5 (vanilla range)
            return 1 + rnd.nextInt(5);
        }
        if (name.contains("emerald")) {
            // 3 - 7 (vanilla range)
            return 3 + rnd.nextInt(5);
        }

        // フォールバック: ExperienceDroppingBlock を継承している mod 鉱石は
        // リフレクションで確認して、小さめのデフォルトXPを与える（1-3）
        try {
            Class<?> edClass = Class.forName("net.minecraft.block.ExperienceDroppingBlock");
            if (edClass.isAssignableFrom(block.getClass())) {
                return 1 + rnd.nextInt(3); // 1 - 3
            }
        } catch (ClassNotFoundException cnfe) {
            // クラスが無ければ何もしない（安全フォールバック）
        } catch (Throwable ignored) {}

        return 0;
    }
}
