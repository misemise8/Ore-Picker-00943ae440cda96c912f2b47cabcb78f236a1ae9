package net.misemise.ore_picker.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

import net.misemise.ore_picker.network.HoldC2SPayload;
import net.misemise.ore_picker.client.HoldHudOverlay;
import net.misemise.ore_picker.config.ConfigManager;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.network.PacketByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.math.BlockPos;

/**
 * Robust reflective Client initializer.
 *
 * Tries many strategies to:
 *  - send hold state to server (various ClientPlayNetworking.send overloads)
 *  - register client receiver for orepicker:vein_count (various registerGlobalReceiver signatures)
 *
 * This aims to survive mapping / Fabric API differences.
 */
public class Ore_pickerClient implements ClientModInitializer {
    private static KeyBinding holdKey;
    public static volatile boolean localHold = false;
    private static volatile boolean lastSent = false;

    // for HUD
    public static volatile int lastVeinCount = 0;
    public static volatile long veinCountTimeMs = 0L;

    // selected blocks for outline rendering (public so the renderer can read via reflection)
    public static final Set<BlockPos> selectedBlocks = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void onInitializeClient() {
        System.out.println("[OrePickerClient] client init: registering codec (client side) + keybind");

        // try register payload codec on client side (best-effort; non-fatal)
        try {
            try {
                Class<?> ptrCls = Class.forName("net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry");
                Method playC2S = ptrCls.getMethod("playC2S");
                Object registry = playC2S.invoke(null);
                // we don't strictly depend on invoking register here; just try to access to avoid missing-class surprises
                System.out.println("[OrePickerClient] PayloadTypeRegistry found (client).");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                System.err.println("[OrePickerClient] codec register attempt threw:");
                t.printStackTrace();
            }
        }

        // Keybind (V default)
        holdKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.orepicker.hold",
                GLFW.GLFW_KEY_V,
                "category.orepicker"
        ));

        // tick: detect presses and send when changed
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (client.player == null) return;

                boolean pressed = holdKey.isPressed();
                localHold = pressed;

                if (pressed != lastSent) {
                    lastSent = pressed;
                    sendHoldPayloadRobust(pressed);
                }
            } catch (Throwable t) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
            }
        });

        // HUD overlay registration (calls HoldHudOverlay.register(), which itself tries HudRenderCallback)
        try {
            HoldHudOverlay.register();
        } catch (Throwable t) {
            System.err.println("[OrePickerClient] HoldHudOverlay.register() threw:");
            t.printStackTrace();
        }

        // register vein_count receiver (robust)
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

        // config screen opener (best-effort)
        try {
            net.misemise.ore_picker.client.ConfigScreenOpener.init();
        } catch (Throwable ignored) {}
    }

    /**
     * Try multiple ways to send the hold state to server.
     */
    private static void sendHoldPayloadRobust(boolean pressed) {
        // Strategy A: If there's a send(payload) method that accepts our HoldC2SPayload wrapper, use it.
        try {
            Object payloadInstance = null;
            try {
                Constructor<HoldC2SPayload> ctor = HoldC2SPayload.class.getConstructor(boolean.class);
                payloadInstance = ctor.newInstance(pressed);
            } catch (Throwable ignored) {
                // ignore
            }

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

        // Strategy B: try send(idLike, PacketByteBuf) or various overloads
        try {
            Class<?> clientPlayNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> packetBufClass = tryForClass("net.minecraft.network.PacketByteBuf");
            Class<?> unpooledClass = tryForClass("io.netty.buffer.Unpooled");

            Object buf = null;
            if (packetBufClass != null && unpooledClass != null) {
                // try PacketByteBuf(Unpooled.buffer())
                try {
                    Object byteBuf = unpooledClass.getMethod("buffer").invoke(null);
                    Constructor<?> pbCtor = packetBufClass.getConstructor(io.netty.buffer.ByteBuf.class);
                    buf = pbCtor.newInstance(byteBuf);
                } catch (Throwable t1) {
                    // fallback: try no-arg ctor
                    try {
                        Constructor<?> pbCtor2 = packetBufClass.getDeclaredConstructor();
                        pbCtor2.setAccessible(true);
                        buf = pbCtor2.newInstance();
                    } catch (Throwable ignore) {}
                }

                if (buf != null) {
                    // write boolean or byte
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

            // build candidate id-like object: prefer HoldC2SPayload.TYPE or ID fields
            Object idArg = null;
            try {
                Field f = HoldC2SPayload.class.getField("TYPE");
                idArg = f.get(null);
            } catch (Throwable ignored) {}
            if (idArg == null) {
                try {
                    Field f2 = HoldC2SPayload.class.getField("ID");
                    idArg = f2.get(null);
                } catch (Throwable ignored) {}
            }

            // fallback: try to create Identifier via reflection
            if (idArg == null) {
                try {
                    Class<?> idCls = tryForClass("net.minecraft.util.Identifier");
                    if (idCls != null) {
                        // try static of(ns,path)
                        try {
                            Method ofM = idCls.getMethod("of", String.class, String.class);
                            idArg = ofM.invoke(null, "orepicker", "hold_state");
                        } catch (Throwable t) {
                            // try constructor (may be private); setAccessible true
                            try {
                                Constructor<?> c = idCls.getDeclaredConstructor(String.class, String.class);
                                c.setAccessible(true);
                                idArg = c.newInstance("orepicker", "hold_state");
                            } catch (Throwable ex2) {
                                // try single-string ctor "ns:path"
                                try {
                                    Constructor<?> c2 = idCls.getDeclaredConstructor(String.class);
                                    c2.setAccessible(true);
                                    idArg = c2.newInstance("orepicker:hold_state");
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // now try to find send methods with (idLike, PacketByteBuf) or (Identifier, PacketByteBuf) patterns
            for (Method m : clientPlayNet.getMethods()) {
                if (!m.getName().equals("send")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && packetBufClass != null && pts[1].isAssignableFrom(packetBufClass)) {
                    // try to coerce idArg to first param type
                    Object idToUse = tryCoerceIdArg(pts[0], idArg);
                    if (idToUse == null && idArg != null && pts[0].isInstance(idArg)) idToUse = idArg;

                    if (idToUse != null && buf != null) {
                        try {
                            m.invoke(null, idToUse, buf);
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                                System.out.println("[OrePickerClient] sendHoldPayload: sent via send(id,buf) route.");
                            return;
                        } catch (IllegalArgumentException iae) {
                            // try next
                        }
                    }
                }
            }

            // final fallback: try ClientPlayNetworking.send(Identifier, PacketByteBuf) by searching for a method where first param name contains "identifier" (best-effort)
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
            // missing API - cannot send this way
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

    /** Try to coerce or construct an instance for the id parameter type required by a send(...) method. */
    private static Object tryCoerceIdArg(Class<?> paramType, Object existingId) {
        if (existingId != null && paramType.isInstance(existingId)) return existingId;

        // if paramType has a static "of" or "tryParse" method, try create
        try {
            if (existingId instanceof String) {
                String s = (String) existingId;
                try {
                    Method of = paramType.getMethod("of", String.class);
                    return of.invoke(null, s);
                } catch (Throwable ignored) {}
            }

            // try typical Identifier constructors
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

    /**
     * Register a receiver for "orepicker:vein_count" using a robust reflective strategy.
     * The handler will read one int/varint/byte and write it into lastVeinCount + veinCountTimeMs,
     * and call HoldHudOverlay.onVeinCountReceived(count).
     */
    private static void registerVeinCountReceiverRobust() throws Exception {
        Class<?> clientPlayNet;
        try {
            clientPlayNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
        } catch (ClassNotFoundException cnf) {
            throw new RuntimeException("ClientPlayNetworking not present", cnf);
        }

        // Prepare an "id" object to pass as first param: prefer VeinCountS2CPayload.TYPE if exists
        Object idObj = null;
        try {
            Class<?> wrapper = null;
            try {
                wrapper = Class.forName("net.misemise.ore_picker.network.VeinCountS2CPayload");
            } catch (Throwable ignored) {}
            if (wrapper != null) {
                try {
                    Field typeField = wrapper.getField("TYPE");
                    idObj = typeField.get(null);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // fallback: construct Identifier via reflection
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

        // find a suitable registerGlobalReceiver method (2-arg) and call it
        Method targetMethod = null;
        for (Method m : clientPlayNet.getMethods()) {
            if (!m.getName().equals("registerGlobalReceiver")) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 2) {
                targetMethod = m;
                break;
            }
        }
        if (targetMethod == null) {
            throw new RuntimeException("registerGlobalReceiver not found on ClientPlayNetworking");
        }

        final Class<?> handlerIface = targetMethod.getParameterTypes()[1];

        // create a proxy instance for handlerIface
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
                        // schedule on client thread if possible
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

        // Try to invoke targetMethod with idObj directly first, else try wrapper.TYPE or coerce
        try {
            if (idObj != null) {
                targetMethod.invoke(null, idObj, handlerProxy);
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                    System.out.println("[OrePickerClient] registered vein_count receiver (via id).");
                return;
            }
        } catch (IllegalArgumentException iae) {
            // try fallback below
        }

        // Try to find a TYPE field on a wrapper (VeinCountS2CPayload)
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

        // As a last resort, try to coerce an Identifier-like object to the first param type
        Class<?> firstParamType = targetMethod.getParameterTypes()[0];
        Object coerced = null;
        try {
            // try Identifier creation
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

        // if we reach here, registration failed
        throw new RuntimeException("Failed to register vein_count receiver: could not coerce id argument.");
    }
}
