package net.misemise.ore_picker;

import net.misemise.ore_picker.config.ConfigManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.text.Text;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.util.math.BlockPos;

/**
 * VeinMiner:
 * - BFS で同種ブロックを探索し、limit を超えたら探索を停止する。
 * - 各ブロック破壊時に toolStack を利用して drop を生成する（可能な場合は Block.dropStacks を呼ぶ）。
 *
 * 変更:
 * - メッセージ表示は「開始ブロックを含めた合計」で出す（ユーザにはこちらが自然）。
 * - mineAndSchedule の戻り値も「開始ブロックを含めた合計」を返すようにした。
 */
public final class VeinMiner {
    private VeinMiner() {}

    /**
     * mineAndSchedule:
     *  - limit: 開始ブロックを含む合計上限
     *  - toolStack: スケジュール時にキャプチャしたツール（null 可）
     *
     * 返り値: totalBroken = 1 (開始ブロック) + 隣接で破壊した数
     */
    public static int mineAndSchedule(ServerWorld world, ServerPlayerEntity player, BlockPos startPos, BlockState originalState, UUID playerUuid, int limit, ItemStack toolStack) {
        if (world == null || player == null || originalState == null) return 0;
        Block target = originalState.getBlock();
        if (target == null) return 0;

        // neighborLimit は「開始ブロックを除いた」上限
        int neighborLimit = Math.max(0, limit - 1);

        Deque<BlockPos> q = new ArrayDeque<>();
        HashSet<BlockPos> visited = new HashSet<>();
        List<BlockPos> toBreak = new ArrayList<>();

        // 6方向探索
        final int[][] DIRS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : DIRS) {
            BlockPos n = startPos.add(d[0], d[1], d[2]);
            q.add(n);
            visited.add(n);
        }

        while (!q.isEmpty() && toBreak.size() < neighborLimit) {
            BlockPos p = q.poll();
            if (p == null) continue;

            BlockState bs = world.getBlockState(p);
            if (bs == null) continue;
            if (bs.getBlock() != target) continue;

            toBreak.add(p);
            if (toBreak.size() >= neighborLimit) break;

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

        int neighborBroken = 0;
        for (BlockPos p : toBreak) {
            try {
                BlockState currentState = world.getBlockState(p);
                if (currentState == null) continue;

                boolean dropped = false;

                // 1) try to call Block.dropStacks(...) via reflection passing toolStack where possible
                try {
                    Class<?> blockClass = Class.forName("net.minecraft.block.Block");
                    Class<?> blockStateClass = Class.forName("net.minecraft.block.BlockState");
                    Class<?> worldClass = Class.forName("net.minecraft.world.World");
                    Class<?> blockPosClass = Class.forName("net.minecraft.util.math.BlockPos");
                    Class<?> blockEntityClass = null;
                    try { blockEntityClass = Class.forName("net.minecraft.block.entity.BlockEntity"); } catch (Throwable ignored) {}
                    Class<?> entityClass = Class.forName("net.minecraft.entity.Entity");
                    Class<?> itemStackClass = Class.forName("net.minecraft.item.ItemStack");

                    Method dropStacksMethod = null;
                    try {
                        dropStacksMethod = blockClass.getMethod("dropStacks", blockStateClass, worldClass, blockPosClass, blockEntityClass, entityClass, itemStackClass);
                    } catch (Throwable ex) {
                        for (Method m : blockClass.getMethods()) {
                            if (!m.getName().equals("dropStacks")) continue;
                            Class<?>[] params = m.getParameterTypes();
                            if (params.length >= 3) {
                                if (params[0].getName().toLowerCase().contains("blockstate")) {
                                    dropStacksMethod = m;
                                    break;
                                }
                            }
                        }
                    }

                    if (dropStacksMethod != null) {
                        try {
                            Object be = null;
                            Class<?>[] params = dropStacksMethod.getParameterTypes();
                            Object[] args = new Object[params.length];
                            for (int i = 0; i < params.length; i++) {
                                String pn = params[i].getName().toLowerCase();
                                if (pn.contains("blockstate")) args[i] = currentState;
                                else if (pn.contains("world")) args[i] = (Object) world;
                                else if (pn.contains("blockpos")) args[i] = p;
                                else if (pn.contains("blockentity")) args[i] = null;
                                else if (pn.contains("entity")) args[i] = player;
                                else if (pn.contains("itemstack")) args[i] = toolStack;
                                else args[i] = null;
                            }

                            if ((dropStacksMethod.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                                dropStacksMethod.invoke(null, args);
                            } else {
                                Object blockObj = currentState.getBlock();
                                dropStacksMethod.invoke(blockObj, args);
                            }
                            dropped = true;
                        } catch (Throwable exInvoke) {
                            dropped = false;
                        }
                    }
                } catch (Throwable reflectionEx) {
                    dropped = false;
                }

                // 2) fallback: temporarily swap player's main hand and call world.breakBlock
                if (!dropped) {
                    ItemStack originalMain = null;
                    Integer selectedSlot = null;
                    boolean swapped = false;
                    try {
                        if (toolStack != null) {
                            try {
                                selectedSlot = player.getInventory().selectedSlot;
                            } catch (Throwable t) {
                                try {
                                    java.lang.reflect.Field f = player.getInventory().getClass().getField("selectedSlot");
                                    selectedSlot = (Integer) f.get(player.getInventory());
                                } catch (Throwable ignored) {
                                    selectedSlot = null;
                                }
                            }

                            if (selectedSlot != null) {
                                try {
                                    originalMain = player.getInventory().getStack(selectedSlot);
                                    player.getInventory().setStack(selectedSlot, toolStack.copy());
                                    swapped = true;
                                } catch (Throwable ignored) {
                                    swapped = false;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        swapped = false;
                    }

                    try {
                        world.breakBlock(p, true, player);
                    } catch (Throwable ex2) {
                        try { world.setBlockState(p, Blocks.AIR.getDefaultState(), 3); } catch (Throwable ignored) {}
                    }

                    if (swapped && selectedSlot != null) {
                        try { player.getInventory().setStack(selectedSlot, originalMain); } catch (Throwable ignored) {}
                    }
                } else {
                    // remove the block to avoid duplicates
                    try { world.setBlockState(p, Blocks.AIR.getDefaultState(), 3); } catch (Throwable ignored) {}
                }

                // schedule collect for the broken block to pick up its drops (pass toolStack)
                try {
                    CollectScheduler.schedule(world, p, playerUuid, originalState, false, toolStack);
                } catch (Throwable ignored) {}

                neighborBroken++;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        // totalBroken は 「開始ブロック(1) + 隣接で壊した数」
        int totalBroken = 1 + neighborBroken;

        if (neighborBroken > 0 || totalBroken > 0) {
            try {
                String blockId = originalState.getBlock().toString();

                // 即時チャット送信（設定で有効な場合のみ）
                try {
                    if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.logToChat) {
                        try {
                            player.sendMessage(Text.translatable("chat.ore_picker.vein_broken", totalBroken, Text.literal(blockId)), false);
                        } catch (Throwable t) {
                            // フォールバック
                            player.sendMessage(Text.literal("[OrePicker] Broke " + totalBroken + " " + blockId), false);
                        }
                    }
                } catch (Throwable ignored) {}

                // コンソールログは常に出す
                try {
                    System.out.println("[VeinMiner] Vein broken: " + totalBroken + " " + blockId);
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }

        // 戻り値も合計に揃える（呼び出し側ログとの不一致を避ける）
        return totalBroken;
    }
}
