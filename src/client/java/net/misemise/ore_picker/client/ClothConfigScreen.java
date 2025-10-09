package net.misemise.ore_picker.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.text.Text;
import net.misemise.ore_picker.config.ConfigManager;

import java.util.*;

/**
 * Cloth Config screen (trimmed):
 * - removed "Add all from mod id" UI
 * - removed "requirePickaxeForVein" UI
 * - added enableHudOverlay and logToChat toggles
 */
public class ClothConfigScreen {
    private enum LanguageOption {
        AUTO(""),
        EN_US("en_us"),
        JA_JP("ja_jp");

        private final String code;
        LanguageOption(String code) { this.code = code; }
        public String code() { return code; }

        public static LanguageOption fromString(String s) {
            if (s == null || s.trim().isEmpty()) return AUTO;
            String v = s.toLowerCase(Locale.ROOT);
            for (LanguageOption o : values()) {
                if (o.code.equalsIgnoreCase(v)) return o;
            }
            return AUTO;
        }
    }

    private static final Map<String, Map<String, String>> BUILTIN_LOCALES = new HashMap<>();

    static {
        Map<String, String> en = new HashMap<>();
        en.put("text.ore_picker.config.title", "Ore Picker Settings");
        en.put("text.ore_picker.config.general", "General");
        en.put("text.ore_picker.config.maxVeinSize", "Max vein size");
        en.put("text.ore_picker.config.maxVeinSizeCap", "Max vein cap");
        en.put("text.ore_picker.config.autoCollectEnabled", "Enable auto-collect");
        en.put("text.ore_picker.config.pickupRadius", "Pickup radius");
        en.put("text.ore_picker.config.debug", "Enable debug logs");
        en.put("text.ore_picker.config.extraOreBlocks", "Extra ore blocks");
        en.put("text.ore_picker.config.languageOverride", "Language override (empty = follow game)");
        en.put("text.ore_picker.config.lang_empty", "Follow game language");
        en.put("text.ore_picker.config.lang_en", "English (en_us)");
        en.put("text.ore_picker.config.lang_ja", "Japanese (ja_jp)");
        en.put("text.ore_picker.config.detect_ores", "Detect common ores and add to list");
        en.put("text.ore_picker.config.add_by_id", "Add block ID to list (modid:block_name)");
        en.put("text.ore_picker.config.add_by_id_button", "Add by ID");
        en.put("text.ore_picker.config.enableHudOverlay", "Enable HUD overlay");
        en.put("text.ore_picker.config.logToChat", "Log vein breaks to chat");

        Map<String, String> ja = new HashMap<>();
        ja.put("text.ore_picker.config.title", "Ore Picker 設定");
        ja.put("text.ore_picker.config.general", "基本設定");
        ja.put("text.ore_picker.config.maxVeinSize", "一括破壊の最大数");
        ja.put("text.ore_picker.config.maxVeinSizeCap", "最大上限");
        ja.put("text.ore_picker.config.autoCollectEnabled", "自動回収を有効にする");
        ja.put("text.ore_picker.config.pickupRadius", "回収半径");
        ja.put("text.ore_picker.config.debug", "デバッグログを有効にする");
        ja.put("text.ore_picker.config.extraOreBlocks", "追加鉱石ブロック");
        ja.put("text.ore_picker.config.languageOverride", "言語（空 = ゲームに従う）");
        ja.put("text.ore_picker.config.lang_empty", "ゲーム設定に従う");
        ja.put("text.ore_picker.config.lang_en", "英語 (en_us)");
        ja.put("text.ore_picker.config.lang_ja", "日本語 (ja_jp)");
        ja.put("text.ore_picker.config.detect_ores", "よくある鉱石を検出して追加する");
        ja.put("text.ore_picker.config.add_by_id", "ブロックIDを追加 (modid:block_name)");
        ja.put("text.ore_picker.config.add_by_id_button", "IDで追加");
        ja.put("text.ore_picker.config.enableHudOverlay", "HUD 表示を有効にする");
        ja.put("text.ore_picker.config.logToChat", "一括破壊をチャットに記録する");

        BUILTIN_LOCALES.put("en_us", en);
        BUILTIN_LOCALES.put("ja_jp", ja);
    }

    private static Text tr(ConfigManager cfg, String key) {
        if (cfg != null && cfg.languageOverride != null && !cfg.languageOverride.isEmpty()) {
            String lang = cfg.languageOverride.toLowerCase(Locale.ROOT);
            Map<String, String> map = BUILTIN_LOCALES.get(lang);
            if (map != null && map.containsKey(key)) {
                return Text.literal(map.get(key));
            } else {
                return Text.translatable(key);
            }
        }
        return Text.translatable(key);
    }

    public static void open(Screen parent) {
        try {
            if (ConfigManager.INSTANCE == null) {
                try {
                    ConfigManager.load();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            if (ConfigManager.INSTANCE == null) {
                MinecraftClient.getInstance().setScreen(new ConfirmScreen(
                        (b) -> MinecraftClient.getInstance().setScreen(parent),
                        Text.translatable("text.ore_picker.config.failed_title"),
                        Text.translatable("text.ore_picker.config.failed_message")
                ));
                return;
            }

            final ConfigManager cfgMgr = ConfigManager.INSTANCE;

            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(tr(cfgMgr, "text.ore_picker.config.title"));
            ConfigEntryBuilder entryBuilder = builder.entryBuilder();

            ConfigCategory general = builder.getOrCreateCategory(tr(cfgMgr, "text.ore_picker.config.general"));

            // --- maxVeinSize ---
            general.addEntry(entryBuilder
                    .startIntField(tr(cfgMgr, "text.ore_picker.config.maxVeinSize"), cfgMgr.maxVeinSize)
                    .setDefaultValue(64)
                    .setSaveConsumer((Integer v) -> cfgMgr.maxVeinSize = v)
                    .build());

            // --- maxVeinSizeCap ---
            general.addEntry(entryBuilder
                    .startIntField(tr(cfgMgr, "text.ore_picker.config.maxVeinSizeCap"), cfgMgr.maxVeinSizeCap)
                    .setDefaultValue(512)
                    .setSaveConsumer((Integer v) -> cfgMgr.maxVeinSizeCap = v)
                    .build());

            // --- autoCollectEnabled ---
            general.addEntry(entryBuilder
                    .startBooleanToggle(tr(cfgMgr, "text.ore_picker.config.autoCollectEnabled"), cfgMgr.autoCollectEnabled)
                    .setSaveConsumer((Boolean v) -> cfgMgr.autoCollectEnabled = v)
                    .setDefaultValue(true)
                    .build());

            // --- pickupRadius ---
            general.addEntry(entryBuilder
                    .startDoubleField(tr(cfgMgr, "text.ore_picker.config.pickupRadius"), cfgMgr.pickupRadius)
                    .setDefaultValue(1.5d)
                    .setSaveConsumer((Double v) -> cfgMgr.pickupRadius = v)
                    .build());

            // --- debug ---
            general.addEntry(entryBuilder
                    .startBooleanToggle(tr(cfgMgr, "text.ore_picker.config.debug"), cfgMgr.debug)
                    .setSaveConsumer((Boolean v) -> cfgMgr.debug = v)
                    .setDefaultValue(false)
                    .build());

            // --- language override (enum-based selector) ---
            LanguageOption currentLangOpt = LanguageOption.fromString(cfgMgr.languageOverride);
            general.addEntry(entryBuilder
                    .startEnumSelector(tr(cfgMgr, "text.ore_picker.config.languageOverride"), LanguageOption.class, currentLangOpt)
                    .setDefaultValue(LanguageOption.AUTO)
                    .setEnumNameProvider(opt -> {
                        if (opt == null) return tr(cfgMgr, "text.ore_picker.config.lang_empty");
                        if (opt == LanguageOption.EN_US) return tr(cfgMgr, "text.ore_picker.config.lang_en");
                        if (opt == LanguageOption.JA_JP) return tr(cfgMgr, "text.ore_picker.config.lang_ja");
                        return tr(cfgMgr, "text.ore_picker.config.lang_empty");
                    })
                    .setSaveConsumer((LanguageOption v) -> cfgMgr.languageOverride = (v == null ? "" : v.code()))
                    .build());

            // --- extraOreBlocks: List editor ---
            List<String> extraList = parseCommaSeparated(cfgMgr.extraOreBlocks);
            general.addEntry(entryBuilder
                    .startStrList(tr(cfgMgr, "text.ore_picker.config.extraOreBlocks"), extraList)
                    .setDefaultValue(Collections.emptyList())
                    .setSaveConsumer((List<String> v) -> {
                        if (v == null || v.isEmpty()) {
                            cfgMgr.extraOreBlocks = "";
                        } else {
                            String joined = String.join(", ", v);
                            cfgMgr.extraOreBlocks = joined;
                        }
                    })
                    .build());

            // --- Detect common ores (action) ---
            general.addEntry(entryBuilder
                    .startBooleanToggle(tr(cfgMgr, "text.ore_picker.config.detect_ores"), false)
                    .setSaveConsumer((Boolean v) -> {
                        if (v != null && v) {
                            List<String> detected = detectCommonOreIds();
                            List<String> existing = parseCommaSeparated(cfgMgr.extraOreBlocks);
                            Set<String> merged = new LinkedHashSet<>(existing);
                            merged.addAll(detected);
                            cfgMgr.extraOreBlocks = String.join(", ", merged);
                        }
                    })
                    .build());

            // --- Add by ID (text field) ---
            general.addEntry(entryBuilder
                    .startTextField(tr(cfgMgr, "text.ore_picker.config.add_by_id"), cfgMgr.tempAddById == null ? "" : cfgMgr.tempAddById)
                    .setDefaultValue("")
                    .setSaveConsumer(s -> {
                        try {
                            cfgMgr.tempAddById = (s == null ? "" : s);
                        } catch (Throwable ignored) {}
                    })
                    .build());

            // --- HUD & chat log toggles ---
            general.addEntry(entryBuilder
                    .startBooleanToggle(tr(cfgMgr, "text.ore_picker.config.enableHudOverlay"), cfgMgr.enableHudOverlay)
                    .setSaveConsumer((Boolean v) -> cfgMgr.enableHudOverlay = (v != null ? v : true))
                    .setDefaultValue(true)
                    .build());

            general.addEntry(entryBuilder
                    .startBooleanToggle(tr(cfgMgr, "text.ore_picker.config.logToChat"), cfgMgr.logToChat)
                    .setSaveConsumer((Boolean v) -> cfgMgr.logToChat = (v != null ? v : false))
                    .setDefaultValue(false)
                    .build());


            // --- Outline overlay enable ---
            general.addEntry(entryBuilder
                    .startBooleanToggle(tr(cfgMgr, "text.ore_picker.config.enableOutlineOverlay"), cfgMgr.enableOutlineOverlay)
                    .setSaveConsumer((Boolean v) -> cfgMgr.enableOutlineOverlay = (v != null ? v : false))
                    .setDefaultValue(false)
                    .build());

            // --- Outline radius ---
            general.addEntry(entryBuilder
                    .startIntField(tr(cfgMgr, "text.ore_picker.config.outlineRadius"), cfgMgr.outlineRadius)
                    .setDefaultValue(16)
                    .setSaveConsumer((Integer v) -> {
                        if (v == null) v = 16;
                        if (v < 1) v = 1;
                        if (v > 64) v = 64; // 安全上限
                        cfgMgr.outlineRadius = v;
                    })
                    .build());


            // Save-time processing: if tempAddById present, add
            builder.setSavingRunnable(() -> {
                try {
                    try {
                        if (cfgMgr.tempAddById != null && !cfgMgr.tempAddById.trim().isEmpty()) {
                            String entered = cfgMgr.tempAddById.trim();
                            if (!entered.contains(":")) entered = "minecraft:" + entered;
                            List<String> existing = parseCommaSeparated(cfgMgr.extraOreBlocks);
                            Set<String> merged = new LinkedHashSet<>(existing);
                            merged.add(entered);
                            cfgMgr.extraOreBlocks = String.join(", ", merged);
                            cfgMgr.tempAddById = "";
                        }
                    } catch (Throwable ignored) {}

                    // finally save config
                    try { cfgMgr.saveToFile(); } catch (Throwable e) { e.printStackTrace(); }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            });

            MinecraftClient.getInstance().setScreen(builder.build());
        } catch (Throwable t) {
            System.err.println("[OrePicker] Failed to open Cloth Config screen (is cloth-config present?).");
            t.printStackTrace();
        }
    }

    private static List<String> detectCommonOreIds() {
        String[] ids = {
                "minecraft:coal_ore",
                "minecraft:iron_ore",
                "minecraft:copper_ore",
                "minecraft:gold_ore",
                "minecraft:diamond_ore",
                "minecraft:lapis_ore",
                "minecraft:nether_quartz_ore",
                "minecraft:redstone_ore",
                "minecraft:emerald_ore",
                "minecraft:deepslate_coal_ore",
                "minecraft:deepslate_iron_ore",
                "minecraft:deepslate_copper_ore",
                "minecraft:deepslate_gold_ore",
                "minecraft:deepslate_diamond_ore",
                "minecraft:deepslate_lapis_ore",
                "minecraft:deepslate_redstone_ore",
                "minecraft:deepslate_emerald_ore"
        };
        List<String> out = new ArrayList<>();
        for (String s : ids) out.add(s);
        return out;
    }

    private static List<String> parseCommaSeparated(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return out;
        String[] parts = s.split(",");
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
