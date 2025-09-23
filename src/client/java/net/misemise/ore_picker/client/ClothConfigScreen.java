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
 * Cloth Config を用いた設定画面。
 *
 * - 言語オーバーライド（ConfigManager.languageOverride）をサポート（""=自動）
 * - extraOreBlocks をストリングリストで編集可能にする
 *
 * 注意:
 * - ConfigManager に public String languageOverride = ""; を追加してください（下記参照）。
 */
public class ClothConfigScreen {
    // 簡易辞書: override が入っている場合に UI テキストをこちらの辞書で返す
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
        en.put("text.ore_picker.config.lang_ja", "日本語 (ja_jp)");

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

        BUILTIN_LOCALES.put("en_us", en);
        BUILTIN_LOCALES.put("ja_jp", ja);
    }

    // トランスレータ：Config の override を見て適切に Text を返す
    private static Text tr(ConfigManager cfg, String key) {
        if (cfg != null && cfg.languageOverride != null && !cfg.languageOverride.isEmpty()) {
            String lang = cfg.languageOverride.toLowerCase(Locale.ROOT);
            Map<String, String> map = BUILTIN_LOCALES.get(lang);
            if (map != null && map.containsKey(key)) {
                return Text.literal(map.get(key));
            } else {
                // フォールバックは translatable（ゲーム言語）
                return Text.translatable(key);
            }
        }
        // デフォルトはゲームの言語に従う
        return Text.translatable(key);
    }

    public static void open(Screen parent) {
        try {
            // ConfigManager 初期化
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

            // --- language override (選択肢) ---
            List<String> langChoices = Arrays.asList("", "en_us", "ja_jp");
            Map<String, String> displayMap = new LinkedHashMap<>();
            displayMap.put("", tr(cfgMgr, "text.ore_picker.config.lang_empty").getString());
            displayMap.put("en_us", tr(cfgMgr, "text.ore_picker.config.lang_en").getString());
            displayMap.put("ja_jp", tr(cfgMgr, "text.ore_picker.config.lang_ja").getString());

            general.addEntry(entryBuilder
                    .startEnumSelector(tr(cfgMgr, "text.ore_picker.config.languageOverride"), String.class, cfgMgr.languageOverride == null ? "" : cfgMgr.languageOverride)
                    .setDefaultValue("")
                    .setEnumNameProvider(s -> displayMap.getOrDefault(s, s))
                    .setSaveConsumer((String v) -> cfgMgr.languageOverride = (v == null ? "" : v))
                    .build());

            // --- extraOreBlocks: List editor（Cloth のストリングリストエントリを使用） ---
            List<String> extraList = parseCommaSeparated(cfgMgr.extraOreBlocks);
            general.addEntry(entryBuilder
                    .startStrList(tr(cfgMgr, "text.ore_picker.config.extraOreBlocks"), extraList)
                    .setDefaultValue(Collections.emptyList())
                    .setSaveConsumer((List<String> v) -> {
                        // 保存時に ConfigManager.extraOreBlocks をカンマ区切り文字列として保存
                        if (v == null || v.isEmpty()) {
                            cfgMgr.extraOreBlocks = "";
                        } else {
                            String joined = String.join(", ", v);
                            cfgMgr.extraOreBlocks = joined;
                        }
                    })
                    .build());

            // 保存処理
            builder.setSavingRunnable(() -> {
                try {
                    cfgMgr.saveToFile();
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
