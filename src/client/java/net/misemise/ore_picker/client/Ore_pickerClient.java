package net.misemise.ore_picker.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

import net.misemise.ore_picker.network.HoldC2SPayload;
import net.misemise.ore_picker.client.HoldHudOverlay;
import net.misemise.ore_picker.config.ConfigManager;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;

/**
 * Ore_pickerClient（robust networking）にクライアント側選択機能を追加した版
 */
public class Ore_pickerClient implements ClientModInitializer {
    private static KeyBinding holdKey;
    public static volatile boolean localHold = false;
    private static volatile boolean lastSent = false;

    public static volatile int lastVeinCount = 0;
    public static volatile long veinCountTimeMs = 0L;

    // selection exposed for renderer
    public static final Set<BlockPos> selectedBlocks = Collections.synchronizedSet(new HashSet<>());

    // to avoid recompute every tick
    private static volatile BlockPos lastSelectedOrigin = null;

    @Override
    public void onInitializeClient() {
        System.out.println("[OrePickerClient] client init: registering codec (client side) + keybind");

        try {
            try {
                Class<?> ptrCls = Class.forName("net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry");
                Method playC2S = ptrCls.getMethod("playC2S");
                playC2S.invoke(null);
                System.out.println("[OrePickerClient] PayloadTypeRegistry found (client).");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                System.err.println("[OrePickerClient] codec register attempt threw:");
                t.printStackTrace();
            }
        }

        holdKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.orepicker.hold",
                GLFW.GLFW_KEY_V,
                "category.orepicker"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (client.player == null) return;

                boolean pressed = holdKey.isPressed();
                localHold = pressed;

                if (pressed != lastSent) {
                    lastSent = pressed;
                    sendHoldPayloadRobust(pressed);
                }

                // update selection on client while holding
                try {
                    updateClientSelectedBlocks(client);
                } catch (Throwable t) {
                    if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
                }
            } catch (Throwable t) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
            }
        });

        try {
            HoldHudOverlay.register();
        } catch (Throwable t) {
            System.err.println("[OrePickerClient] HoldHudOverlay.register() threw:");
            t.printStackTrace();
        }

        try {
            registerVeinCountReceiverRobust();
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                System.err.println("[OrePickerClient] registerVeinCountReceiverRobust() threw:");
                t.printStackTrace();
            } else {
                System.err.println("[OrePickerClient] Cannot register vein_count receiver (debug disabled).");
            }
        }

        try {
            net.misemise.ore_picker.client.rendering.OutlineRenderer.register();
        } catch (Throwable ignored) {}

        try {
            net.misemise.ore_picker.client.ConfigScreenOpener.init();
        } catch (Throwable ignored) {}
    }

    /**
     * client-side simple selection: raycast origin and BFS to same block type up to config limit
     */
    private static void updateClientSelectedBlocks(MinecraftClient client) {
        if (!localHold) {
            if (!selectedBlocks.isEmpty()) {
                selectedBlocks.clear();
                lastSelectedOrigin = null;
            }
            return;
        }

        if (client.player == null || client.world == null) return;

        HitResult hr = client.crosshairTarget;
        if (!(hr instanceof BlockHitResult)) {
            if (!selectedBlocks.isEmpty()) { selectedBlocks.clear(); lastSelectedOrigin = null; }
            return;
        }

        BlockHitResult bhr = (BlockHitResult) hr;
        BlockPos origin = bhr.getBlockPos();

        if (origin.equals(lastSelectedOrigin)) return;
        lastSelectedOrigin = origin;

        selectedBlocks.clear();

        int maxSize = 64;
        try {
            if (ConfigManager.INSTANCE != null) {
                maxSize = Math.max(1, ConfigManager.INSTANCE.maxVeinSize);
            }
        } catch (Throwable ignored) {}

        BlockState originState = client.world.getBlockState(origin);
        Block originBlock = originState.getBlock();

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty() && visited.size() < maxSize) {
            BlockPos p = queue.poll();
            BlockState bs = client.world.getBlockState(p);
            if (bs.getBlock() != originBlock) continue;

            selectedBlocks.add(p);

            for (Direction d : Direction.values()) {
                BlockPos np = p.offset(d);
                if (visited.contains(np)) continue;
                int manh = Math.abs(np.getX() - origin.getX()) + Math.abs(np.getY() - origin.getY()) + Math.abs(np.getZ() - origin.getZ());
                if (manh > Math.max(64, maxSize * 3)) continue;
                visited.add(np);
                queue.add(np);
                if (visited.size() >= maxSize) break;
            }
        }

        if (selectedBlocks.size() > maxSize) {
            Iterator<BlockPos> it = selectedBlocks.iterator();
            Set<BlockPos> trimmed = new HashSet<>();
            int i = 0;
            while (it.hasNext() && i < maxSize) { trimmed.add(it.next()); i++; }
            selectedBlocks.clear();
            selectedBlocks.addAll(trimmed);
        }

        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
            System.out.println("[OrePickerClient] selectedBlocks size=" + selectedBlocks.size());
        }
    }

    /* ---------- 以下はあなたが以前に持っていた robust networking メソッドを（完全な形で）入れてください ---------- */
    /* sendHoldPayloadRobust(...) と registerVeinCountReceiverRobust() は
       会話で提示されていた長い互換実装がそのまま動いていたはずなので、
       その実装をここに差し込んでください（長いためここでは省略しません）.
       もし要るなら私から会話中にあった完全実装を丸ごと再掲します。 */

    private static void sendHoldPayloadRobust(boolean pressed) {
        // --- ここにあなたの既存の robust 実装を丸ごと入れてください ---
        // 会話中に最初に出してくれたファイルに既に入っていた sendHoldPayloadRobust を使えばOKです。
        try {
            Object payloadInstance = null;
            try {
                Constructor<HoldC2SPayload> ctor = HoldC2SPayload.class.getConstructor(boolean.class);
                payloadInstance = ctor.newInstance(pressed);
            } catch (Throwable ignored) {}
            if (payloadInstance != null) {
                try {
                    Class<?> clientPlayNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
                    for (Method m : clientPlayNet.getMethods()) {
                        if (!m.getName().equals("send")) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length == 1 && pts[0].isAssignableFrom(payloadInstance.getClass())) {
                            m.invoke(null, payloadInstance);
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                                System.out.println("[OrePickerClient] sendHoldPayload: sent via send(payload) route.");
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // (残りの互換ルートもあなたの元実装を貼ってください)
        try {
            Class<?> clientPlayNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> packetBufClass = tryForClass("net.minecraft.network.PacketByteBuf");
            Class<?> unpooledClass = tryForClass("io.netty.buffer.Unpooled");

            Object buf = null;
            if (packetBufClass != null && unpooledClass != null) {
                try {
                    Object byteBuf = unpooledClass.getMethod("buffer").invoke(null);
                    Constructor<?> pbCtor = packetBufClass.getConstructor(io.netty.buffer.ByteBuf.class);
                    buf = pbCtor.newInstance(byteBuf);
                } catch (Throwable t1) {
                    try {
                        Constructor<?> pbCtor2 = packetBufClass.getDeclaredConstructor();
                        pbCtor2.setAccessible(true);
                        buf = pbCtor2.newInstance();
                    } catch (Throwable ignore) {}
                }

                if (buf != null) {
                    try {
                        Method writeBool = packetBufClass.getMethod("writeBoolean", boolean.class);
                        writeBool.invoke(buf, pressed);
                    } catch (NoSuchMethodException nsme) {
                        try {
                            Method writeByte = packetBufClass.getMethod("writeByte", int.class);
                            writeByte.invoke(buf, pressed ? 1 : 0);
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                }
            }

            Object idArg = null;
            try { Field f = HoldC2SPayload.class.getField("TYPE"); idArg = f.get(null); } catch (Throwable ignored) {}
            if (idArg == null) {
                try { Field f2 = HoldC2SPayload.class.getField("ID"); idArg = f2.get(null); } catch (Throwable ignored) {}
            }
            if (idArg == null) {
                try {
                    Class<?> idCls = tryForClass("net.minecraft.util.Identifier");
                    if (idCls != null) {
                        try {
                            Method ofM = idCls.getMethod("of", String.class, String.class);
                            idArg = ofM.invoke(null, "orepicker", "hold_state");
                        } catch (Throwable t) {
                            try {
                                Constructor<?> c = idCls.getDeclaredConstructor(String.class, String.class);
                                c.setAccessible(true);
                                idArg = c.newInstance("orepicker", "hold_state");
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }

            for (Method m : clientPlayNet.getMethods()) {
                if (!m.getName().equals("send")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && packetBufClass != null && pts[1].isAssignableFrom(packetBufClass)) {
                    Object idToUse = tryCoerceIdArg(pts[0], idArg);
                    if (idToUse == null && idArg != null && pts[0].isInstance(idArg)) idToUse = idArg;

                    if (idToUse != null && buf != null) {
                        try {
                            m.invoke(null, idToUse, buf);
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                                System.out.println("[OrePickerClient] sendHoldPayload: sent via send(id,buf) route.");
                            return;
                        } catch (IllegalArgumentException iae) {
                        }
                    }
                }
            }

            for (Method m : clientPlayNet.getMethods()) {
                if (!m.getName().equals("send")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && buf != null) {
                    if (idArg != null) {
                        try {
                            m.invoke(null, idArg, buf);
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                                System.out.println("[OrePickerClient] sendHoldPayload: sent via fallback route (direct).");
                            return;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (ClassNotFoundException cnf) {
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                System.err.println("[OrePickerClient] sendHoldPayload: unexpected error:");
                t.printStackTrace();
            }
        }

        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
            System.err.println("[OrePickerClient] Could not find a compatible ClientPlayNetworking.send(...) method to send hold state.");
        }
    }

    private static Object tryCoerceIdArg(Class<?> paramType, Object existingId) {
        if (existingId != null && paramType.isInstance(existingId)) return existingId;
        try {
            if (existingId instanceof String) {
                String s = (String) existingId;
                try {
                    Method of = paramType.getMethod("of", String.class);
                    return of.invoke(null, s);
                } catch (Throwable ignored) {}
            }
            if (paramType.getName().contains("Identifier") || paramType.getName().toLowerCase().contains("identifier")) {
                try {
                    Constructor<?> c = paramType.getDeclaredConstructor(String.class, String.class);
                    c.setAccessible(true);
                    return c.newInstance("orepicker", "hold_state");
                } catch (Throwable ignored) {}
                try {
                    Constructor<?> c2 = paramType.getDeclaredConstructor(String.class);
                    c2.setAccessible(true);
                    return c2.newInstance("orepicker:hold_state");
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> tryForClass(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }

    private static void registerVeinCountReceiverRobust() throws Exception {
        // --- ここも会話で最初に提示されていた registerVeinCountReceiverRobust の完全実装を再利用してください ---
        // 実装は長いので会話中にあったものを丸ごとコピー／ペーストするのが早いです。
        Class<?> clientPlayNet;
        try {
            clientPlayNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
        } catch (ClassNotFoundException cnf) {
            throw new RuntimeException("ClientPlayNetworking not present", cnf);
        }

        Object idObj = null;
        try {
            Class<?> wrapper = null;
            try {
                wrapper = Class.forName("net.misemise.ore_picker.network.VeinCountS2CPayload");
            } catch (Throwable ignored) {}
            if (wrapper != null) {
                try { Field typeField = wrapper.getField("TYPE"); idObj = typeField.get(null); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        if (idObj == null) {
            Class<?> idCls = tryForClass("net.minecraft.util.Identifier");
            if (idCls != null) {
                try {
                    try {
                        Method of = idCls.getMethod("of", String.class, String.class);
                        idObj = of.invoke(null, "orepicker", "vein_count");
                    } catch (Throwable t) {
                        Constructor<?> c = idCls.getDeclaredConstructor(String.class, String.class);
                        c.setAccessible(true);
                        idObj = c.newInstance("orepicker", "vein_count");
                    }
                } catch (Throwable ignored) {}
            }
        }

        Method targetMethod = null;
        for (Method m : clientPlayNet.getMethods()) {
            if (!m.getName().equals("registerGlobalReceiver")) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 2) { targetMethod = m; break; }
        }
        if (targetMethod == null) throw new RuntimeException("registerGlobalReceiver not found on ClientPlayNetworking");

        final Class<?> handlerIface = targetMethod.getParameterTypes()[1];

        Object handlerProxy = Proxy.newProxyInstance(
                handlerIface.getClassLoader(),
                new Class<?>[]{handlerIface},
                (proxy, method, args) -> {
                    try {
                        if (args == null || args.length == 0) return null;
                        Object pkt = null;
                        for (Object a : args) {
                            if (a == null) continue;
                            String cn = a.getClass().getName().toLowerCase();
                            if (cn.contains("packetbytebuf") || cn.contains("packetbuf") || cn.contains("packet")) { pkt = a; break; }
                        }
                        int broken = 0;
                        if (pkt != null) {
                            try {
                                Method readVarInt = pkt.getClass().getMethod("readVarInt");
                                Object rv = readVarInt.invoke(pkt);
                                if (rv instanceof Integer) broken = (Integer) rv;
                            } catch (NoSuchMethodException nsme) {
                                try {
                                    Method readInt = pkt.getClass().getMethod("readInt");
                                    Object rv = readInt.invoke(pkt);
                                    if (rv instanceof Integer) broken = (Integer) rv;
                                } catch (NoSuchMethodException nsme2) {
                                    try {
                                        Method readByte = pkt.getClass().getMethod("readByte");
                                        Object rv = readByte.invoke(pkt);
                                        if (rv instanceof Number) broken = ((Number) rv).intValue();
                                    } catch (Throwable ignored) {}
                                } catch (Throwable ignored) {}
                            } catch (Throwable ignored) {}
                        }
                        final int fb = broken;
                        try {
                            Class<?> mcCls = Class.forName("net.minecraft.client.MinecraftClient");
                            Method getInst = mcCls.getMethod("getInstance");
                            Object mc = getInst.invoke(null);
                            try {
                                Method exec = mc.getClass().getMethod("execute", Runnable.class);
                                exec.invoke(mc, (Runnable) () -> {
                                    try {
                                        lastVeinCount = fb;
                                        veinCountTimeMs = System.currentTimeMillis();
                                        try { HoldHudOverlay.onVeinCountReceived(fb); } catch (Throwable ignored) {}
                                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                                            System.out.println("[OrePickerClient] Received vein_count = " + fb);
                                        }
                                    } catch (Throwable t) { if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace(); }
                                });
                            } catch (NoSuchMethodException nsme) {
                                lastVeinCount = fb;
                                veinCountTimeMs = System.currentTimeMillis();
                                try { HoldHudOverlay.onVeinCountReceived(fb); } catch (Throwable ignored) {}
                            }
                        } catch (Throwable t) {
                            lastVeinCount = fb;
                            veinCountTimeMs = System.currentTimeMillis();
                            try { HoldHudOverlay.onVeinCountReceived(fb); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable t) {
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
                    }
                    return null;
                });

        try {
            if (idObj != null) {
                targetMethod.invoke(null, idObj, handlerProxy);
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                    System.out.println("[OrePickerClient] registered vein_count receiver (via id).");
                return;
            }
        } catch (IllegalArgumentException iae) {
        }

        try {
            Class<?> wrapper = null;
            try { wrapper = Class.forName("net.misemise.ore_picker.network.VeinCountS2CPayload"); } catch (ClassNotFoundException cnf) { wrapper = null; }
            if (wrapper != null) {
                try {
                    Field typeField = wrapper.getField("TYPE");
                    Object typeObj = typeField.get(null);
                    targetMethod.invoke(null, typeObj, handlerProxy);
                    if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                        System.out.println("[OrePickerClient] registered vein_count receiver (via TYPE).");
                    return;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        Class<?> firstParamType = targetMethod.getParameterTypes()[0];
        Object coerced = null;
        try {
            Class<?> idCls = tryForClass("net.minecraft.util.Identifier");
            if (idCls != null) {
                try {
                    Method of = idCls.getMethod("of", String.class, String.class);
                    Object idCandidate = of.invoke(null, "orepicker", "vein_count");
                    if (firstParamType.isInstance(idCandidate)) coerced = idCandidate;
                } catch (Throwable t) {
                    try {
                        Constructor<?> c = idCls.getDeclaredConstructor(String.class, String.class);
                        c.setAccessible(true);
                        Object idCandidate = c.newInstance("orepicker", "vein_count");
                        if (firstParamType.isInstance(idCandidate)) coerced = idCandidate;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        if (coerced != null) {
            targetMethod.invoke(null, coerced, handlerProxy);
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                System.out.println("[OrePickerClient] registered vein_count receiver (via coerced Identifier).");
            return;
        }

        throw new RuntimeException("Failed to register vein_count receiver: could not coerce id argument.");
    }
}
