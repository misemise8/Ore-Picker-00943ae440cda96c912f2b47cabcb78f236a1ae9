package net.misemise.ore_picker.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * ConfigManager
 * - properties ベースの設定管理
 * - 起動時に config/orepicker.properties を作成/読み込み
 * - ファイル変更を WatchService で監視し、変更時に自動リロード
 */
public final class ConfigManager {
    public static volatile ConfigManager INSTANCE = null;

    public int maxVeinSize = 64;
    public int maxVeinSizeCap = 512;
    public boolean mergeDifferentOreTypes = false;
    public boolean autoCollectEnabled = true;
    public double pickupRadius = 1.5d;
    public String extraOreBlocks = "";
    public boolean debug = false;

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_NAME = "orepicker.properties";

    private final Path configDirPath;
    private final Path configFilePath;

    private WatchService watchService = null;
    private Thread watcherThread = null;
    private volatile boolean watching = false;

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // 既存: 空文字列 = ゲームに従う
    public String languageOverride = "";

    // UI の一時保持フィールド（Cloth 画面で使う）
    public String tempAddById = "";
    public String tempAddByMod = "";

    // 動作制御
    public boolean requirePickaxeForVein = true;
    public boolean applyInCreative = false;

    // HUD とチャットログの制御（追加項目）
    // HUD を表示するか（押下ホールド中に表示するか） - default true
    public boolean enableHudOverlay = true;
    // 一括破壊時にチャットへログを出すか（default false）
    public boolean logToChat = false;

    private ConfigManager() {
        configDirPath = Paths.get(CONFIG_DIR);
        configFilePath = configDirPath.resolve(CONFIG_NAME);
    }

    public static synchronized void load() {
        if (INSTANCE == null) {
            INSTANCE = new ConfigManager();
            INSTANCE.ensureConfigExistsAndLoad();
            INSTANCE.startWatcher();
        } else {
            INSTANCE.reloadFromFile();
        }
    }

    private void ensureConfigExistsAndLoad() {
        try {
            if (Files.notExists(configDirPath)) {
                Files.createDirectories(configDirPath);
            }
        } catch (IOException e) {
            System.err.println("[OrePicker] Failed to create config directory: " + e.getMessage());
        }

        if (Files.notExists(configFilePath)) {
            saveToFile();
            System.out.println("[OrePicker] ConfigManager: created default config at " + configFilePath.toString());
        }

        reloadFromFile();
    }

    public synchronized void reloadFromFile() {
        Properties p = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(configFilePath, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (NoSuchFileException nsf) {
            saveToFile();
            System.out.println("[OrePicker] ConfigManager: config file not found, recreated default.");
            return;
        } catch (IOException ioe) {
            System.err.println("[OrePicker] ConfigManager: failed to read config: " + ioe.getMessage());
            return;
        }

        this.maxVeinSize = parseInt(p.getProperty("maxVeinSize"), this.maxVeinSize);
        this.maxVeinSizeCap = parseInt(p.getProperty("maxVeinSizeCap"), this.maxVeinSizeCap);
        this.mergeDifferentOreTypes = parseBoolean(p.getProperty("mergeDifferentOreTypes"), this.mergeDifferentOreTypes);
        this.autoCollectEnabled = parseBoolean(p.getProperty("autoCollectEnabled"), this.autoCollectEnabled);
        this.pickupRadius = parseDouble(p.getProperty("pickupRadius"), this.pickupRadius);
        this.extraOreBlocks = p.getProperty("extraOreBlocks", this.extraOreBlocks);
        this.debug = parseBoolean(p.getProperty("debug"), this.debug);

        // 新しい設定項目を読み込む
        this.languageOverride = p.getProperty("languageOverride", this.languageOverride);
        this.requirePickaxeForVein = parseBoolean(p.getProperty("requirePickaxeForVein"), this.requirePickaxeForVein);
        this.applyInCreative = parseBoolean(p.getProperty("applyInCreative"), this.applyInCreative);

        this.enableHudOverlay = parseBoolean(p.getProperty("enableHudOverlay"), this.enableHudOverlay);
        this.logToChat = parseBoolean(p.getProperty("logToChat"), this.logToChat);

        System.out.println("[OrePicker] ConfigManager: reloaded config (maxVeinSize=" + this.maxVeinSize
                + ", maxVeinSizeCap=" + this.maxVeinSizeCap
                + ", autoCollectEnabled=" + this.autoCollectEnabled
                + ", pickupRadius=" + this.pickupRadius
                + ", debug=" + this.debug
                + ", languageOverride=" + this.languageOverride
                + ", requirePickaxeForVein=" + this.requirePickaxeForVein
                + ", applyInCreative=" + this.applyInCreative
                + ", enableHudOverlay=" + this.enableHudOverlay
                + ", logToChat=" + this.logToChat
                + ")");

        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void saveToFile() {
        Properties p = new Properties();
        p.setProperty("maxVeinSize", Integer.toString(this.maxVeinSize));
        p.setProperty("maxVeinSizeCap", Integer.toString(this.maxVeinSizeCap));
        p.setProperty("mergeDifferentOreTypes", Boolean.toString(this.mergeDifferentOreTypes));
        p.setProperty("autoCollectEnabled", Boolean.toString(this.autoCollectEnabled));
        p.setProperty("pickupRadius", Double.toString(this.pickupRadius));
        p.setProperty("extraOreBlocks", this.extraOreBlocks == null ? "" : this.extraOreBlocks);
        p.setProperty("debug", Boolean.toString(this.debug));

        // 新しく永続化する項目
        p.setProperty("languageOverride", this.languageOverride == null ? "" : this.languageOverride);
        p.setProperty("requirePickaxeForVein", Boolean.toString(this.requirePickaxeForVein));
        p.setProperty("applyInCreative", Boolean.toString(this.applyInCreative));
        p.setProperty("enableHudOverlay", Boolean.toString(this.enableHudOverlay));
        p.setProperty("logToChat", Boolean.toString(this.logToChat));

        try (BufferedWriter writer = Files.newBufferedWriter(configFilePath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            p.store(writer, "OrePicker configuration");
        } catch (IOException e) {
            System.err.println("[OrePicker] ConfigManager: failed to write config file: " + e.getMessage());
        }
    }

    private void startWatcher() {
        if (watching) return;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            configDirPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException e) {
            System.err.println("[OrePicker] ConfigManager: cannot start watcher: " + e.getMessage());
            return;
        }

        watching = true;
        watcherThread = new Thread(() -> {
            try {
                while (watching) {
                    WatchKey key = watchService.poll(750, TimeUnit.MILLISECONDS);
                    if (key == null) continue;
                    List<WatchEvent<?>> events = key.pollEvents();
                    boolean reloadNeeded = false;
                    for (WatchEvent<?> ev : events) {
                        WatchEvent.Kind<?> kind = ev.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                        Object ctx = ev.context();
                        if (!(ctx instanceof Path)) {
                            String name = ctx.toString();
                            if (CONFIG_NAME.equals(name)) reloadNeeded = true;
                            continue;
                        }
                        Path changed = (Path) ctx;
                        if (CONFIG_NAME.equals(changed.getFileName().toString())) {
                            reloadNeeded = true;
                        }
                    }
                    if (reloadNeeded) {
                        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                        try { reloadFromFile(); } catch (Throwable t) { System.err.println("[OrePicker] ConfigManager: error reloading config: " + t.getMessage()); }
                    }
                    boolean valid = key.reset();
                    if (!valid) break;
                }
            } catch (InterruptedException ie) {
            } finally {
                try { if (watchService != null) watchService.close(); } catch (IOException ignored) {}
            }
        }, "orepicker-config-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        System.out.println("[OrePicker] ConfigManager: started file watcher for " + configFilePath.toString());
    }

    public void stopWatcher() {
        watching = false;
        if (watcherThread != null) watcherThread.interrupt();
        try { if (watchService != null) watchService.close(); } catch (IOException ignored) {}
    }

    private int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
    private double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
    private boolean parseBoolean(String s, boolean fallback) {
        if (s == null) return fallback;
        String v = s.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) return true;
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) return false;
        return fallback;
    }

    public void addChangeListener(Runnable r) {
        if (r == null) return;
        listeners.addIfAbsent(r);
    }

    public void removeChangeListener(Runnable r) {
        if (r == null) return;
        listeners.remove(r);
    }
}
