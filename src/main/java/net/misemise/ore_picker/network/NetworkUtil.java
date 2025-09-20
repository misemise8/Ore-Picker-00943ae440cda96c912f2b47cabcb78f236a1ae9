package net.misemise.ore_picker.network;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Small helper to create Identifier instances in a way that copes with mapping differences.
 */
public final class NetworkUtil {
    private NetworkUtil() {}

    /**
     * Create an Identifier instance for given namespace/path using reflection where necessary.
     * Returns null if creation fails.
     */
    public static Object makeIdentifier(String namespace, String path) {
        try {
            Class<?> identCls = Class.forName("net.minecraft.util.Identifier");

            // 1) try static factory of(String,String)
            try {
                Method ofm = identCls.getMethod("of", String.class, String.class);
                return ofm.invoke(null, namespace, path);
            } catch (Throwable ignored) {}

            // 2) try public constructor (String,String)
            try {
                Constructor<?> ctor = identCls.getConstructor(String.class, String.class);
                return ctor.newInstance(namespace, path);
            } catch (Throwable ignored) {}

            // 3) try declared constructor (String,String) and setAccessible(true)
            try {
                Constructor<?> ctor = identCls.getDeclaredConstructor(String.class, String.class);
                ctor.setAccessible(true);
                return ctor.newInstance(namespace, path);
            } catch (Throwable ignored) {}

            // 4) try single-string constructor "ns:path"
            try {
                Constructor<?> ctor = identCls.getDeclaredConstructor(String.class);
                ctor.setAccessible(true);
                return ctor.newInstance(namespace + ":" + path);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            // cannot create Identifier
            t.printStackTrace();
        }
        return null;
    }
}
