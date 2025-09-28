package net.misemise.ore_picker;

import net.misemise.ore_picker.config.ConfigManager;
import net.misemise.ore_picker.network.NetworkUtil;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.PacketByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.Identifier;

/**
 * VeinMiner:
 * - BFS で同種ブロックを探索し、limit を超えたら探索を停止する。
 * - 各ブロック破壊時に toolStack を利用して drop を生成する（可能な場合は Block.dropStacks を呼ぶ）。
 * - 完了時に S2C で broken count をプレイヤーへ送る（Identifier: orepicker:vein_count）。
 *
 * 注意: パッケージ / mappings の差に強くするため、S2C packet の生成は直接型参照せずリフレクションで行います。
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

        int neighborLimit = Math.max(0, limit - 1);

        Deque<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> toBreak = new ArrayList<>();

        // 6-direction neighbors (you can expand to 26 if desired)
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

                broken++;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        if (broken > 0) {
            String blockId = originalState.getBlock().toString();
            // サーバー側ログ（コンソール）には常に出す
            try {
                System.out.println("[VeinMiner] Vein broken: " + broken + " " + blockId);
            } catch (Throwable ignored) {}

            // チャットへ出すかどうかは設定次第（ConfigManager.logToChat）
            try {
                boolean logChat = false;
                if (ConfigManager.INSTANCE != null) logChat = ConfigManager.INSTANCE.logToChat;
                if (logChat) {
                    try {
                        player.sendMessage(Text.translatable("chat.ore_picker.vein_broken", broken, Text.literal(blockId)), false);
                    } catch (Throwable ignored) {
                        try { player.sendMessage(Text.literal("一括破壊：" + broken + " 個の " + blockId), false); } catch (Throwable ignored2) {}
                    }
                }
            } catch (Throwable ignored) {}

            // S2C: クライアントへ broken count をプレイヤーに送信（確実に届くように複数方式を試す）
            try {
                sendVeinCountToPlayer(player, broken);
            } catch (Throwable t) {
                try { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); } catch (Throwable ignored) {}
            }
        }

        return broken;
    }

    /**
     * broken count をプレイヤーへ送るユーティリティ。
     * - 可能なら Fabric ServerPlayNetworking.send(ServerPlayerEntity, Identifier, PacketByteBuf) を使う（リフレクション）
     * - それが存在しない／失敗したら CustomPayloadS2CPacket をリフレクションで作って送信する
     */
    private static void sendVeinCountToPlayer(ServerPlayerEntity player, int broken) {
        if (player == null) return;
        try {
            Identifier id = NetworkUtil.makeIdentifier("orepicker", "vein_count");
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            buf.writeVarInt(broken);

            // 1) try Fabric ServerPlayNetworking.send(ServerPlayerEntity, Identifier, PacketByteBuf)
            try {
                Class<?> spnCls = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking");
                Method m = spnCls.getMethod("send", net.minecraft.server.network.ServerPlayerEntity.class, net.minecraft.util.Identifier.class, net.minecraft.network.PacketByteBuf.class);
                m.invoke(null, player, id, buf);
                return;
            } catch (NoSuchMethodException nsme) {
                // method not available: fallthrough to vanilla packet
            } catch (Throwable t) {
                // reflective call failed -> fallthrough
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
            }

            // 2) fallback: create vanilla CustomPayload packet reflectively and send via player's networkHandler
            try {
                // try to find CustomPayloadS2CPacket class (mappings vary, so use Class.forName)
                Class<?> pktClass = null;
                try {
                    pktClass = Class.forName("net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket");
                } catch (ClassNotFoundException cnf1) {
                    try {
                        // alternative name in some mappings (rare)
                        pktClass = Class.forName("net.minecraft.network.PacketByteBuf"); // dummy try; will fail the ctor below
                    } catch (ClassNotFoundException ignored) {
                        pktClass = null;
                    }
                }

                if (pktClass != null) {
                    // prefer ctor (Identifier, PacketByteBuf)
                    Constructor<?> ctor = null;
                    try {
                        ctor = pktClass.getConstructor(Identifier.class, PacketByteBuf.class);
                    } catch (Throwable ctorEx) {
                        // try (PacketByteBuf) constructor (older mappings sometimes use single-arg with Identifier encoded inside buf)
                        try {
                            ctor = pktClass.getConstructor(PacketByteBuf.class);
                        } catch (Throwable ignored) {
                            ctor = null;
                        }
                    }

                    Object pkt = null;
                    if (ctor != null) {
                        try {
                            if (ctor.getParameterCount() == 2) {
                                pkt = ctor.newInstance(id, buf);
                            } else if (ctor.getParameterCount() == 1) {
                                pkt = ctor.newInstance(buf);
                            }
                        } catch (Throwable instEx) {
                            pkt = null;
                        }
                    }

                    if (pkt != null) {
                        // try sending via player.networkHandler.sendPacket(pkt)
                        try {
                            Method sendM = null;
                            try {
                                sendM = player.networkHandler.getClass().getMethod("sendPacket", java.lang.Class.forName("net.minecraft.network.Packet"));
                            } catch (Throwable e) {
                                // try to find any sendPacket with 1 parameter
                                for (Method mm : player.networkHandler.getClass().getMethods()) {
                                    if (mm.getName().equals("sendPacket") && mm.getParameterCount() == 1) {
                                        sendM = mm;
                                        break;
                                    }
                                }
                            }
                            if (sendM != null) {
                                sendM.invoke(player.networkHandler, pkt);
                                return;
                            } else {
                                // last resort: try invoke any method named 'sendPacket' with single arg
                                for (Method mm : player.networkHandler.getClass().getMethods()) {
                                    if (mm.getName().equals("sendPacket") && mm.getParameterCount() == 1) {
                                        try { mm.invoke(player.networkHandler, pkt); return; } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        } catch (Throwable sendEx) {
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) sendEx.printStackTrace();
                        }
                    }
                }
            } catch (Throwable t2) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t2.printStackTrace();
            }

        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
        }
    }
}
