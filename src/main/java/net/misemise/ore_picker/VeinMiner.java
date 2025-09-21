package net.misemise.ore_picker;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;

import java.lang.reflect.Method;
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
 * - 各ブロック破壊時に toolStack を利用して drop を生成する（可能な場合は Block.dropStacks を呼ぶ）。
 *
 * 変更点:
 * - 第7引数で toolStack を受け取り、drop 計算に渡します（Fortune/SilkTouch の正確な適用のため）。
 */
public final class VeinMiner {
    private VeinMiner() {}

    public static int mineAndSchedule(ServerWorld world, ServerPlayerEntity player, BlockPos startPos, BlockState originalState, UUID playerUuid, int limit, ItemStack toolStack) {
        if (world == null || player == null || originalState == null) return 0;
        Block target = originalState.getBlock();
        if (target == null) return 0;

        // limit は「開始ブロックを含む合計上限」として扱う
        int neighborLimit = Math.max(0, limit - 1);

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

        int broken = 0;
        for (BlockPos p : toBreak) {
            try {
                BlockState currentState = world.getBlockState(p);
                if (currentState == null) continue;

                boolean dropped = false;

                // 1) try to call Block.dropStacks(BlockState, World, BlockPos, BlockEntity, Entity, ItemStack)
                try {
                    Class<?> blockClass = Class.forName("net.minecraft.block.Block");
                    Class<?> blockStateClass = Class.forName("net.minecraft.block.BlockState");
                    Class<?> worldClass = Class.forName("net.minecraft.world.World");
                    Class<?> blockPosClass = Class.forName("net.minecraft.util.math.BlockPos");
                    Class<?> blockEntityClass;
                    try {
                        blockEntityClass = Class.forName("net.minecraft.block.entity.BlockEntity");
                    } catch (ClassNotFoundException e) {
                        blockEntityClass = null;
                    }
                    Class<?> entityClass = Class.forName("net.minecraft.entity.Entity");
                    Class<?> itemStackClass = Class.forName("net.minecraft.item.ItemStack");

                    // try common static signature
                    Method dropStacksMethod = null;
                    try {
                        dropStacksMethod = blockClass.getMethod("dropStacks", blockStateClass, worldClass, blockPosClass, blockEntityClass, entityClass, itemStackClass);
                    } catch (Throwable ex) {
                        // method not found with this exact signature - try overloaded variants via iteration
                        for (Method m : blockClass.getMethods()) {
                            if (!m.getName().equals("dropStacks")) continue;
                            Class<?>[] params = m.getParameterTypes();
                            // prefer methods that accept at least (BlockState, World, BlockPos)
                            if (params.length >= 3) {
                                if (params[0].getName().contains("BlockState") || params[0].getName().toLowerCase().contains("blockstate")) {
                                    dropStacksMethod = m;
                                    break;
                                }
                            }
                        }
                    }

                    if (dropStacksMethod != null) {
                        try {
                            // prepare arguments in common order: (BlockState, World, BlockPos, BlockEntity, Entity, ItemStack)
                            Object be = null;
                            Object[] args = null;
                            Class<?>[] params = dropStacksMethod.getParameterTypes();
                            args = new Object[params.length];
                            for (int i = 0; i < params.length; i++) {
                                String pn = params[i].getName().toLowerCase();
                                if (pn.contains("blockstate")) args[i] = currentState;
                                else if (pn.contains("world")) args[i] = (Object) world;
                                else if (pn.contains("blockpos") || pn.contains("blockpos")) args[i] = p;
                                else if (pn.contains("blockentity")) args[i] = null;
                                else if (pn.contains("entity")) args[i] = player;
                                else if (pn.contains("itemstack") || pn.contains("itemstack")) args[i] = toolStack;
                                else args[i] = null;
                            }

                            // static method: invoke null; instance method: invoke currentState.getBlock()
                            if ((dropStacksMethod.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                                dropStacksMethod.invoke(null, args);
                            } else {
                                Object blockObj = currentState.getBlock();
                                dropStacksMethod.invoke(blockObj, args);
                            }

                            dropped = true;
                        } catch (Throwable exInvoke) {
                            // fall through to fallback
                            dropped = false;
                        }
                    }
                } catch (Throwable reflectionEx) {
                    // ignore and fallback
                    dropped = false;
                }

                // 2) fallback: try to break via world.breakBlock with temporary swap (older method)
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
                        try {
                            world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
                        } catch (Throwable ignored) {}
                    }

                    if (swapped && selectedSlot != null) {
                        try {
                            player.getInventory().setStack(selectedSlot, originalMain);
                        } catch (Throwable ignored) {}
                    }
                } else {
                    // if we used dropStacks, still remove the block to avoid duplicates
                    try {
                        world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
                    } catch (Throwable ignored) {}
                }

                // schedule collect for the broken block to pick up its drops (allowVein=false to avoid recursion)
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
