package net.misemise.ore_picker.network;

import net.minecraft.util.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * NetworkUtil - Identifier を安全に作成するユーティリティ。
 * mappings による差異を吸収するため複数方法を試行する。
 */
public final class NetworkUtil {
    private NetworkUtil() {}

    public static Identifier makeIdentifier(String ns, String path) {
        // 1) try Identifier.of(ns, path)
        try {
            Method ofm = Identifier.class.getMethod("of", String.class, String.class);
            Object o = ofm.invoke(null, ns, path);
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}

        // 2) try public constructor (String, String)
        try {
            Constructor<Identifier> ctor = Identifier.class.getConstructor(String.class, String.class);
            Object o = ctor.newInstance(ns, path);
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}

        // 3) try declared constructor (String, String)
        try {
            Constructor<Identifier> ctor = Identifier.class.getDeclaredConstructor(String.class, String.class);
            ctor.setAccessible(true);
            Object o = ctor.newInstance(ns, path);
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}

        // 4) try single-string constructor "ns:path"
        try {
            Constructor<Identifier> ctor = Identifier.class.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            Object o = ctor.newInstance(ns + ":" + path);
            if (o instanceof Identifier) return (Identifier) o;
        } catch (Throwable ignored) {}

        throw new RuntimeException("Unable to construct Identifier for " + ns + ":" + path);
    }
}
