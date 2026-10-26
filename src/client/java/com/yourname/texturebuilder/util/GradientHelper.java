package com.yourname.texturebuilder.util;

/**
 * Gradient-mode math: axis choice, position along the axis, and which palette entry a position
 * draws. Isolated from Minecraft classes like {@link TextureBuilderHelper} so it can be verified
 * standalone. Pure Java, no game dependencies.
 *
 * <h2>Model</h2>
 * The start→end line is normalized to {@code t} in [0, 1]. A palette of N entries splits that line
 * into N consecutive segments by N-1 <em>boundaries</em> ({@code 0 < b[0] < … < b[N-2] < 1}), so
 * entry {@code i} owns {@code [b[i-1], b[i]]} (with implicit edges 0 and 1).
 *
 * <p>Each boundary {@code k} has a blend zone of half-width
 * {@code hw = transition × min(left segment, right segment) / 2}. That is at most half of the
 * smaller adjacent segment, so zones of neighbouring boundaries never overlap. Inside a zone the
 * draw is weighted random between the two neighbours, with the right-hand entry's probability
 * rising linearly from 0 at {@code b - hw} through 0.5 at {@code b} to 1 at {@code b + hw}.
 * Outside every zone the segment's own entry is drawn with certainty. At {@code transition = 1}
 * every boundary fades from the middle of one segment to the middle of the next.
 */
public final class GradientHelper {

    /** Axis indices returned by {@link #selectAxis}. */
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;
    /** No single longest axis: a tie between two or more axes, including identical points. */
    public static final int AXIS_NONE = -1;

    public static final int MIN_ENTRIES = 2;
    public static final int MAX_ENTRIES = 9;

    /** Smallest share of the line a segment may shrink to (2%), so handles can never meet. */
    public static final double MIN_GAP = 0.02D;

    private GradientHelper() {
    }

    /**
     * The axis with the largest absolute difference between start and end, or {@link #AXIS_NONE}
     * if the largest difference is shared by two or more axes (which includes start == end).
     */
    public static int selectAxis(int sx, int sy, int sz, int ex, int ey, int ez) {
        long dx = Math.abs((long) ex - sx);
        long dy = Math.abs((long) ey - sy);
        long dz = Math.abs((long) ez - sz);
        long max = Math.max(dx, Math.max(dy, dz));
        int winners = (dx == max ? 1 : 0) + (dy == max ? 1 : 0) + (dz == max ? 1 : 0);
        if (winners != 1) {
            return AXIS_NONE;
        }
        return dx == max ? AXIS_X : dy == max ? AXIS_Y : AXIS_Z;
    }

    /** Bits for {@link #tiedAxes}: X = 1, Y = 2, Z = 4. */
    public static final int MASK_X = 1;
    public static final int MASK_Y = 2;
    public static final int MASK_Z = 4;

    /**
     * Which axes share the largest absolute difference when two or three of them do, as a bit
     * mask of {@link #MASK_X}/{@link #MASK_Y}/{@link #MASK_Z}. Returns 0 when one axis wins
     * outright, and 0 for identical points (no axis differs at all, so nothing "ties").
     */
    public static int tiedAxes(int sx, int sy, int sz, int ex, int ey, int ez) {
        long dx = Math.abs((long) ex - sx);
        long dy = Math.abs((long) ey - sy);
        long dz = Math.abs((long) ez - sz);
        long max = Math.max(dx, Math.max(dy, dz));
        if (max == 0) {
            return 0;
        }
        int mask = (dx == max ? MASK_X : 0) | (dy == max ? MASK_Y : 0) | (dz == max ? MASK_Z : 0);
        return Integer.bitCount(mask) >= 2 ? mask : 0;
    }

    /** The largest absolute difference between start and end on any axis. */
    public static long largestDifference(int sx, int sy, int sz, int ex, int ey, int ez) {
        return Math.max(Math.abs((long) ex - sx), Math.max(Math.abs((long) ey - sy), Math.abs((long) ez - sz)));
    }

    // ---- 1D / 2D / 3D gradients ----------------------------------------------------------------

    /** {@link AxisCheck#problem()} values. */
    public static final int PROBLEM_NONE = 0;
    /** Start and end are the same block. */
    public static final int PROBLEM_SAME = 1;
    /**
     * Can't tell which axes to use. 1D: the longest axis is tied ({@code problemAxes} = the tied
     * axes, {@code distance} = their difference). 2D: the axis to ignore is tied
     * ({@code problemAxes} = the tied smallest axes, {@code distance} = their difference).
     */
    public static final int PROBLEM_TIE = 2;
    /**
     * Not enough axes differ for the shape. 2D: only one axis differs ({@code problemAxes} = that
     * axis). 3D: some axes don't differ ({@code problemAxes} = the axes that are the same).
     */
    public static final int PROBLEM_TOO_FEW = 3;

    /**
     * Result of {@link #checkAxes}: the axes the gradient runs across (a mask of
     * {@link #MASK_X}/{@link #MASK_Y}/{@link #MASK_Z}; 0 when invalid), or why it can't run.
     */
    public record AxisCheck(int axes, int problem, int problemAxes, long distance) {
        public boolean valid() {
            return problem == PROBLEM_NONE;
        }
    }

    /**
     * Which axes a gradient of {@code shape} (1, 2 or 3) uses for these points, or why it can't.
     * <ul>
     *   <li>1D: the single axis with the largest difference; any tie is invalid.</li>
     *   <li>2D (lenient): the two axes that differ most, ignoring the smallest. Invalid when the
     *       smallest is tied (can't tell which to ignore, e.g. a cube's corners) or when only one
     *       axis differs.</li>
     *   <li>3D: all three axes; invalid unless every axis differs.</li>
     * </ul>
     * Identical points are {@link #PROBLEM_SAME} for every shape.
     */
    public static AxisCheck checkAxes(int shape, int sx, int sy, int sz, int ex, int ey, int ez) {
        long[] d = {Math.abs((long) ex - sx), Math.abs((long) ey - sy), Math.abs((long) ez - sz)};
        int[] bit = {MASK_X, MASK_Y, MASK_Z};
        if (d[0] == 0 && d[1] == 0 && d[2] == 0) {
            return new AxisCheck(0, PROBLEM_SAME, 0, 0);
        }
        switch (shape) {
            case 2 -> {
                long min = Math.min(d[0], Math.min(d[1], d[2]));
                int minMask = 0;
                for (int i = 0; i < 3; i++) {
                    if (d[i] == min) {
                        minMask |= bit[i];
                    }
                }
                if (Integer.bitCount(minMask) == 1) {
                    return new AxisCheck((MASK_X | MASK_Y | MASK_Z) & ~minMask, PROBLEM_NONE, 0, 0);
                }
                if (min == 0) {
                    // Two axes are 0, so only one differs.
                    return new AxisCheck(0, PROBLEM_TOO_FEW, (MASK_X | MASK_Y | MASK_Z) & ~minMask, 0);
                }
                return new AxisCheck(0, PROBLEM_TIE, minMask, min);
            }
            case 3 -> {
                int same = 0;
                for (int i = 0; i < 3; i++) {
                    if (d[i] == 0) {
                        same |= bit[i];
                    }
                }
                return same == 0
                        ? new AxisCheck(MASK_X | MASK_Y | MASK_Z, PROBLEM_NONE, 0, 0)
                        : new AxisCheck(0, PROBLEM_TOO_FEW, same, 0);
            }
            default -> {
                int tie = tiedAxes(sx, sy, sz, ex, ey, ez);
                if (tie != 0) {
                    return new AxisCheck(0, PROBLEM_TIE, tie, largestDifference(sx, sy, sz, ex, ey, ez));
                }
                int axis = selectAxis(sx, sy, sz, ex, ey, ez);
                return new AxisCheck(bit[axis], PROBLEM_NONE, 0, 0);
            }
        }
    }

    /**
     * Position of {@code p} along the start→end line, using only the {@code axes} in the mask:
     * {@code t = clamp((P-S)·(E-S) / |E-S|², 0, 1)} with the vectors restricted to those axes.
     * The bands are straight and at right angles to the line, so in 2D/3D the fade runs
     * diagonally, and blocks past either end clamp to the first or last entry. With one axis this
     * is exactly {@link #positionT}.
     */
    public static double projectT(int px, int py, int pz, int sx, int sy, int sz, int ex, int ey, int ez,
                                  int axes) {
        double num = 0.0D;
        double den = 0.0D;
        if ((axes & MASK_X) != 0) {
            num += ((double) px - sx) * ((double) ex - sx);
            den += ((double) ex - sx) * ((double) ex - sx);
        }
        if ((axes & MASK_Y) != 0) {
            num += ((double) py - sy) * ((double) ey - sy);
            den += ((double) ey - sy) * ((double) ey - sy);
        }
        if ((axes & MASK_Z) != 0) {
            num += ((double) pz - sz) * ((double) ez - sz);
            den += ((double) ez - sz) * ((double) ez - sz);
        }
        return den == 0.0D ? 0.0D : clamp01(num / den);
    }

    /** "X", "XZ", "XYZ"... for an axes mask, in X, Y, Z order. */
    public static String axesName(int axes) {
        StringBuilder sb = new StringBuilder();
        if ((axes & MASK_X) != 0) {
            sb.append('X');
        }
        if ((axes & MASK_Y) != 0) {
            sb.append('Y');
        }
        if ((axes & MASK_Z) != 0) {
            sb.append('Z');
        }
        return sb.toString();
    }

    /** Display letter for an axis index ("X", "Y", "Z"), or "-" for {@link #AXIS_NONE}. */
    public static String axisName(int axis) {
        return switch (axis) {
            case AXIS_X -> "X";
            case AXIS_Y -> "Y";
            case AXIS_Z -> "Z";
            default -> "-";
        };
    }

    /**
     * {@code t = clamp((p - s) / (e - s), 0, 1)} on one axis. Works in either direction: if
     * {@code e < s}, {@code t} still runs from 0 at the start to 1 at the end. Returns 0 when
     * {@code s == e} (callers only use valid axes, where that cannot happen).
     */
    public static double positionT(int p, int s, int e) {
        if (s == e) {
            return 0.0D;
        }
        double t = ((double) p - s) / ((double) e - s);
        return clamp01(t);
    }

    /**
     * Picks the palette index for position {@code t}.
     *
     * @param t          position along the line, clamped to [0, 1]
     * @param boundaries the N-1 boundary positions, ascending, each in (0, 1)
     * @param transition blend width, 0 (hard bands) to 1 (full fade), clamped
     * @param random     a uniform random value in [0, 1)
     * @return the palette index, 0 to N-1
     */
    public static int selectIndex(double t, double[] boundaries, double transition, double random) {
        t = clamp01(t);
        transition = clamp01(transition);
        int n = boundaries.length + 1;
        for (int k = 0; k < boundaries.length; k++) {
            double b = boundaries[k];
            double left = b - (k == 0 ? 0.0D : boundaries[k - 1]);
            double right = (k == boundaries.length - 1 ? 1.0D : boundaries[k + 1]) - b;
            double hw = transition * Math.min(left, right) / 2.0D;
            if (hw > 0.0D && t >= b - hw && t <= b + hw) {
                double pRight = (t - (b - hw)) / (2.0D * hw);
                return random < pRight ? k + 1 : k;
            }
        }
        // Hard band: the entry is the number of boundaries at or below t.
        int index = 0;
        for (double b : boundaries) {
            if (t >= b) {
                index++;
            }
        }
        return Math.min(index, n - 1);
    }

    /** The blend half-width at boundary {@code k} (used by the config screen's zone markers). */
    public static double blendHalfWidth(double[] boundaries, int k, double transition) {
        double b = boundaries[k];
        double left = b - (k == 0 ? 0.0D : boundaries[k - 1]);
        double right = (k == boundaries.length - 1 ? 1.0D : boundaries[k + 1]) - b;
        return clamp01(transition) * Math.min(left, right) / 2.0D;
    }

    /** N-1 boundaries splitting the line into N equal segments. */
    public static double[] evenBoundaries(int entries) {
        double[] b = new double[Math.max(0, entries - 1)];
        for (int i = 0; i < b.length; i++) {
            b[i] = (i + 1) / (double) entries;
        }
        return b;
    }

    /**
     * Whether {@code boundaries} is usable for a palette of {@code entries}: right length, finite,
     * ascending, and every segment at least {@link #MIN_GAP} wide (with a small tolerance).
     */
    public static boolean isValid(double[] boundaries, int entries) {
        if (boundaries == null || boundaries.length != entries - 1) {
            return false;
        }
        double prev = 0.0D;
        for (double b : boundaries) {
            if (!Double.isFinite(b) || b - prev < MIN_GAP - 1e-9) {
                return false;
            }
            prev = b;
        }
        return 1.0D - prev >= MIN_GAP - 1e-9;
    }

    /**
     * Moves handle {@code index} to {@code position}, kept at least {@link #MIN_GAP} from its
     * neighbours (or from 0 / 1 at the ends), so handles can never cross or pass each other.
     * Only that one value changes, so only the two adjacent segments change size.
     *
     * @return the position actually applied
     */
    public static double moveHandle(double[] boundaries, int index, double position) {
        double lo = (index == 0 ? 0.0D : boundaries[index - 1]) + MIN_GAP;
        double hi = (index == boundaries.length - 1 ? 1.0D : boundaries[index + 1]) - MIN_GAP;
        double v = Double.isFinite(position) ? position : boundaries[index];
        v = lo > hi ? (lo + hi) / 2.0D : Math.max(lo, Math.min(hi, v));
        boundaries[index] = v;
        return v;
    }

    /** Share of the line owned by entry {@code i} (0 to 1). */
    public static double segmentShare(double[] boundaries, int i) {
        double lo = i == 0 ? 0.0D : boundaries[i - 1];
        double hi = i == boundaries.length ? 1.0D : boundaries[i];
        return hi - lo;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, v));
    }
}
