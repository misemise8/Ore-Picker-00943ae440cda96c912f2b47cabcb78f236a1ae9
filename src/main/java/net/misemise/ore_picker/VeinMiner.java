package net.misemise.ore_picker;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;

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
 * - BFS で同種ブロックを探索（26近傍：斜め含む）
 * - 各ブロック破壊時に toolStack を利用して drop を生成する（可能な場合は Block.dropStacks を呼ぶ via reflection）
 * - dropStacks で生成された ItemEntity を見つけ、pickupDelay を 0 にして回収しやすくする
 * - dropStacks が呼べない場合はプレイヤーのメインハンドを一時差し替えて world.breakBlock を呼ぶ（フォールバック）
 * - 各破壊ブロックは CollectScheduler に再スケジュールして AutoCollect で拾わせる（toolStack を渡す）
 */
public final class VeinMiner {
    private VeinMiner() {}

    /**
     * mineAndSchedule:
     *  - limit: 開始ブロックを含む合計上限
     *  - toolStack: スケジュール時にキャプチャしたツール（null 可）
     */
    public static int mineAndSchedule(ServerWorld world, ServerPlayerEntity player, BlockPos startPos, BlockState originalState, UUID playerUuid, int limit, ItemStack toolStack) {
        if (world == null || player == null || originalState == null) return 0;
        Block target = originalState.getBlock();
        if (target == null) return 0;

        if (limit <= 0) return 0;

        int neighborLimit = Math.max(0, limit - 1);

        Deque<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> toBreak = new ArrayList<>();

        // initialize queue with all 26 neighbors (dx,dy,dz = -1..1 excluding 0,0,0)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos n = startPos.add(dx, dy, dz);
                    q.add(n);
                    visited.add(n);
                }
            }
        }

        // BFS: collect up to neighborLimit matching blocks
        while (!q.isEmpty() && toBreak.size() < neighborLimit) {
            BlockPos p = q.poll();
            if (p == null) continue;

            try {
                BlockState bs = world.getBlockState(p);
                if (bs == null) continue;
                if (bs.getBlock() != target) continue;

                toBreak.add(p);
                if (toBreak.size() >= neighborLimit) break;

                // enqueue 26 neighbors of p
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos nn = p.add(dx, dy, dz);
                            if (visited.contains(nn)) continue;
                            visited.add(nn);
                            q.add(nn);
                        }
                    }
                }
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }

        int broken = 0;
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

                // If dropped via dropStacks, try to find spawned ItemEntity's and make them immediately collectible
                if (dropped) {
                    try {
                        makeNearbyDropsPickupable(world, p);
                    } catch (Throwable ignored) {}
                }

                // 2) fallback: temporarily swap player's main hand and call world.breakBlock
                if (!dropped) {
                    ItemStack originalMain = null;
                    Integer selectedSlot = null;
                    boolean swapped = false;
                    try {
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

                        if (selectedSlot != null && toolStack != null) {
                            try {
                                originalMain = player.getInventory().getStack(selectedSlot);
                                player.getInventory().setStack(selectedSlot, toolStack.copy());
                                swapped = true;
                            } catch (Throwable ignored) {
                                swapped = false;
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
                    // remove the block to avoid duplicates if needed
                    try { world.setBlockState(p, Blocks.AIR.getDefaultState(), 3); } catch (Throwable ignored) {}
                }

                // schedule collect for the broken block to pick up its drops (pass toolStack)
                try {
                    CollectScheduler.schedule(world, p, playerUuid, originalState, false, toolStack);
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

    /**
     * dropStacks 等で spawn された ItemEntity を見つけて pickupDelay=0、velocity=0 にする（即回収可能にする）
     */
    private static void makeNearbyDropsPickupable(ServerWorld world, BlockPos pos) {
        try {
            double r = 1.0d;
            List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class,
                    new net.minecraft.util.math.Box(pos.getX() - r, pos.getY() - r, pos.getZ() - r,
                            pos.getX() + r, pos.getY() + r, pos.getZ() + r),
                    e -> true);
            for (ItemEntity ie : items) {
                try {
                    // set pickup delay to 0 if method available
                    try {
                        Method m = ie.getClass().getMethod("setPickupDelay", int.class);
                        m.invoke(ie, 0);
                    } catch (Throwable ex) {
                        try {
                            java.lang.reflect.Field f = ie.getClass().getField("pickupDelay");
                            f.setInt(ie, 0);
                        } catch (Throwable ignored) {}
                    }
                    // zero velocity
                    try {
                        Method setVelocity = ie.getClass().getMethod("setVelocity", double.class, double.class, double.class);
                        setVelocity.invoke(ie, 0.0d, 0.0d, 0.0d);
                    } catch (Throwable ex) {
                        try {
                            Method setVelocityVec = ie.getClass().getMethod("setVelocity", net.minecraft.util.math.Vec3d.class);
                            setVelocityVec.invoke(ie, new net.minecraft.util.math.Vec3d(0.0d, 0.0d, 0.0d));
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ex) {
                    // ignore per-item failures
                }
            }
        } catch (Throwable ignored) {}
    }
}
