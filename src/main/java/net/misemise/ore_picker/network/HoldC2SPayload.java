package net.misemise.ore_picker.network;

import net.minecraft.util.Identifier;
import net.minecraft.network.PacketByteBuf;

// PacketCodec / PacketCodecs
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;

/**
 * Client -> Server payload for "hold" (boolean).
 *
 * Notes:
 * - Many mappings name the boolean codec PacketCodecs.BOOLEAN, older/newer ones may use PacketCodecs.BOOL.
 *   If compilation fails complaining BOOLEAN が無い場合は、このファイル内の PacketCodecs.BOOLEAN を
 *   PacketCodecs.BOOL に置き換えてください。
 *
 * - This implementation avoids calling 'new Identifier(...)' directly to prevent compile-time errors
 *   when that constructor is non-public in your mappings. It first tries NetworkUtil.makeIdentifier(...),
 *   then Identifier.of(...), then reflective constructor.
 */
public record HoldC2SPayload(boolean pressed) implements CustomPayload {
    public static final Identifier ID = createId();

    private static Identifier createId() {
        // 1) try helper NetworkUtil.makeIdentifier(...) if available
        try {
            try {
                Class<?> nu = Class.forName("net.misemise.ore_picker.network.NetworkUtil");
                Method mm = nu.getMethod("makeIdentifier", String.class, String.class);
                Object idObj = mm.invoke(null, "orepicker", "hold_state");
                if (idObj instanceof Identifier) return (Identifier) idObj;
            } catch (ClassNotFoundException cnf) {
                // NetworkUtil not present — fallthrough to other strategies
            }
        } catch (Throwable ignored) {}

        // 2) try Identifier.of(ns, path)
        try {
            Method ofm = Identifier.class.getMethod("of", String.class, String.class);
            Object o = ofm.invoke(null, "orepicker", "hold_state");
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}

        // 3) try reflective public constructor (String, String)
        try {
            Constructor<Identifier> ctor = Identifier.class.getConstructor(String.class, String.class);
            Object o = ctor.newInstance("orepicker", "hold_state");
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}

        // 4) try declared (possibly non-public) constructor (String, String)
        try {
            Constructor<Identifier> ctor = Identifier.class.getDeclaredConstructor(String.class, String.class);
            ctor.setAccessible(true);
            Object o = ctor.newInstance("orepicker", "hold_state");
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}

        // 5) try single-string constructor "ns:path"
        try {
            Constructor<Identifier> ctor = Identifier.class.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            Object o = ctor.newInstance("orepicker:hold_state");
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}

        throw new RuntimeException("Unable to construct Identifier for HoldC2SPayload (tried NetworkUtil, Identifier.of, and reflective ctors).");
    }

    // CustomPayload.Id wrapper
    public static final CustomPayload.Id<HoldC2SPayload> TYPE = new CustomPayload.Id<>(ID);

    // PacketCodec: (PacketByteBuf) <-> HoldC2SPayload
    // NOTE: Replace PacketCodecs.BOOLEAN -> PacketCodecs.BOOL if your mappings require it.
    public static final PacketCodec<PacketByteBuf, HoldC2SPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.BOOLEAN,
                    HoldC2SPayload::pressed,
                    HoldC2SPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
