package com.yourname.texturebuilder.placement;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.hud.TextureBuilderHud;
import com.yourname.texturebuilder.util.GradientHelper;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Gradient mode's game-facing side: the "Set Gradient Point" keybind cycle, which saved points
 * apply in the current dimension, and the slot pick for one placement. The math itself lives in
 * {@link GradientHelper}.
 *
 * <p>Points are stored in {@link ModConfig} with the dimension they were set in, and the TOML is
 * written every time they change. Points saved in another dimension are ignored (not applied) but
 * kept, until the player starts a new start point in the current dimension.
 */
public final class GradientController {

    /** What the stored points mean in the current dimension. */
    public enum State {
        /** No start point. */
        NO_POINTS,
        /** Points exist but belong to a different dimension, so they are ignored here. */
        OTHER_DIMENSION,
        /** A start point but no end point yet. */
        START_ONLY,
        /**
         * Both points set, but they don't work for the current 1D/2D/3D shape (identical points, a
         * tie, or too few differing axes; see {@link GradientHelper#checkAxes}).
         */
        NO_AXIS,
        /** Both points set and valid for the current shape: gradient placement is active. */
        VALID
    }

    private GradientController() {
    }

    /** The current level's dimension id, e.g. {@code minecraft:overworld}, or "" with no level. */
    public static String currentDimension(Minecraft client) {
        return client.level == null ? "" : client.level.dimension().location().toString();
    }

    /**
     * The stored points' state here. With no level loaded (e.g. the config screen opened from the
     * title screen through Mod Menu) the dimension check is skipped.
     */
    public static State state(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        if (cfg.gradientStart == null) {
            return State.NO_POINTS;
        }
        if (client.level != null && !currentDimension(client).equals(cfg.gradientDimension)) {
            return State.OTHER_DIMENSION;
        }
        if (cfg.gradientEnd == null) {
            return State.START_ONLY;
        }
        return check(cfg).valid() ? State.VALID : State.NO_AXIS;
    }

    /**
     * Which axes the stored points use for the current shape ({@code gradient_shape}), or why they
     * can't be used. Unset points report {@link GradientHelper#PROBLEM_SAME}; callers check
     * {@link #state} first.
     */
    public static GradientHelper.AxisCheck check(ModConfig cfg) {
        int[] s = cfg.gradientStart;
        int[] e = cfg.gradientEnd;
        if (s == null || e == null) {
            return new GradientHelper.AxisCheck(0, GradientHelper.PROBLEM_SAME, 0, 0);
        }
        return GradientHelper.checkAxes(cfg.gradientShape, s[0], s[1], s[2], e[0], e[1], e[2]);
    }

    /**
     * "Set Gradient Point" keybind: 1st press sets the start, 2nd the end, 3rd clears both, then
     * the cycle repeats. The cycle position comes from the stored points, so it survives restarts.
     * If the stored points belong to another dimension, the press starts a fresh cycle here: it
     * sets a new start and drops the old end.
     */
    public static void onSetPointPressed(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        ModConfig cfg = ModConfig.get();
        String dimension = currentDimension(client);
        boolean pointsHere = cfg.gradientStart != null && dimension.equals(cfg.gradientDimension);

        if (pointsHere && cfg.gradientEnd != null) {
            cfg.clearGradientPoints();
            cfg.save();
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.gradient.cleared"));
            return;
        }

        BlockPos target = lookedAtBlock(client);
        if (target == null) {
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.gradient.no_target"));
            return;
        }
        int[] point = {target.getX(), target.getY(), target.getZ()};

        if (!pointsHere) {
            cfg.gradientStart = point;
            cfg.gradientEnd = null;
            cfg.gradientDimension = dimension;
            cfg.save();
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.gradient.start_set",
                    point[0], point[1], point[2]));
            return;
        }

        cfg.gradientEnd = point;
        cfg.save();
        GradientHelper.AxisCheck check = check(cfg);
        if (!check.valid()) {
            TextureBuilderHud.showMessage(problemMessage(cfg, check));
        } else if (Integer.bitCount(check.axes()) == 1) {
            // Green confirmation, the counterpart of the gold refusal warnings: the gradient is
            // ready, along this axis, spanning this many blocks (both endpoints included).
            int[] s = cfg.gradientStart;
            long length = GradientHelper.largestDifference(s[0], s[1], s[2], point[0], point[1], point[2]) + 1;
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.gradient.end_set",
                            point[0], point[1], point[2], GradientHelper.axesName(check.axes()), length)
                    .withStyle(ChatFormatting.GREEN));
        } else {
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.gradient.end_set_multi",
                            point[0], point[1], point[2], runsPhrase(check.axes()))
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /**
     * The hotbar slot gradient mode picks for this placement, or -1 if it can't pick. Callers check
     * {@link #blockedReason} first, which blocks every case that returns -1 here, so the caller's
     * Texture-pool fallback is only a safety net.
     */
    public static int selectSlot(Minecraft client, BlockHitResult hit, RandomGenerator random) {
        if (state(client) != State.VALID || hit == null) {
            return -1;
        }
        ModConfig cfg = ModConfig.get();
        int[] palette = cfg.gradientPalette();
        if (palette.length < GradientHelper.MIN_ENTRIES || cfg.gradientBoundaries.length != palette.length - 1) {
            return -1;
        }
        BlockPos placed = predictPlacedPos(client, hit);
        if (placed == null) {
            return -1;
        }
        int axes = check(cfg).axes();
        int[] s = cfg.gradientStart;
        int[] e = cfg.gradientEnd;
        // Projection onto the start-to-end line over the shape's axes: 1D is the plain per-axis
        // fraction, 2D/3D fade diagonally with straight bands at right angles to the line.
        double t = GradientHelper.projectT(placed.getX(), placed.getY(), placed.getZ(),
                s[0], s[1], s[2], e[0], e[1], e[2], axes);
        int index = GradientHelper.selectIndex(t, cfg.gradientBoundaries,
                cfg.gradientTransition / 100.0D, random.nextDouble());
        int slot = palette[index];
        if (cfg.debugLogging) {
            TextureBuilder.LOGGER.info("[TextureBuilder] gradient draw at {}: axes {}, t={}, entry {} -> slot {}",
                    placed.toShortString(), GradientHelper.axesName(axes), String.format("%.3f", t), index + 1, slot + 1);
        }
        return slot;
    }

    /**
     * Why Gradient mode must not place right now, as a gold warning for the HUD, or {@code null}
     * when it can. Every point problem, and fewer than 2 included slots, blocks placement rather
     * than falling back to Texture mode's mix (user's choice, 2026-10-25). Point problems are
     * reported first.
     */
    public static Component blockedReason(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        MutableComponent reason = switch (state(client)) {
            case NO_POINTS -> Component.translatable("message.texturebuilder.gradient.blocked.no_points");
            case START_ONLY -> Component.translatable("message.texturebuilder.gradient.blocked.no_end");
            case OTHER_DIMENSION -> Component.translatable("message.texturebuilder.gradient.blocked.other_dimension",
                    cfg.gradientDimension);
            case NO_AXIS -> problemMessage(cfg, check(cfg));
            case VALID -> cfg.gradientPalette().length < GradientHelper.MIN_ENTRIES
                    ? Component.translatable("message.texturebuilder.gradient.blocked.need_two")
                    : null;
        };
        return reason == null ? null : reason.withStyle(ChatFormatting.GOLD);
    }

    /** Per block class: whether it overrides {@code use}, i.e. has its own right-click. */
    private static final Map<Class<?>, Boolean> USE_ACTION_CACHE = new ConcurrentHashMap<>();

    /**
     * The runtime name of {@code BlockBehaviour.use}. Minecraft 1.20.2 runs with
     * intermediary names in production ({@code method_9534}) and Mojang names in a dev
     * environment, so the name is looked up through Fabric Loader's mapping resolver rather than
     * written as a literal.
     */
    private static final String USE_METHOD_NAME = FabricLoader.getInstance().getMappingResolver().mapMethodName(
            "intermediary", "net.minecraft.class_4970", "method_9534",
            "(Lnet/minecraft/class_2680;Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;Lnet/minecraft/class_1657;Lnet/minecraft/class_1268;Lnet/minecraft/class_3965;)Lnet/minecraft/class_1269;");

    /**
     * This jar also runs on 1.20.5 - 1.20.6, where the block's own right-click became
     * {@code useWithoutItem(state, level, pos, player, hit)} ({@code method_55766}, the method 26.x
     * checks). Its name is resolved the same way; on 1.20.2 - 1.20.4 it simply matches nothing.
     */
    private static final String USE_WITHOUT_ITEM_NAME = FabricLoader.getInstance().getMappingResolver().mapMethodName(
            "intermediary", "net.minecraft.class_4970", "method_55766",
            "(Lnet/minecraft/class_2680;Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;Lnet/minecraft/class_1657;Lnet/minecraft/class_3965;)Lnet/minecraft/class_1269;");

    /**
     * Whether right-clicking this block does something of its own (open a chest, toggle a door,
     * press a button), judged by whether its class overrides {@code BlockBehaviour.use} (this
     * version's counterpart of 1.20.5+'s {@code useWithoutItem}).
     * Vanilla runs that before placing the held item (unless the player is sneaking with an item),
     * so when it acts, nothing is placed and the click can safely go through while placement is
     * blocked.
     *
     * <p>The method is matched by its runtime name, {@link #USE_METHOD_NAME} (or
     * {@link #USE_WITHOUT_ITEM_NAME} on 1.20.5+). A few blocks
     * override it but sometimes pass (e.g. redstone ore lighting up); a
     * click on those still places the held block, since the client can't know in advance.
     */
    public static boolean hasUseAction(Block block) {
        return USE_ACTION_CACHE.computeIfAbsent(block.getClass(), cls -> {
            for (Class<?> c = cls; c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if ((m.getName().equals(USE_METHOD_NAME) && m.getParameterCount() == 6)
                            || (m.getName().equals(USE_WITHOUT_ITEM_NAME) && m.getParameterCount() == 5)) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    /**
     * Where this placement will put the block. This runs before vanilla places it, so it uses the
     * same rule the restock code applies afterwards: the face-adjacent position, or the clicked
     * position itself when that block is replaceable (grass, snow layers, water).
     */
    static BlockPos predictPlacedPos(Minecraft client, BlockHitResult hit) {
        if (client.level == null) {
            return null;
        }
        BlockPos clicked = hit.getBlockPos();
        if (client.level.getBlockState(clicked).canBeReplaced()) {
            return clicked;
        }
        return clicked.relative(hit.getDirection());
    }

    private static BlockPos lookedAtBlock(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    /**
     * One-line summary of the points and axis, for the gradient screen and
     * {@code /texturebuilder status}.
     */
    public static Component describePoints(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        return switch (state(client)) {
            case NO_POINTS -> Component.translatable("text.texturebuilder.gradient.points.none")
                    .withStyle(ChatFormatting.GRAY);
            case OTHER_DIMENSION -> Component.translatable("text.texturebuilder.gradient.points.other_dimension",
                    cfg.gradientDimension).withStyle(ChatFormatting.GOLD);
            case START_ONLY -> Component.translatable("text.texturebuilder.gradient.points.start_only",
                    point(cfg.gradientStart));
            case NO_AXIS -> Component.translatable("text.texturebuilder.gradient.points.problem",
                    point(cfg.gradientStart), point(cfg.gradientEnd), problemMessage(cfg, check(cfg)))
                    .withStyle(ChatFormatting.RED);
            case VALID -> Component.translatable("text.texturebuilder.gradient.points.valid",
                    point(cfg.gradientStart), point(cfg.gradientEnd), runsPhrase(check(cfg).axes()))
                    .withStyle(ChatFormatting.GREEN);
        };
    }

    private static String point(int[] p) {
        return p[0] + " " + p[1] + " " + p[2];
    }

    /**
     * Why the points don't work for the current shape:
     * <ul>
     *   <li>same block twice: "Gradient start and end are the same";</li>
     *   <li>1D tie: "Gradient axis unclear: X and Z both differ by 12 blocks";</li>
     *   <li>2D tie on the axis to ignore: "2D gradient unclear: X, Y and Z all differ by 12 blocks";</li>
     *   <li>2D with one differing axis: "2D gradient needs the points to differ on two axes (only X
     *       differs)";</li>
     *   <li>3D with an axis that matches: "3D gradient needs the points to differ on X, Y and Z (Y
     *       is the same)".</li>
     * </ul>
     */
    private static MutableComponent problemMessage(ModConfig cfg, GradientHelper.AxisCheck check) {
        return switch (check.problem()) {
            case GradientHelper.PROBLEM_TIE -> Component.translatable(cfg.gradientShape == 2
                            ? "message.texturebuilder.gradient.unclear_2d" : "message.texturebuilder.gradient.unclear",
                    tieDetail(check.problemAxes(), check.distance()));
            case GradientHelper.PROBLEM_TOO_FEW -> cfg.gradientShape == 3
                    ? Component.translatable("message.texturebuilder.gradient.need_three_axes",
                            sameAxes(check.problemAxes()))
                    : Component.translatable("message.texturebuilder.gradient.need_two_axes",
                            GradientHelper.axesName(check.problemAxes()));
            default -> Component.translatable("message.texturebuilder.gradient.same");
        };
    }

    /** "X and Z both differ by 12 blocks" or "X, Y and Z all differ by 12 blocks". */
    private static MutableComponent tieDetail(int mask, long blocks) {
        Component distance = blocks == 1
                ? Component.translatable("text.texturebuilder.gradient.distance.one")
                : Component.translatable("text.texturebuilder.gradient.distance.many", blocks);
        if (Integer.bitCount(mask) == 3) {
            return Component.translatable("text.texturebuilder.gradient.tie.all", distance);
        }
        String names = GradientHelper.axesName(mask);
        return Component.translatable("text.texturebuilder.gradient.tie.two",
                String.valueOf(names.charAt(0)), String.valueOf(names.charAt(1)), distance);
    }

    /** "Y is the same" / "Y and Z are the same", for the 3D message. */
    private static MutableComponent sameAxes(int mask) {
        String names = GradientHelper.axesName(mask);
        return names.length() == 1
                ? Component.translatable("text.texturebuilder.gradient.same.one", names)
                : Component.translatable("text.texturebuilder.gradient.same.two",
                        String.valueOf(names.charAt(0)), String.valueOf(names.charAt(1)));
    }

    /** "along X" (1D), "across X and Z (2D)" or "across X, Y and Z (3D)". */
    private static MutableComponent runsPhrase(int axes) {
        String names = GradientHelper.axesName(axes);
        return switch (names.length()) {
            case 1 -> Component.translatable("text.texturebuilder.gradient.runs.one", names);
            case 2 -> Component.translatable("text.texturebuilder.gradient.runs.two",
                    String.valueOf(names.charAt(0)), String.valueOf(names.charAt(1)));
            default -> Component.translatable("text.texturebuilder.gradient.runs.three");
        };
    }
}
