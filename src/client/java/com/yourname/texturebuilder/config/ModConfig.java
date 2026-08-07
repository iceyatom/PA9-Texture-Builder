package com.yourname.texturebuilder.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.yourname.texturebuilder.TextureBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * TextureBuilder configuration, backed by {@code config/texturebuilder.toml} (FR-20, SRS §6).
 *
 * <p>Only a flat {@code key = value} subset of TOML is used (plus one inline-table array for the
 * per-slot pool), so the file is parsed and written by hand rather than pulling in a TOML library —
 * the same approach as the author's ContainerSort/ShulkerPickBlock configs. The file is created
 * with defaults on first launch and can be reloaded at runtime with
 * {@code /texturebuilder reload} (FR-21).
 */
public final class ModConfig {
    private static final String FILE_NAME = "texturebuilder.toml";

    /** One inline table per hotbar slot inside the {@code slots} array, e.g. {@code {index=0, included=true, weight=10}}. */
    private static final Pattern SLOT_TABLE = Pattern.compile("\\{([^}]*)}");
    private static final Pattern SLOT_FIELD = Pattern.compile("(index|included|weight)\\s*=\\s*([A-Za-z0-9_-]+)");

    public static final int MESSAGE_DURATION_MIN_MS = 500;
    public static final int MESSAGE_DURATION_MAX_MS = 5000;

    /**
     * Per-slot weight bounds. The SRS calls these "weight (percentage)" values (FR-07) and frames
     * the running total around 100 (FR-09), so a slot's weight is capped at 100 — which is also
     * what makes the config screen's weight sliders a bounded, 1-unit-per-pixel control. Only the
     * <em>ratios</em> between weights matter, because selection normalizes proportionally at
     * runtime (FR-11), so the cap costs no expressiveness for any realistic blend.
     */
    public static final int WEIGHT_MIN = 0;
    public static final int WEIGHT_MAX = 100;

    private static ModConfig instance = new ModConfig();

    /** Master toggle at launch — mirrors the in-game hotkey state (FR-03). */
    public boolean enabled = false;
    /**
     * Key name for the ON/OFF hotkey (FR-01), e.g. {@code key.keyboard.b}, or {@code unbound}.
     * Applied to the registered {@link net.minecraft.client.KeyMapping} at load; once registered
     * the key is also rebindable through the vanilla Controls screen (SRS §6), whose options.txt
     * entry takes precedence for the rest of the session.
     */
    public String toggleKeybind = "unbound";
    /** Per-slot pool membership (FR-07/FR-08). Index 0–8 = hotbar left to right. */
    public boolean[] included = defaultIncluded();
    /** Per-slot raw weight (FR-07). Normalized proportionally at selection time (FR-11). */
    public int[] weights = defaultWeights();
    /** Show normalized effective percentages, not just raw weights, in the config screen (FR-09). */
    public boolean autoNormalizeDisplay = true;
    /** How long the disappearing "no more blocks found" message stays visible (FR-15). Range 500–5000. */
    public int restockMessageDurationMs = 2000;
    /** Write verbose slot-selection and restock diagnostics to the Fabric log. Development use only. */
    public boolean debugLogging = false;

    private ModConfig() {
    }

    public static ModConfig get() {
        return instance;
    }

    private static boolean[] defaultIncluded() {
        boolean[] inc = new boolean[TextureBuilder.HOTBAR_SIZE];
        java.util.Arrays.fill(inc, true);
        return inc;
    }

    private static int[] defaultWeights() {
        int[] w = new int[TextureBuilder.HOTBAR_SIZE];
        java.util.Arrays.fill(w, 10);
        return w;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** The message duration expressed in client ticks (20 ticks/second). */
    public int restockMessageDurationTicks() {
        return Math.max(1, restockMessageDurationMs / 50);
    }

    /** Loads the config from disk, creating the file with defaults if it does not exist. */
    public static void load() {
        Path path = configPath();
        ModConfig cfg = new ModConfig();
        if (Files.exists(path)) {
            try {
                for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = line.substring(0, eq).trim();
                    String value = stripComment(line.substring(eq + 1).trim());
                    apply(cfg, key, value);
                }
            } catch (IOException e) {
                TextureBuilder.LOGGER.warn("[TextureBuilder] Failed to read {}; using defaults", path, e);
            }
        }
        cfg.restockMessageDurationMs = Math.max(MESSAGE_DURATION_MIN_MS,
                Math.min(MESSAGE_DURATION_MAX_MS, cfg.restockMessageDurationMs));
        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            cfg.weights[i] = Math.max(WEIGHT_MIN, Math.min(WEIGHT_MAX, cfg.weights[i]));
        }
        instance = cfg;
        // Always rewrite so new options gain their documented defaults after an update.
        cfg.save();
    }

    private static String stripComment(String value) {
        // The slots array contains no '#' or quotes, and no other value is quoted, so a plain
        // comment cut is safe for every key this file can contain.
        boolean quoted = value.startsWith("\"");
        if (!quoted) {
            int hash = value.indexOf('#');
            if (hash >= 0) {
                value = value.substring(0, hash).trim();
            }
            return value;
        }
        int close = value.indexOf('"', 1);
        return close > 0 ? value.substring(1, close) : value.substring(1);
    }

    private static void apply(ModConfig cfg, String key, String value) {
        try {
            switch (key) {
                case "enabled" -> cfg.enabled = Boolean.parseBoolean(value);
                case "toggle_keybind" -> cfg.toggleKeybind = value.isEmpty() ? "unbound" : value;
                case "slots" -> parseSlots(cfg, value);
                case "auto_normalize_display" -> cfg.autoNormalizeDisplay = Boolean.parseBoolean(value);
                case "restock_message_duration_ms" -> cfg.restockMessageDurationMs = Integer.parseInt(value);
                case "debug_logging" -> cfg.debugLogging = Boolean.parseBoolean(value);
                default -> TextureBuilder.LOGGER.warn("[TextureBuilder] Unknown config key '{}' ignored", key);
            }
        } catch (IllegalArgumentException e) {
            TextureBuilder.LOGGER.warn("[TextureBuilder] Invalid value '{}' for config key '{}'; keeping default",
                    value, key);
        }
    }

    /** Parses {@code slots = [ {index=0, included=true, weight=10}, ... ]} (SRS §6). */
    private static void parseSlots(ModConfig cfg, String value) {
        Matcher table = SLOT_TABLE.matcher(value);
        while (table.find()) {
            int index = -1;
            Boolean inc = null;
            Integer weight = null;
            Matcher field = SLOT_FIELD.matcher(table.group(1));
            while (field.find()) {
                switch (field.group(1)) {
                    case "index" -> index = Integer.parseInt(field.group(2));
                    case "included" -> inc = Boolean.parseBoolean(field.group(2));
                    case "weight" -> weight = Integer.parseInt(field.group(2));
                }
            }
            if (index >= 0 && index < TextureBuilder.HOTBAR_SIZE) {
                if (inc != null) {
                    cfg.included[index] = inc;
                }
                if (weight != null) {
                    cfg.weights[index] = weight;
                }
            }
        }
    }

    /** Writes the current values (with documentation comments) back to disk. */
    public void save() {
        StringBuilder slots = new StringBuilder("slots = [ ");
        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            if (i > 0) {
                slots.append(", ");
            }
            slots.append("{index=").append(i)
                    .append(", included=").append(included[i])
                    .append(", weight=").append(weights[i]).append('}');
        }
        slots.append(" ]");

        String content = """
                # PA9 TextureBuilder configuration
                # Reload in-game with: /texturebuilder reload

                # Master toggle at launch - mirrors the in-game hotkey state. The mod never
                # silently re-enables itself on a later launch unless this is true (FR-03).
                enabled = %s

                # Key name for the ON/OFF hotkey (e.g. key.keyboard.b), or "unbound".
                # Also rebindable via the vanilla Controls screen once registered.
                toggle_keybind = %s

                # One entry per hotbar slot: {index, included, weight}. Only included slots
                # participate in weighted random selection; weights are normalized
                # proportionally at runtime, so they do not need to sum to 100.
                # Each weight is a percentage in the range 0-100 (values outside are clamped).
                %s

                # Show normalized effective percentages (not just raw weights) in the config screen.
                auto_normalize_display = %s

                # How long the disappearing "no more blocks found" message stays visible. Range 500-5000.
                restock_message_duration_ms = %d

                # Write verbose slot-selection and restock diagnostics to the Fabric log. Development use only.
                debug_logging = %s
                """.formatted(enabled, toggleKeybind, slots, autoNormalizeDisplay,
                restockMessageDurationMs, debugLogging);
        try {
            Files.createDirectories(configPath().getParent());
            Files.writeString(configPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            TextureBuilder.LOGGER.warn("[TextureBuilder] Failed to write {}", configPath(), e);
        }
    }

    /** Human-readable summary for {@code /texturebuilder status}. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("enabled (at launch) = " + enabled);
        lines.add("toggle_keybind = " + toggleKeybind);
        StringBuilder pool = new StringBuilder();
        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            if (included[i]) {
                if (!pool.isEmpty()) {
                    pool.append(", ");
                }
                pool.append(i + 1).append(':').append(weights[i]);
            }
        }
        lines.add("included slots (slot:weight) = " + (pool.isEmpty() ? "none" : pool));
        lines.add("auto_normalize_display = " + autoNormalizeDisplay);
        lines.add("restock_message_duration_ms = " + restockMessageDurationMs);
        lines.add("debug_logging = " + debugLogging);
        return lines;
    }
}
