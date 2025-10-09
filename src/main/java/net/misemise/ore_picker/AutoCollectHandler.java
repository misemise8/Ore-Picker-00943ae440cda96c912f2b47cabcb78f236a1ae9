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
 * - Silk Touch を使っている場合は XP を与えない（toolStack を優先して判定）
 *
 * デバッグ: hasSilkTouch 判定と toolStack の NBT をログに出すようにしている。
 */
public class AutoCollectHandler {
    // 簡易ホワイトリスト（必要なら ConfigManager 経由に拡張可能）
    private static final Set<Block> XP_WHITELIST = makeWhitelist();

    private static Set<Block> makeWhitelist() {
        Set<Block> s = new HashSet<>();
        // バニラ通常鉱石
        try {
            s.add(Blocks.COAL_ORE);
            s.add(Blocks.DIAMOND_ORE);
            s.add(Blocks.LAPIS_ORE);
            s.add(Blocks.NETHER_QUARTZ_ORE);
            s.add(Blocks.REDSTONE_ORE);
            s.add(Blocks.EMERALD_ORE);
        } catch (Throwable e1) {}

        // deepslate 系（存在すれば追加）
        try {
            s.add(Blocks.DEEPSLATE_COAL_ORE);
            s.add(Blocks.DEEPSLATE_DIAMOND_ORE);
            s.add(Blocks.DEEPSLATE_LAPIS_ORE);
            s.add(Blocks.DEEPSLATE_REDSTONE_ORE);
            s.add(Blocks.DEEPSLATE_EMERALD_ORE);
        } catch (Throwable e2) {}

        // iron/gold intentionally excluded
        return s;
    }

    // 互換オーバーロード（旧4引数）
    public static void collectDrops(World world, PlayerEntity player, BlockPos pos, BlockState state) {
        ItemStack toolCopy = null;
        try {
            ItemStack main = player.getMainHandStack();
            if (main != null) toolCopy = main.copy();
        } catch (Throwable e3) {}
        collectDrops(world, player, pos, state, toolCopy);
    }

    /**
     * 新しいシグネチャ: toolStack を優先して SilkTouch 判定を行う
     */
    public static void collectDrops(World world, PlayerEntity player, BlockPos pos, BlockState state, ItemStack toolStack) {
        OrePickerLog.debug("collectDrops called pos=" + pos + " block=" + (state != null ? state.getBlock().toString() : "null"));

        if (!(world instanceof ServerWorld serverWorld)) return;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;

        // 自動回収は「鉱石」に対してのみ行う
        try {
            if (!net.misemise.ore_picker.OreUtils.isOre(state)) {
                return;
            }
        } catch (Throwable e4) {
            e4.printStackTrace();
            return;
        }

        // ---------- Silk Touch 判定（toolStack 優先） ----------
        boolean hasSilkTouch = false;
        try {
            if (toolStack != null) {
                try {
                    OrePickerLog.debug("[AutoCollectHandler][DEBUG] received toolStack = " + toolStack.toString());
                } catch (Throwable e5) {}
                if (robustHasSilkTouch(toolStack)) hasSilkTouch = true;
            }
            if (!hasSilkTouch) {
                try {
                    ItemStack main = serverPlayer.getMainHandStack();
                    if (main != null) {
                        try { OrePickerLog.debug("[AutoCollectHandler][DEBUG] player's mainHand = " + main.toString()); } catch (Throwable e6) {}
                        if (robustHasSilkTouch(main)) hasSilkTouch = true;
                    }
                } catch (Throwable e7) {}
            }
            if (!hasSilkTouch) {
                try {
                    ItemStack off = serverPlayer.getOffHandStack();
                    if (off != null) {
                        try { OrePickerLog.debug("[AutoCollectHandler][DEBUG] player's offHand = " + off.toString()); } catch (Throwable e8) {}
                        if (robustHasSilkTouch(off)) hasSilkTouch = true;
                    }
                } catch (Throwable e9) {}
            }
        } catch (Throwable e10) {}
        // --------------------------------------------------------

        OrePickerLog.debug("[AutoCollectHandler][DEBUG] hasSilkTouch=" + hasSilkTouch);

        boolean anyInserted = false;

        // 1) アイテム回収
        try {
            double radius = 1.5d;
            try {
                if (net.misemise.ore_picker.config.ConfigManager.INSTANCE != null) {
                    radius = net.misemise.ore_picker.config.ConfigManager.INSTANCE.pickupRadius;
                }
            } catch (Throwable e11) {}

            Box box = new Box(pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                    pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius);
            List<ItemEntity> items = serverWorld.getEntitiesByClass(ItemEntity.class, box, e -> true);
            OrePickerLog.debug("found " + items.size() + " item entities near " + pos);
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
                        } catch (Throwable e12) {
                            try { ie.discard(); } catch (Throwable e13) {}
                        }
                    }
                } catch (Throwable exItem) {
                    exItem.printStackTrace();
                }
            }
        } catch (Throwable e14) {
            e14.printStackTrace();
        }

        // 2) 回収音
        if (anyInserted) {
            try {
                serverWorld.playSound(null,
                        player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.2f, 1.0f);
            } catch (Throwable e15) {}
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
                        } catch (Throwable e16) {}
                    }
                } catch (Throwable e17) {}
            }
        } catch (Throwable e18) {
            xp = 0;
        }

        // Silk Touch: バニラに合わせて XP は与えない
        if (hasSilkTouch) {
            OrePickerLog.debug("[AutoCollectHandler][DEBUG] silkTouch detected -> xp set to 0");
            xp = 0;
        }

        if (xp > 0) {
            // 近傍のオーブを削除してポップを抑え（念のため）
            try {
                Box orbBox = new Box(pos.getX() - 2.5, pos.getY() - 2.5, pos.getZ() - 2.5,
                        pos.getX() + 2.5, pos.getY() + 2.5, pos.getZ() + 2.5);
                List<ExperienceOrbEntity> orbs = serverWorld.getEntitiesByClass(ExperienceOrbEntity.class, orbBox, e -> true);
                OrePickerLog.debug("found " + orbs.size() + " xp orbs near " + pos);
                for (ExperienceOrbEntity orb : orbs) {
                    try {
                        orb.remove(net.minecraft.entity.Entity.RemovalReason.DISCARDED);
                    } catch (Throwable e19) {
                        try { orb.discard(); } catch (Throwable e20) {}
                    }
                }
            } catch (Throwable e21) {}

            // プレイヤーへ直接付与
            try {
                OrePickerLog.debug("[AutoCollectHandler][DEBUG] awarding xp=" + xp + " to player=" + serverPlayer.getGameProfile().getName());
                serverPlayer.addExperience(xp);
                try {
                    serverWorld.playSound(null,
                            player.getX(), player.getY(), player.getZ(),
                            SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.3f, 1.0f);
                } catch (Throwable e22) {}
            } catch (Throwable t) {
                // addExperience が環境で無い等は無視
            }
        }

        // 4) 破壊トラッキング（VeinMineTrackerの仕様に合わせて引数を渡す）
        try {
            String blockIdString = state.getBlock().toString();
            VeinMineTracker.increment(serverPlayer.getUuid(), blockIdString);
        } catch (Throwable e23) {}
    }

    /**
     * Silk Touch 検出をより頑健に行うヘルパー。
     * - NBT・enchant list の取り方を複数試す
     * - 最終フォールバックで toString() に "silk_touch" 等が含まれていないかを確認
     */
    private static boolean robustHasSilkTouch(ItemStack st) {
        if (st == null) return false;
        try {
            // 1) try multiple "get nbt" method names
            Object nbt = null;
            String[] nbtNames = new String[] {"getNbt", "getTag", "toTag", "getOrCreateNbt"};
            for (String name : nbtNames) {
                try {
                    java.lang.reflect.Method m = st.getClass().getMethod(name);
                    if (m != null) {
                        nbt = m.invoke(st);
                        if (nbt != null) break;
                    }
                } catch (Throwable e24) {}
            }

            // 2) try to retrieve enchantment list from nbt or stack
            Object enchList = null;
            if (nbt != null) {
                try {
                    java.lang.reflect.Method getList = nbt.getClass().getMethod("getList", String.class, int.class);
                    enchList = getList.invoke(nbt, "Enchantments", 10);
                } catch (Throwable e25) {}
                if (enchList == null) {
                    try {
                        java.lang.reflect.Method getM = nbt.getClass().getMethod("get", String.class);
                        enchList = getM.invoke(nbt, "Enchantments");
                    } catch (Throwable e26) {}
                }
            }

            if (enchList == null) {
                // try direct methods on ItemStack that some mappings provide
                try {
                    java.lang.reflect.Method m = st.getClass().getMethod("getEnchantments");
                    enchList = m.invoke(st);
                } catch (Throwable e27) {}
                try {
                    if (enchList == null) {
                        java.lang.reflect.Method m2 = st.getClass().getMethod("getEnchantmentTags");
                        enchList = m2.invoke(st);
                    }
                } catch (Throwable e28) {}
            }

            if (enchList != null) {
                // try indexed access
                try {
                    java.lang.reflect.Method sizeM = enchList.getClass().getMethod("size");
                    int size = ((Number) sizeM.invoke(enchList)).intValue();
                    for (int i = 0; i < size; i++) {
                        try {
                            Object comp = null;
                            try {
                                java.lang.reflect.Method getCompound = enchList.getClass().getMethod("getCompound", int.class);
                                comp = getCompound.invoke(enchList, i);
                            } catch (Throwable e29) {
                                try {
                                    java.lang.reflect.Method getM = enchList.getClass().getMethod("get", int.class);
                                    comp = getM.invoke(enchList, i);
                                } catch (Throwable e30) {}
                            }
                            if (comp == null) continue;
                            String id = null;
                            try {
                                java.lang.reflect.Method gs = comp.getClass().getMethod("getString", String.class);
                                id = (String) gs.invoke(comp, "id");
                                if (id == null || id.isEmpty()) id = (String) gs.invoke(comp, "Id");
                            } catch (Throwable e31) {}
                            if (id != null && id.toLowerCase().contains("silk_touch")) return true;
                        } catch (Throwable e32) {}
                    }
                } catch (Throwable e33) {
                    // fallback to toString search
                    try {
                        String s = enchList.toString().toLowerCase();
                        if (s.contains("silk_touch") || s.contains("silktouch") || s.contains("silk-touch")) return true;
                    } catch (Throwable e34) {}
                }
            }

            // final fallback: stringify entire nbt or stack
            if (nbt != null) {
                try {
                    String s = nbt.toString().toLowerCase();
                    if (s.contains("silk_touch") || s.contains("silktouch")) return true;
                } catch (Throwable e35) {}
            }

            try {
                String s2 = st.toString().toLowerCase();
                if (s2.contains("silk_touch") || s2.contains("silktouch")) return true;
            } catch (Throwable e36) {}
        } catch (Throwable e37) {}
        return false;
    }

    private static int estimateXpForBlock(Block block, ServerWorld world) {
        Random rnd = new Random();
        String name = "";
        try { name = block.toString().toLowerCase(); } catch (Throwable e38) {}

        if (name.contains("iron") || name.contains("gold")) return 0;
        if (name.contains("coal")) return rnd.nextInt(3); // 0-2
        if (name.contains("diamond")) return 3 + rnd.nextInt(5); // 3-7
        if (name.contains("lapis")) return 2 + rnd.nextInt(4); // 2-5
        if (name.contains("quartz")) return 2 + rnd.nextInt(4); // 2-5
        if (name.contains("redstone")) return 1 + rnd.nextInt(5); // 1-5
        if (name.contains("emerald")) return 3 + rnd.nextInt(5); // 3-7

        try {
            Class<?> edClass = Class.forName("net.minecraft.block.ExperienceDroppingBlock");
            if (edClass.isAssignableFrom(block.getClass())) {
                return 1 + rnd.nextInt(3);
            }
        } catch (Throwable e39) {}

        return 0;
    }
}
