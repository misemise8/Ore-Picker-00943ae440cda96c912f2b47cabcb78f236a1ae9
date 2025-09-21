package net.misemise.ore_picker.config;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * ConfigManager:
 * - 起動時に config/orepicker.properties を読み込んで ModConfig.INSTANCE に反映します。
 * - ファイルがなければデフォルトを作成して書き出します。
 *
 * ファイル形式（properties）:
 *   maxVeinSize=64
 *   maxVeinSizeCap=512
 *   mergeDifferentOreTypes=false
 *   autoCollectEnabled=true
 *   pickupRadius=1.5
 *   extraOreBlocks=modid:ore1,othermod:ore2
 *   debug=false
 */
public final class ConfigManager {
    public static ModConfig INSTANCE = new ModConfig();

    private ConfigManager() {}

    public static void load() {
        Path cfgDir = Path.of("config");
        Path cfgFile = cfgDir.resolve("orepicker.properties");

        // ensure dir
        try {
            if (!Files.exists(cfgDir)) Files.createDirectories(cfgDir);
        } catch (Throwable t) {
            System.out.println("[OrePicker] Could not create config dir: " + t.getMessage());
        }

        Properties props = new Properties();

        if (Files.exists(cfgFile)) {
            try (InputStream in = Files.newInputStream(cfgFile)) {
                props.load(in);
            } catch (Throwable t) {
                System.out.println("[OrePicker] Failed to load config file, using defaults: " + t.getMessage());
            }
        } else {
            // write defaults
            props.setProperty("maxVeinSize", String.valueOf(INSTANCE.maxVeinSize));
            props.setProperty("maxVeinSizeCap", String.valueOf(INSTANCE.maxVeinSizeCap));
            props.setProperty("mergeDifferentOreTypes", String.valueOf(INSTANCE.mergeDifferentOreTypes));
            props.setProperty("autoCollectEnabled", String.valueOf(INSTANCE.autoCollectEnabled));
            props.setProperty("pickupRadius", String.valueOf(INSTANCE.pickupRadius));
            props.setProperty("extraOreBlocks", "");
            props.setProperty("debug", String.valueOf(INSTANCE.debug));

            try (OutputStream out = Files.newOutputStream(cfgFile)) {
                String header = "OrePicker config - edit values as needed\n" +
                        "# maxVeinSize: total blocks to break at once (including starting block)\n" +
                        "# maxVeinSizeCap: absolute hard cap (safety)\n" +
                        "# mergeDifferentOreTypes: true/false\n" +
                        "# autoCollectEnabled: true/false\n" +
                        "# pickupRadius: double (e.g. 1.5)\n" +
                        "# extraOreBlocks: comma-separated list of block ids (modid:block_name)\n" +
                        "# debug: true/false\n";
                props.store(out, header);
            } catch (Throwable t) {
                System.out.println("[OrePicker] Failed to write default config: " + t.getMessage());
            }
        }

        // Apply properties with safe parsing
        try {
            String s;
            s = props.getProperty("maxVeinSize");
            if (s != null) {
                try {
                    int v = Integer.parseInt(s.trim());
                    INSTANCE.maxVeinSize = Math.max(1, v);
                } catch (NumberFormatException ignored) {}
            }

            s = props.getProperty("maxVeinSizeCap");
            if (s != null) {
                try {
                    int v = Integer.parseInt(s.trim());
                    INSTANCE.maxVeinSizeCap = Math.max(1, v);
                } catch (NumberFormatException ignored) {}
            }

            // ensure cap >= size
            if (INSTANCE.maxVeinSize > INSTANCE.maxVeinSizeCap) {
                INSTANCE.maxVeinSize = Math.min(INSTANCE.maxVeinSize, INSTANCE.maxVeinSizeCap);
            }

            s = props.getProperty("mergeDifferentOreTypes");
            if (s != null) {
                INSTANCE.mergeDifferentOreTypes = Boolean.parseBoolean(s.trim());
            }

            s = props.getProperty("autoCollectEnabled");
            if (s != null) {
                INSTANCE.autoCollectEnabled = Boolean.parseBoolean(s.trim());
            }

            s = props.getProperty("pickupRadius");
            if (s != null) {
                try {
                    double d = Double.parseDouble(s.trim());
                    INSTANCE.pickupRadius = Math.max(0.0, d);
                } catch (NumberFormatException ignored) {}
            }

            s = props.getProperty("extraOreBlocks");
            if (s != null) {
                if (!s.trim().isEmpty()) {
                    INSTANCE.extraOreBlocks = Arrays.stream(s.split(","))
                            .map(String::trim)
                            .filter(x -> !x.isEmpty())
                            .collect(Collectors.toList());
                } else {
                    INSTANCE.extraOreBlocks = new java.util.ArrayList<>();
                }
            }

            s = props.getProperty("debug");
            if (s != null) {
                INSTANCE.debug = Boolean.parseBoolean(s.trim());
            }

            System.out.println("[OrePicker] ConfigManager: loaded config (maxVeinSize=" + INSTANCE.maxVeinSize
                    + ", maxVeinSizeCap=" + INSTANCE.maxVeinSizeCap
                    + ", mergeDifferentOreTypes=" + INSTANCE.mergeDifferentOreTypes
                    + ", autoCollectEnabled=" + INSTANCE.autoCollectEnabled
                    + ", pickupRadius=" + INSTANCE.pickupRadius
                    + ", extraOreBlocks=" + INSTANCE.extraOreBlocks.size()
                    + ", debug=" + INSTANCE.debug + ")");
        } catch (Throwable t) {
            System.out.println("[OrePicker] Error applying config, using defaults: " + t.getMessage());
        }
    }
}
