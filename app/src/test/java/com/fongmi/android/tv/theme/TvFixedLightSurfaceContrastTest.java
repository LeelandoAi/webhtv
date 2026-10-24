package com.fongmi.android.tv.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mirror of {@link TvFixedDarkSurfaceContrastTest}: a <em>palette-following</em>
 * foreground on a surface whose fill is palette-independent <em>light</em>.
 *
 * <p>That pair breaks in the opposite direction. A drawable with a literal light fill keeps
 * that fill in every table, so the only thing that moves is the foreground role: readable
 * {@code #44474F} in the day table, but {@code #C4C6D0} in the night table - 1.63:1 on
 * {@code #F8FAFD}. Measured on the TV exit-confirm dialog on 2026-10-24 the secondary
 * ("取消") button was a near-white pill with an almost invisible label, because
 * {@code dialog_exit_confirm.xml} painted it with {@code ?attr/colorOnSurfaceVariant} while
 * {@code selector_exit_confirm_secondary} kept its literal light fill.
 *
 * <p>Neither existing guard covers this: {@link LeanbackForegroundContrastTest} only tracks
 * <em>constant light</em> foregrounds, and {@link TvFixedDarkSurfaceContrastTest} only tracks
 * fixed <em>dark</em> surfaces (and only in day mode). This class therefore resolves each
 * palette role in <em>both</em> tables and requires the worst of the two to clear the
 * body-text floor, so a role that only becomes unreadable after dark cannot hide.
 */
public class TvFixedLightSurfaceContrastTest {

    /** Minimum contrast for the button and label text these layouts carry. */
    private static final double MIN_CONTRAST = 4.5;

    /**
     * Fills below this luminance can no longer be called "designed for dark text".
     *
     * <p>The surfaces below are {@code #F8FAFD} / {@code #DCEAFF} / {@code #FAFBFC}; the
     * threshold only needs to separate those from a dark panel role.
     */
    private static final double LIGHT_SURFACE_LUMINANCE = 0.6;

    /**
     * Layouts that carry the same defect class but are <em>not</em> part of this fix.
     *
     * <p>The exit-confirm dialog above was the layout the user reported. The files below came
     * out of the first full run of this guard (2026-10-24) and are the same defect - a
     * palette-following foreground on a drawable whose fill is a literal that never changes
     * - on screens nobody reported. Each needs its own visual review and its own task, so they
     * are listed here instead of being silently repainted inside an unrelated change; this
     * guard starts covering a file the moment its entry is removed. The ratio in each line is
     * the worst of the day and night tables, as the guard measured it.
     *
     * <ul>
     *   <li>{@code dialog_ad_block_stats.xml}: twelve statistic labels,
     *       {@code ?attr/colorOnSurfaceVariant} on {@code shape_ad_stats_content} ({@code #FAFBFC}), 1.31:1.</li>
     *   <li>{@code adapter_ad_stats_item.xml}: {@code ?attr/colorOnSurfaceVariant} on
     *       {@code selector_ad_stats_item} ({@code #00FFFFFF} / {@code #E8F0FE}), 1.31:1.</li>
     *   <li>{@code adapter_mpv_config_profile.xml}: {@code ?attr/colorOnSurface} and
     *       {@code ?attr/colorOnSurfaceVariant} on {@code selector_mpv_profile_card}
     *       ({@code #FAFBFC} / {@code #F4F8FF}), 1.13:1 / 1.49:1, and {@code ?attr/colorPrimary}
     *       on the {@code shape_mpv_active_badge} fill {@code #E8F0FE}, 1.50:1.</li>
     *   <li>{@code dialog_ai_recommendation_info.xml}: {@code ?attr/colorPrimary} on
     *       {@code shape_recommendation_source_chip} ({@code #E8F0FE}), 1.50:1. The chip's own
     *       comment already concedes the light fill is deliberate, so its text has to stop
     *       following the palette the same way the exit-confirm button did.</li>
     * </ul>
     */
    private static final String[] PENDING_LAYOUTS = {
            "dialog_ad_block_stats.xml",
            "adapter_ad_stats_item.xml",
            "adapter_mpv_config_profile.xml",
            "dialog_ai_recommendation_info.xml",
    };

    /**
     * Layouts owned by the Lab module, which pins {@code colorOnSurface} to
     * {@code #FFFFFFFF} in its own {@code Theme.App.Lab} styles. Its roles do not follow the
     * app tables, and the module is hard-coded dark by design, so nothing here applies.
     */
    private static final String[] LAB_LAYOUTS = {
            "adapter_lab_command_compact.xml", "adapter_lab_package.xml",
            "activity_lab_output.xml", "dialog_lab_command_sheet.xml",
    };

    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z0-9_.]+)((?:\"[^\"]*\"|[^>\"])*?)(/?)>", Pattern.DOTALL);
    private static final Pattern BACKGROUND = Pattern.compile("(?:android:background|app:cardBackgroundColor|android:cardBackgroundColor)=\"([^\"]+)\"");
    private static final Pattern TEXT_COLOR = Pattern.compile("android:textColor=\"([^\"]+)\"");
    private static final Pattern SOLID = Pattern.compile("<solid\\s+android:color=\"([^\"]+)\"");
    private static final Pattern GRADIENT = Pattern.compile("android:(?:startColor|endColor|centerColor)=\"([^\"]+)\"");
    private static final Pattern COLOR_ENTRY = Pattern.compile("<color name=\"([^\"]+)\">([^<]+)</color>");
    private static final Pattern SELECTOR_COLOR = Pattern.compile("android:color=\"([^\"]+)\"");
    private static final Pattern STYLE_COLOR_ENTRY = Pattern.compile("<item name=\"([A-Za-z0-9_]+)\">(@color/[A-Za-z0-9_]+)</item>");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern STROKE = Pattern.compile("<stroke\\b[^>]*/>|<stroke\\b.*?</stroke>", Pattern.DOTALL);
    private static final Pattern PADDING = Pattern.compile("<padding\\b[^>]*/>|<padding\\b.*?</padding>", Pattern.DOTALL);
    private static final Pattern MASK_ITEM = Pattern.compile("<item\\b[^>]*@android:id/mask[^>]*>.*?</item>", Pattern.DOTALL);
    private static final Pattern RIPPLE_COLOR = Pattern.compile("<ripple\\b[^>]*android:color=\"[^\"]*\"[^>]*>");

    /** Day-table colour literals, then the night table layered on top by qualifier order. */
    private static final Map<String, String> DAY = new HashMap<>();
    private static final Map<String, String> NIGHT = new HashMap<>();

    /** {@code ?attr/} name to the {@code @color/} resource its theme style points at. */
    private static final Map<String, String> STYLE_COLORS = new LinkedHashMap<>();

    private static boolean loaded;

    // ------------------------------------------------------------------ palette loading

    private static Path root() {
        return Files.exists(Path.of("src")) ? Path.of("") : Path.of("app");
    }

    private static String readIfExists(String relative) {
        try {
            Path path = root().resolve(relative);
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n") : null;
        } catch (Exception error) {
            return null;
        }
    }

    private static void loadTables() {
        if (loaded) return;
        loaded = true;
        loadLiterals("src/main/res/values/webhtv_tokens.xml", DAY);
        loadLiterals("src/main/res/values/colors.xml", DAY);
        loadLiterals("src/leanback/res/values/colors.xml", DAY);
        NIGHT.putAll(DAY);
        loadLiterals("src/main/res/values-night/webhtv_tokens.xml", NIGHT);
        loadLiterals("src/main/res/values-night/colors.xml", NIGHT);
        loadLiterals("src/leanback/res/values-night/colors.xml", NIGHT);
        String styles = readIfExists("src/main/res/values/webhtv_styles.xml");
        if (styles == null) return;
        Matcher matcher = STYLE_COLOR_ENTRY.matcher(withoutComments(styles));
        while (matcher.find()) STYLE_COLORS.putIfAbsent(matcher.group(1), matcher.group(2));
    }

    private static void loadLiterals(String file, Map<String, String> target) {
        String source = readIfExists(file);
        if (source == null) return;
        Matcher matcher = COLOR_ENTRY.matcher(withoutComments(source));
        while (matcher.find()) target.put(matcher.group(1), matcher.group(2).trim());
    }

    private static String withoutComments(String source) {
        return COMMENT.matcher(source).replaceAll("");
    }

    /** Literal a {@code @color/} resource resolves to in one table, or null when it follows the palette. */
    private static String literalOf(String name, Map<String, String> table, boolean night) {
        return literalOf(name, table, night, new HashMap<>());
    }

    private static String literalOf(String name, Map<String, String> table, boolean night, Map<String, String> seen) {
        if (seen.put(name, name) != null) return null;
        String value = table.get(name);
        if (value != null) {
            if (value.startsWith("#")) return value;
            if (value.startsWith("@color/")) return literalOf(value.substring("@color/".length()), table, night, seen);
            return null;
        }
        for (String directory : new String[]{"src/main/res/color", "src/leanback/res/color"}) {
            String source = night ? readIfExists(directory + "-night/" + name + ".xml") : null;
            if (source == null) source = readIfExists(directory + "/" + name + ".xml");
            if (source == null) continue;
            if (withoutComments(source).contains("?attr/")) return null;
            Matcher matcher = SELECTOR_COLOR.matcher(withoutComments(source));
            while (matcher.find()) {
                String entry = matcher.group(1);
                if (entry.startsWith("#")) return entry;
                if (entry.startsWith("@color/")) {
                    String resolved = literalOf(entry.substring("@color/".length()), table, night, seen);
                    if (resolved != null) return resolved;
                }
            }
        }
        return null;
    }

    /** The day and night values a {@code ?attr/} foreground resolves to, or null when either is unknown. */
    private static String[] paletteColors(String value) {
        if (!value.startsWith("?attr/")) return null;
        String color = STYLE_COLORS.get(value.substring("?attr/".length()));
        if (color == null || !color.startsWith("@color/")) return null;
        String day = literalOf(color.substring("@color/".length()), DAY, false);
        String night = literalOf(color.substring("@color/".length()), NIGHT, true);
        if (day == null || night == null) return null;
        return new String[]{day, night};
    }

    private static double luminance(String hex) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if (digits.length() == 8) digits = digits.substring(2);
        double[] channels = new double[3];
        for (int i = 0; i < 3; i++) {
            double value = Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16) / 255.0;
            channels[i] = value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }

    private static int alpha(String hex) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        return digits.length() == 8 ? Integer.parseInt(digits.substring(0, 2), 16) : 0xFF;
    }

    private static double contrast(String first, String second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /**
     * Composites a translucent fill over the app's dark content backdrop.
     *
     * <p>A light panel that is translucent becomes darker, which can only move a fill
     * <em>away</em> from the light threshold. The result is therefore conservative: such a
     * panel drops out of this guard rather than producing a false failure.
     */
    private static String compositeOver(String fill, String backdrop) {
        double a = alpha(fill) / 255.0;
        String top = fill.startsWith("#") ? fill.substring(1) : fill;
        if (top.length() == 8) top = top.substring(2);
        String bottom = backdrop.startsWith("#") ? backdrop.substring(1) : backdrop;
        StringBuilder out = new StringBuilder("#");
        for (int i = 0; i < 3; i++) {
            int front = Integer.parseInt(top.substring(i * 2, i * 2 + 2), 16);
            int back = Integer.parseInt(bottom.substring(i * 2, i * 2 + 2), 16);
            out.append(String.format(Locale.ROOT, "%02X", Math.round(front * a + back * (1 - a))));
        }
        return out.toString();
    }

    /**
     * The fills of a background in both tables, or {@code null} when it follows the palette.
     *
     * <p>A {@code ?attr/} fill, an unresolvable {@code @color/} or a non-colour value makes
     * the whole drawable palette-following, which is the case this guard does not cover.
     */
    private static List<String> fixedFills(String name) {
        List<String> fills = new ArrayList<>();
        boolean palette = false;
        for (String directory : new String[]{"src/main/res/drawable", "src/leanback/res/drawable"}) {
            String source = readIfExists(directory + "/" + name + ".xml");
            if (source == null) continue;
            String body = RIPPLE_COLOR.matcher(MASK_ITEM.matcher(PADDING.matcher(STROKE.matcher(withoutComments(source))
                    .replaceAll("")).replaceAll("")).replaceAll("")).replaceAll("<ripple>");
            if (body.contains("?attr/")) palette = true;
            Matcher solid = SOLID.matcher(body);
            while (solid.find()) {
                String value = solid.group(1);
                if (value.startsWith("#")) fills.add(value);
                else if (value.startsWith("@color/")) {
                    String literal = fixedLiteral(value.substring("@color/".length()));
                    if (literal == null) palette = true;
                    else fills.add(literal);
                } else if (!value.contains("transparent")) palette = true;
            }
            Matcher gradient = GRADIENT.matcher(body);
            while (gradient.find()) {
                String value = gradient.group(1);
                if (value.startsWith("#")) fills.add(value);
                else if (value.startsWith("@color/")) {
                    String literal = fixedLiteral(value.substring("@color/".length()));
                    if (literal == null) palette = true;
                    else fills.add(literal);
                } else palette = true;
            }
        }
        if (palette) return null;
        List<String> visible = fills.stream().filter(fill -> alpha(fill) >= 0x66).toList();
        if (visible.isEmpty()) return null;
        return visible.stream().map(fill -> compositeOver(fill, "#101418")).toList();
    }

    /**
     * The one literal an {@code @color/} resource paints in <em>both</em> tables.
     *
     * <p>A token whose day and night values differ follows the palette - it is simply not
     * the "fixed fill" this guard is about - so it returns null and drops the whole drawable
     * from the guard, exactly like a {@code ?attr/} fill would.
     */
    private static String fixedLiteral(String name) {
        String day = literalOf(name, DAY, false);
        String night = literalOf(name, NIGHT, true);
        if (day == null || night == null) return null;
        return day.equalsIgnoreCase(night) ? day : null;
    }

    // ------------------------------------------------------------------ the guard

    /**
     * A palette-following foreground must not sit on a palette-independent light surface.
     *
     * <p>Reported per file so a failure names the layout to fix, together with the resolved
     * surface and the worst ratio across the day and night tables.
     */
    @Test
    public void paletteForegroundsStayReadableOnFixedLightSurfaces() throws Exception {
        loadTables();
        List<Path> layouts = new ArrayList<>();
        for (String directory : new String[]{"src/leanback/res/layout", "src/main/res/layout"}) {
            Path base = root().resolve(directory);
            if (!Files.isDirectory(base)) continue;
            try (var paths = Files.walk(base)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".xml"))
                        .sorted()
                        .forEach(layouts::add);
            }
        }
        assertFalse("no layouts were found to inspect", layouts.isEmpty());

        List<String> failures = new ArrayList<>();
        for (Path layout : layouts) {
            String name = layout.getFileName().toString();
            if (isExcluded(name)) continue;
            String source = Files.readString(layout, StandardCharsets.UTF_8).replace("\r\n", "\n");
            for (String[] hit : inspect(source)) {
                double worst = Double.parseDouble(hit[2]);
                if (worst < MIN_CONTRAST) {
                    failures.add(name + ": " + hit[0] + " sits on the fixed light surface " + hit[1]
                            + " with " + ratio(hit[2]) + " at worst (day " + ratio(hit[3]) + ", night " + ratio(hit[4]) + ")");
                }
            }
        }
        if (!failures.isEmpty()) {
            fail("TV paints a palette-following foreground on a palette-independent light surface,"
                    + " which becomes near-white on near-white in the night table:\n  " + String.join("\n  ", failures));
        }
    }

    /**
     * Proves the guard above is not vacuous.
     *
     * <p>The probe feeds {@link #inspect} the exact markup the exit-confirm dialog carried
     * before the fix and requires it to be flagged with a sub-threshold worst-case ratio, so a
     * future regression cannot hide behind an empty match set.
     */
    @Test
    public void theGuardDetectsTheDefectItWasWrittenFor() {
        loadTables();
        String defective = "<LinearLayout android:background=\"@drawable/selector_exit_confirm_secondary\">"
                + "<TextView android:textColor=\"?attr/colorOnSurfaceVariant\" /></LinearLayout>";
        List<String[]> hits = inspect(defective);
        assertFalse("the guard must flag the measured pre-fix markup", hits.isEmpty());
        assertEquals("@drawable/selector_exit_confirm_secondary", hits.get(0)[1]);
        assertTrue("the probe must reproduce the unreadable ratio", Double.parseDouble(hits.get(0)[2]) < MIN_CONTRAST);

        // The mirror of the same markup: a palette-independent dark foreground must be ignored here.
        String alreadyCorrect = "<LinearLayout android:background=\"@drawable/selector_exit_confirm_secondary\">"
                + "<TextView android:textColor=\"@color/exit_confirm_secondary_text\" /></LinearLayout>";
        assertTrue("a palette-independent foreground is not this guard's concern",
                inspect(alreadyCorrect).isEmpty());
    }

    /**
     * Pins the surface this defect was fixed on, so a later "migrate the TV foregrounds"
     * sweep cannot put a palette-following role back on it.
     */
    @Test
    public void fixedLightPanelsKeepPaletteIndependentForegrounds() throws Exception {
        loadTables();
        String[][] expectations = {
                {"src/leanback/res/layout/dialog_exit_confirm.xml", "selector_exit_confirm_secondary"},
        };
        for (String[] expectation : expectations) {
            String source = readIfExists(expectation[0]);
            assertTrue(expectation[0] + " is missing", source != null);
            assertTrue(expectation[0] + " must still paint its secondary button with " + expectation[1],
                    source.contains(expectation[1]));
            assertTrue(expectation[0] + " must paint the secondary button with the fixed"
                            + " @color/exit_confirm_secondary_text selector, not a palette role",
                    source.contains("@color/exit_confirm_secondary_text"));
            for (String[] hit : inspect(source)) {
                if (!expectation[1].equals(hit[1].substring("@drawable/".length()))) continue;
                fail(expectation[0] + " must not paint " + hit[0] + " on " + hit[1]
                        + " (worst " + ratio(hit[2]) + ", day " + ratio(hit[3]) + ", night " + ratio(hit[4]) + ")");
            }
        }
    }

    /**
     * The pinned surface must really be palette-independent and light, otherwise the fix
     * above would be protecting the wrong thing.
     */
    @Test
    public void theFixedLightSurfacesAreStillPaletteIndependentAndLight() throws Exception {
        loadTables();
        for (String drawable : new String[]{"selector_exit_confirm_secondary"}) {
            List<String> fills = fixedFills(drawable);
            assertTrue(drawable + " must have no palette-following fill, or the guard is moot", fills != null);
            assertTrue(drawable + " must be light in both tables",
                    fills.stream().allMatch(fill -> luminance(fill) >= LIGHT_SURFACE_LUMINANCE));
        }
    }

    private static String ratio(String value) {
        return String.format(Locale.ROOT, "%.2f:1", Double.parseDouble(value));
    }

    private static boolean isExcluded(String name) {
        for (String pending : PENDING_LAYOUTS) if (pending.equals(name)) return true;
        for (String lab : LAB_LAYOUTS) if (lab.equals(name)) return true;
        return false;
    }

    /**
     * {@code {foreground, surface, worstRatio, dayRatio, nightRatio}} for every matched element.
     */
    private static List<String[]> inspect(String source) {
        List<String[]> hits = new ArrayList<>();
        Deque<String> backgrounds = new ArrayDeque<>();
        Matcher matcher = TAG.matcher(source);
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String attributes = matcher.group(3);
            boolean selfClosing = !matcher.group(4).isEmpty();
            if (closing) {
                if (!backgrounds.isEmpty()) backgrounds.pop();
                continue;
            }
            Matcher backgroundMatcher = BACKGROUND.matcher(attributes);
            String own = backgroundMatcher.find() ? backgroundMatcher.group(1) : null;
            if (!selfClosing) backgrounds.push(own == null ? "" : own);

            Matcher colorMatcher = TEXT_COLOR.matcher(attributes);
            if (!colorMatcher.find()) continue;
            String[] palette = paletteColors(colorMatcher.group(1));
            if (palette == null) continue;

            String nearest = own != null && !own.isEmpty() ? own : null;
            if (nearest == null) {
                for (String ancestor : backgrounds) {
                    if (ancestor != null && !ancestor.isEmpty()) {
                        nearest = ancestor;
                        break;
                    }
                }
            }
            if (nearest == null || !nearest.startsWith("@drawable/")) continue;
            List<String> fills = fixedFills(nearest.substring("@drawable/".length()));
            if (fills == null) continue;
            boolean light = fills.stream().allMatch(fill -> luminance(fill) >= LIGHT_SURFACE_LUMINANCE);
            if (!light) continue;
            double day = fills.stream().mapToDouble(fill -> contrast(palette[0], fill)).min().orElse(Double.MAX_VALUE);
            double night = fills.stream().mapToDouble(fill -> contrast(palette[1], fill)).min().orElse(Double.MAX_VALUE);
            double worst = Math.min(day, night);
            hits.add(new String[]{colorMatcher.group(1), nearest,
                    String.valueOf(worst), String.valueOf(day), String.valueOf(night)});
        }
        return hits;
    }
}
