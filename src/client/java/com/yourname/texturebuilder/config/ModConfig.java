package com.yourname.texturebuilder.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.util.GradientHelper;

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
    /** Starting weight for every slot, and what the config screen's Reset button restores. */
    public static final int DEFAULT_WEIGHT = 10;

    /** Gradient Transition slider bounds, in percent. */
    public static final int TRANSITION_MIN = 0;
    public static final int TRANSITION_MAX = 100;

    /** How each placement's hotbar slot is chosen while Texture Builder is ON. */
    public enum Mode {
        /** Weighted random draw from the slot pool (the original behaviour). */
        TEXTURE("texture"),
        /** The placed block's position between the gradient points picks a palette entry. */
        GRADIENT("gradient");

        private final String id;

        Mode(String id) {
            this.id = id;
        }

        /** The value written to and read from the TOML. */
        public String id() {
            return id;
        }

        static Mode fromId(String value) {
            if ("random".equalsIgnoreCase(value)) {
                return TEXTURE; // Pre-rename name for Texture mode.
            }
            for (Mode m : values()) {
                if (m.id.equalsIgnoreCase(value)) {
                    return m;
                }
            }
            throw new IllegalArgumentException(value);
        }
    }

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
    /**
     * Restore the player's previously-selected hotbar slot after each placement — the original
     * SRS §1 / FR-12 / NFR-06 behaviour. Defaults to {@code false} so the chosen slot stays
     * selected and the hotbar visibly shows which block was just placed (SRS §1 explicitly allows
     * this assumption to be revisited). Set {@code true} to keep the selection frozen between
     * placements instead.
     */
    public boolean restoreSlotAfterPlacement = false;
    /** Show normalized effective percentages, not just raw weights, in the config screen (FR-09). */
    public boolean autoNormalizeDisplay = true;
    /** How long the disappearing "no more blocks found" message stays visible (FR-15). Range 500–5000. */
    public int restockMessageDurationMs = 2000;
    /** Write verbose slot-selection and restock diagnostics to the Fabric log. Development use only. */
    public boolean debugLogging = false;

    /** Slot-selection mode. Changed only by the Mode button on the config screens. */
    public Mode mode = Mode.TEXTURE;
    /**
     * Gradient-mode pool membership per hotbar slot, kept separate from Texture mode's
     * {@link #included}. The included slots, in hotbar order (left to right), are the gradient's
     * blocks from the start point to the end point; see {@link #gradientPalette()}.
     */
    public boolean[] gradientIncluded = defaultGradientIncluded();
    /**
     * The N-1 boundaries between consecutive palette entries, as fractions (0-1) of the start→end
     * line, ascending and at least {@link GradientHelper#MIN_GAP} apart.
     */
    public double[] gradientBoundaries = GradientHelper.evenBoundaries(2);
    /** Blend-zone width around each boundary: 0 (hard bands) to 100 (full fade), percent. */
    public int gradientTransition = 50;
    /**
     * Gradient shape: 1 = along one axis (the longest), 2 = across the two axes that differ most,
     * 3 = across all three. Cycled with the 1D/2D/3D button on the config screen.
     */
    public int gradientShape = 1;
    /** Gradient start block {x, y, z}, or {@code null} when unset. */
    public int[] gradientStart = null;
    /** Gradient end block {x, y, z}, or {@code null} when unset. */
    public int[] gradientEnd = null;
    /** Dimension id the points were set in (e.g. {@code minecraft:overworld}); empty when unset. */
    public String gradientDimension = "";

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

    /** Gradient default: slots 1 and 2 (a two-block fade). */
    private static boolean[] defaultGradientIncluded() {
        boolean[] inc = new boolean[TextureBuilder.HOTBAR_SIZE];
        inc[0] = true;
        inc[1] = true;
        return inc;
    }

    private static int[] defaultWeights() {
        int[] w = new int[TextureBuilder.HOTBAR_SIZE];
        java.util.Arrays.fill(w, DEFAULT_WEIGHT);
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
        cfg.sanitizeGradient();
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
                case "restore_slot_after_placement" -> cfg.restoreSlotAfterPlacement = Boolean.parseBoolean(value);
                case "auto_normalize_display" -> cfg.autoNormalizeDisplay = Boolean.parseBoolean(value);
                case "restock_message_duration_ms" -> cfg.restockMessageDurationMs = Integer.parseInt(value);
                case "debug_logging" -> cfg.debugLogging = Boolean.parseBoolean(value);
                case "mode" -> cfg.mode = Mode.fromId(value);
                case "gradient_included" -> cfg.gradientIncluded = parseSlotSet(value);
                case "gradient_boundaries" -> cfg.gradientBoundaries = parseDoubleArray(value);
                case "gradient_transition" -> cfg.gradientTransition = Integer.parseInt(value);
                case "gradient_shape" -> cfg.gradientShape = parseShape(value);
                case "gradient_start" -> cfg.gradientStart = parsePoint(value);
                case "gradient_end" -> cfg.gradientEnd = parsePoint(value);
                case "gradient_dimension" -> cfg.gradientDimension = value;
                default -> TextureBuilder.LOGGER.warn("[TextureBuilder] Unknown config key '{}' ignored", key);
            }
        } catch (IllegalArgumentException e) {
            TextureBuilder.LOGGER.warn("[TextureBuilder] Invalid value '{}' for config key '{}'; keeping default",
                    value, key);
        }
    }

    /** Splits {@code [a, b, c]} into trimmed elements; {@code []} gives an empty array. */
    private static String[] arrayElements(String value) {
        String inner = value.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        inner = inner.trim();
        if (inner.isEmpty()) {
            return new String[0];
        }
        String[] parts = inner.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static int[] parseIntArray(String value) {
        String[] parts = arrayElements(value);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }

    private static double[] parseDoubleArray(String value) {
        String[] parts = arrayElements(value);
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i]);
        }
        return out;
    }

    /** {@code [0, 3, 4]} (slot indices 0-8) gives per-slot flags; out-of-range indices are ignored. */
    private static boolean[] parseSlotSet(String value) {
        boolean[] set = new boolean[TextureBuilder.HOTBAR_SIZE];
        for (int slot : parseIntArray(value)) {
            if (slot >= 0 && slot < TextureBuilder.HOTBAR_SIZE) {
                set[slot] = true;
            }
        }
        return set;
    }

    /** {@code "1d"}/{@code "2d"}/{@code "3d"} (or a bare 1-3) to the shape number. */
    private static int parseShape(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.endsWith("d")) {
            v = v.substring(0, v.length() - 1);
        }
        int shape = Integer.parseInt(v);
        if (shape < 1 || shape > 3) {
            throw new IllegalArgumentException(value);
        }
        return shape;
    }

    /** {@code [x, y, z]} gives a point; {@code []} gives {@code null} (unset). */
    private static int[] parsePoint(String value) {
        int[] p = parseIntArray(value);
        if (p.length == 0) {
            return null;
        }
        if (p.length != 3) {
            throw new IllegalArgumentException(value);
        }
        return p;
    }

    /**
     * Brings the gradient fields back into range after loading: boundaries that match the number
     * of included slots (else spread evenly), transition 0-100, and no end point without a start
     * point.
     */
    private void sanitizeGradient() {
        int entries = gradientPalette().length;
        if (entries < GradientHelper.MIN_ENTRIES) {
            gradientBoundaries = new double[0];
        } else if (!GradientHelper.isValid(gradientBoundaries, entries)) {
            gradientBoundaries = GradientHelper.evenBoundaries(entries);
        }
        gradientTransition = Math.max(TRANSITION_MIN, Math.min(TRANSITION_MAX, gradientTransition));
        gradientShape = Math.max(1, Math.min(3, gradientShape));
        if (gradientStart == null) {
            gradientEnd = null;
        }
        if (gradientDimension == null) {
            gradientDimension = "";
        }
    }

    /**
     * The gradient's blocks in order from the start point to the end point: the hotbar slots
     * (0-8) included for Gradient mode, left to right. Fewer than 2 means no usable gradient.
     */
    public int[] gradientPalette() {
        int count = 0;
        for (boolean inc : gradientIncluded) {
            if (inc) {
                count++;
            }
        }
        int[] palette = new int[count];
        int n = 0;
        for (int slot = 0; slot < TextureBuilder.HOTBAR_SIZE; slot++) {
            if (gradientIncluded[slot]) {
                palette[n++] = slot;
            }
        }
        return palette;
    }

    /**
     * Includes or excludes one slot in the gradient. The number of blocks changes, so the
     * distribution handles are redistributed evenly.
     */
    public void setGradientIncluded(int slot, boolean value) {
        if (gradientIncluded[slot] == value) {
            return;
        }
        gradientIncluded[slot] = value;
        int entries = gradientPalette().length;
        gradientBoundaries = entries < GradientHelper.MIN_ENTRIES
                ? new double[0] : GradientHelper.evenBoundaries(entries);
    }

    /** Clears both gradient points and their dimension. Does not write the file. */
    public void clearGradientPoints() {
        gradientStart = null;
        gradientEnd = null;
        gradientDimension = "";
    }

    /** {@code [x, y, z]}, or {@code []} for an unset point. */
    public static String formatPoint(int[] p) {
        return p == null ? "[]" : "[" + p[0] + ", " + p[1] + ", " + p[2] + "]";
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

        int[] gradientSlots = gradientPalette();
        StringBuilder palette = new StringBuilder("[");
        for (int i = 0; i < gradientSlots.length; i++) {
            palette.append(i > 0 ? ", " : "").append(gradientSlots[i]);
        }
        palette.append(']');
        StringBuilder bounds = new StringBuilder("[");
        for (int i = 0; i < gradientBoundaries.length; i++) {
            bounds.append(i > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.4f", gradientBoundaries[i]));
        }
        bounds.append(']');

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

                # Restore your previously-selected hotbar slot after each placement.
                # false (default): the randomly-chosen slot stays selected, so the hotbar always
                #   shows which block was just placed.
                # true: the visible selection stays frozen on the slot you picked manually.
                restore_slot_after_placement = %s

                # Show normalized effective percentages (not just raw weights) in the config screen.
                auto_normalize_display = %s

                # How long the disappearing "no more blocks found" message stays visible. Range 500-5000.
                restock_message_duration_ms = %d

                # Write verbose slot-selection and restock diagnostics to the Fabric log. Development use only.
                debug_logging = %s

                # How the hotbar slot is chosen while Texture Builder is ON: "texture" (weighted
                # random pick from the pool above) or "gradient" (the block's position between the
                # gradient points). Changed with the Mode button in the config screen.
                mode = "%s"

                # Hotbar slots (0-8) included in the gradient, separate from the texture pool.
                # They run from the start point to the end point in hotbar order (left to right).
                # At least 2 are needed; with fewer, Gradient mode blocks placement with a warning.
                gradient_included = %s

                # Boundaries between consecutive gradient blocks, as fractions (0-1) of the
                # start-to-end line. One fewer than the included slots, ascending.
                gradient_boundaries = %s

                # Blend width around each boundary, 0-100. 0 = hard bands, 100 = each block fades
                # fully into the next.
                gradient_transition = %d

                # Gradient shape: "1d" fades along the one axis the points differ on most; "2d"
                # fades diagonally across the two axes that differ most (the smallest is ignored);
                # "3d" fades diagonally across all three. Changed with the 1D/2D/3D button.
                gradient_shape = "%dd"

                # Gradient points as [x, y, z] ([] = unset) and the dimension they belong to.
                # Set with the "Set Gradient Point" keybind. Ignored in other dimensions.
                gradient_start = %s
                gradient_end = %s
                gradient_dimension = "%s"
                """.formatted(enabled, toggleKeybind, slots, restoreSlotAfterPlacement,
                autoNormalizeDisplay, restockMessageDurationMs, debugLogging,
                mode.id(), palette, bounds, gradientTransition, gradientShape,
                formatPoint(gradientStart), formatPoint(gradientEnd), gradientDimension);
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
        lines.add("restore_slot_after_placement = " + restoreSlotAfterPlacement);
        lines.add("auto_normalize_display = " + autoNormalizeDisplay);
        lines.add("restock_message_duration_ms = " + restockMessageDurationMs);
        lines.add("debug_logging = " + debugLogging);
        lines.add("mode = " + mode.id());
        int[] gradientSlots = gradientPalette();
        StringBuilder pal = new StringBuilder();
        for (int i = 0; i < gradientSlots.length; i++) {
            if (i > 0) {
                pal.append(" -> ");
            }
            pal.append("slot ").append(gradientSlots[i] + 1);
            if (gradientSlots.length >= GradientHelper.MIN_ENTRIES) {
                pal.append(String.format(Locale.ROOT, " (%.0f%%)",
                        GradientHelper.segmentShare(gradientBoundaries, i) * 100.0D));
            }
        }
        lines.add("gradient slots = " + (pal.isEmpty() ? "none" : pal)
                + (gradientSlots.length < GradientHelper.MIN_ENTRIES ? " (need at least 2)" : ""));
        lines.add("gradient_transition = " + gradientTransition + "%");
        lines.add("gradient_shape = " + gradientShape + "D");
        return lines;
    }
}
