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
import java.util.Objects;

/**
 * Robust client initializer that tries multiple send/register strategies to survive mapping/API differences.
 */
public class Ore_pickerClient implements ClientModInitializer {
    private static KeyBinding holdKey;
    public static volatile boolean localHold = false;
    private static boolean lastSent = false;

    // fields for server -> client vein count immediate HUD display
    public static volatile int lastVeinCount = 0;
    public static volatile long veinCountTimeMs = 0L;

    @Override
    public void onInitializeClient() {
        System.out.println("[OrePickerClient] client init: registering codec (client side) + keybind");

        // try to register codec on client side (best-effort; may be redundant)
        try {
            try {
                Class<?> ptrCls = Class.forName("net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry");
                Method playC2S = ptrCls.getMethod("playC2S");
                Object registry = playC2S.invoke(null);
                Method register = registry.getClass().getMethod("register", HoldC2SPayload.TYPE.getClass(), HoldC2SPayload.CODEC.getClass());
                // if above line fails because of type mismatch, fallthrough to simple log (we don't strictly need it client-side)
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

        // Each tick, update localHold and send payload if changed
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            boolean pressed = holdKey.isPressed();
            localHold = pressed;

            if (pressed != lastSent) {
                lastSent = pressed;
                sendHoldPayloadRobust(pressed);
            }
        });

        // HUD: register overlay (will try Fabric HudRenderCallback; if not present, a mixin should call renderOnTop)
        try {
            HoldHudOverlay.register();
        } catch (Throwable t) {
            System.err.println("[OrePickerClient] HoldHudOverlay.register() threw:");
            t.printStackTrace();
        }

        // register client-side receiver for vein_count (best-effort reflective)
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

        // Config screen opener (if implemented)
        try {
            net.misemise.ore_picker.client.ConfigScreenOpener.init();
        } catch (Throwable ignored) {}
    }

    /**
     * Robust sender: try several ClientPlayNetworking.send(...) signatures.
     */
    private static void sendHoldPayloadRobust(boolean pressed) {
        // 1) Try to construct HoldC2SPayload instance and call ClientPlayNetworking.send(payload)
        try {
            // construct payload instance
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

        // 2) Try send(CustomPayload.Id/CUSTOM_ID, PacketByteBuf)
        try {
            Class<?> clientPlayNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> packetBufClass = Class.forName("net.minecraft.network.PacketByteBuf");
            Class<?> unpooledClass = Class.forName("io.netty.buffer.Unpooled");
            Object buf = null;
            try {
                Object byteBuf = unpooledClass.getMethod("buffer").invoke(null);
                Constructor<?> pbCtor = packetBufClass.getConstructor(io.netty.buffer.ByteBuf.class);
                buf = pbCtor.newInstance(byteBuf);
            } catch (Throwable t1) {
                // try PacketByteBuf.allocateDirect / no-arg - fallbacks
                try {
                    buf = packetBufClass.getDeclaredConstructor().newInstance();
                } catch (Throwable ignored) {}
            }
            if (buf != null) {
                // write boolean or byte
                try {
                    Method writeBool = buf.getClass().getMethod("writeBoolean", boolean.class);
                    writeBool.invoke(buf, pressed);
                } catch (NoSuchMethodException nsme) {
                    try {
                        Method writeByte = buf.getClass().getMethod("writeByte", int.class);
                        writeByte.invoke(buf, pressed ? 1 : 0);
                    } catch (Throwable ignored) {}
                }

                // attempt to find a send method with (Object idLike, PacketByteBuf)
                for (Method m : clientPlayNet.getMethods()) {
                    if (!m.getName().equals("send")) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2 && packetBufClass.isAssignableFrom(pts[1])) {
                        // prepare id argument: prefer HoldC2SPayload.TYPE if available, else Identifier
                        Object idArg = null;
                        try {
                            Field typeField = HoldC2SPayload.class.getField("TYPE");
                            idArg = typeField.get(null);
                        } catch (Throwable ignored) {}
                        if (idArg == null) {
                            try {
                                Class<?> idCls = Class.forName("net.minecraft.util.Identifier");
                                Constructor<?> idCtor = null;
                                try { idCtor = idCls.getConstructor(String.class, String.class); } catch (NoSuchMethodException ignore) {}
                                if (idCtor != null) idArg = idCtor.newInstance("orepicker", "hold_state");
                                else {
                                    Constructor<?> idCtor2 = idCls.getDeclaredConstructor(String.class);
                                    idCtor2.setAccessible(true);
                                    idArg = idCtor2.newInstance("orepicker:hold_state");
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (idArg != null && pts[0].isInstance(idArg)) {
                            m.invoke(null, idArg, buf);
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                                System.out.println("[OrePickerClient] sendHoldPayload: sent via send(id,buf) route.");
                            return;
                        } else if (idArg != null) {
                            // try to coerce id arg: if parameter expects Identifier class by name
                            try {
                                m.invoke(null, idArg, buf);
                                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                                    System.out.println("[OrePickerClient] sendHoldPayload: sent via send(id,buf) (coerced).");
                                return;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 3) Last fallback: try Fabric API's ClientPlayNetworking.send(Identifier, PacketByteBuf) directly via reflection once more
        try {
            Class<?> clientPlayNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> packetBufClass = Class.forName("net.minecraft.network.PacketByteBuf");
            Class<?> idCls = Class.forName("net.minecraft.util.Identifier");
            Object idObj = null;
            try {
                Constructor<?> idCtor = idCls.getConstructor(String.class, String.class);
                idObj = idCtor.newInstance("orepicker", "hold_state");
            } catch (NoSuchMethodException nsme) {
                Constructor<?> idCtor2 = idCls.getDeclaredConstructor(String.class);
                idCtor2.setAccessible(true);
                idObj = idCtor2.newInstance("orepicker:hold_state");
            }

            Object buf = null;
            try {
                Class<?> unpooledClass = Class.forName("io.netty.buffer.Unpooled");
                Object byteBuf = unpooledClass.getMethod("buffer").invoke(null);
                Constructor<?> pbCtor = packetBufClass.getConstructor(io.netty.buffer.ByteBuf.class);
                buf = pbCtor.newInstance(byteBuf);
                try {
                    Method writeBool = packetBufClass.getMethod("writeBoolean", boolean.class);
                    writeBool.invoke(buf, pressed);
                } catch (NoSuchMethodException nsme) {
                    Method writeByte = packetBufClass.getMethod("writeByte", int.class);
                    writeByte.invoke(buf, pressed ? 1 : 0);
                }
            } catch (Throwable ignored) {}

            if (buf != null) {
                for (Method m : clientPlayNet.getMethods()) {
                    if (!m.getName().equals("send")) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2 && pts[0].isAssignableFrom(idObj.getClass())) {
                        m.invoke(null, idObj, buf);
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                            System.out.println("[OrePickerClient] sendHoldPayload: sent via fallback identifier route.");
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // If all failed:
        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
            System.err.println("[OrePickerClient] sendHoldPayload: all send strategies failed.");
        }
    }

    /**
     * Register a receiver for "orepicker:vein_count" using a robust reflective strategy.
     * The handler will read one int/varint/byte and write it into lastVeinCount + veinCountTimeMs.
     */
    private static void registerVeinCountReceiverRobust() {
        try {
            Class<?> clientPlayNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> idCls = Class.forName("net.minecraft.util.Identifier");

            // construct identifier (try (ns,path) then "ns:path")
            Object idObj = null;
            try {
                Constructor<?> idCtor = idCls.getConstructor(String.class, String.class);
                idObj = idCtor.newInstance("orepicker", "vein_count");
            } catch (NoSuchMethodException nsme) {
                try {
                    Constructor<?> idCtor2 = idCls.getDeclaredConstructor(String.class);
                    idCtor2.setAccessible(true);
                    idObj = idCtor2.newInstance("orepicker:vein_count");
                } catch (Throwable t2) {
                    idObj = null;
                }
            }

            if (idObj == null) {
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                    System.err.println("[OrePickerClient] registerVeinCountReceiverRobust: cannot construct Identifier for vein_count.");
                }
                return;
            }

            // find registerGlobalReceiver method with (IdLike, handler)
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
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                    System.err.println("[OrePickerClient] registerVeinCountReceiverRobust: no suitable registerGlobalReceiver found.");
                return;
            }

            final Class<?> handlerIface = targetMethod.getParameterTypes()[1];
            Object handlerProxy = Proxy.newProxyInstance(
                    handlerIface.getClassLoader(),
                    new Class<?>[]{handlerIface},
                    (proxy, method, args) -> {
                        try {
                            if (args == null || args.length == 0) return null;
                            Object packetBuf = null;
                            for (Object a : args) {
                                if (a == null) continue;
                                String cn = a.getClass().getName().toLowerCase();
                                if (cn.contains("packetbytebuf")) { packetBuf = a; break; }
                            }
                            int broken = 0;
                            if (packetBuf != null) {
                                try {
                                    Method readVarInt = packetBuf.getClass().getMethod("readVarInt");
                                    Object rv = readVarInt.invoke(packetBuf);
                                    if (rv instanceof Integer) broken = (Integer) rv;
                                } catch (NoSuchMethodException nsme) {
                                    try {
                                        Method readInt = packetBuf.getClass().getMethod("readInt");
                                        Object rv = readInt.invoke(packetBuf);
                                        if (rv instanceof Integer) broken = (Integer) rv;
                                    } catch (NoSuchMethodException nsme2) {
                                        try {
                                            Method readByte = packetBuf.getClass().getMethod("readByte");
                                            Object rv = readByte.invoke(packetBuf);
                                            if (rv instanceof Number) broken = ((Number) rv).intValue();
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                            final int fb = broken;
                            // set on client thread if possible
                            try {
                                Object mc = Class.forName("net.minecraft.client.MinecraftClient").getMethod("getInstance").invoke(null);
                                try {
                                    Method exec = mc.getClass().getMethod("execute", Runnable.class);
                                    exec.invoke(mc, (Runnable) () -> {
                                        Ore_pickerClient.lastVeinCount = fb;
                                        Ore_pickerClient.veinCountTimeMs = System.currentTimeMillis();
                                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                                            System.out.println("[OrePickerClient] Received vein_count = " + fb);
                                        }
                                    });
                                } catch (NoSuchMethodException nsme) {
                                    Ore_pickerClient.lastVeinCount = fb;
                                    Ore_pickerClient.veinCountTimeMs = System.currentTimeMillis();
                                }
                            } catch (Throwable t) {
                                Ore_pickerClient.lastVeinCount = fb;
                                Ore_pickerClient.veinCountTimeMs = System.currentTimeMillis();
                            }
                        } catch (Throwable t) {
                            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) t.printStackTrace();
                        }
                        return null;
                    });

            // attempt registration (try Identifier first)
            try {
                targetMethod.invoke(null, idObj, handlerProxy);
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                    System.out.println("[OrePickerClient] registered vein_count receiver (via id).");
                return;
            } catch (IllegalArgumentException iae) {
                // try custom payload id fallback: find TYPE field on a possible VeinCount wrapper
                try {
                    Class<?> wrapper = null;
                    try {
                        wrapper = Class.forName("net.misemise.ore_picker.network.VeinCountS2CPayload");
                    } catch (ClassNotFoundException cnf) {
                        wrapper = null;
                    }
                    if (wrapper != null) {
                        Field typeField = wrapper.getField("TYPE");
                        Object typeObj = typeField.get(null);
                        targetMethod.invoke(null, typeObj, handlerProxy);
                        if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                            System.out.println("[OrePickerClient] registered vein_count receiver (via TYPE).");
                        return;
                    }
                } catch (Throwable ignored) {}
                if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                    System.err.println("[OrePickerClient] registerGlobalReceiver invoke with Identifier failed (arg mismatch).");
            }
        } catch (ClassNotFoundException cnf) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug)
                System.err.println("[OrePickerClient] ClientPlayNetworking class not present; skipping vein_count receiver registration.");
        } catch (Throwable t) {
            if (ConfigManager.INSTANCE != null && ConfigManager.INSTANCE.debug) {
                System.err.println("[OrePickerClient] registerVeinCountReceiverRobust unexpected error:");
                t.printStackTrace();
            }
        }
    }
}
