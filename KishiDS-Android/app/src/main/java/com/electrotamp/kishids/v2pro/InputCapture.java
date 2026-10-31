package com.electrotamp.kishids.v2pro;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guided capture of a Kishi V2 Pro's input reports: the user presses one control at a time and the report fields that react are recorded.
 * The result maps physical controls to report fields, which is what a translating firmware needs and the report descriptor cannot say.
 *
 * <p>Interface 0 report 1 is decoded with the layout measured from the controller's own report descriptor (see
 * {@code firmware-research/v2pro/USB_DESCRIPTORS.md}); interface 0/1 report 3 as the declared consumer-control bitmap.  Any other report
 * is compared byte by byte with the first copy of it seen during the session.  Every distinct report is also kept raw, so nothing depends
 * on this decoding being right.
 *
 * <p>Pure Java and thread-safe: a USB reader thread calls {@link #feed}, the UI calls the rest.
 */
public final class InputCapture {
    /** Report 1 on interface 0, as declared by the measured descriptor: 13 bytes including the ID. */
    public static final int GAMEPAD_IFACE = 0, GAMEPAD_ID = 1, GAMEPAD_BYTES = 13, CONSUMER_ID = 3, CONSUMER_BYTES = 9;
    /** How far an axis must move from rest to count as pressed. */
    public static final int AXIS_THRESHOLD = 64;
    private static final String[] AXES = { "X", "Y", "Z", "Rz" };
    private static final String[] CONSUMER = { "AC Home", "AC Back", "Menu", "Volume Up", "Volume Down", "Power", "Sleep", "Task switcher" };
    private static final int MAX_RAW = 600;

    /** The controls asked for, in order.  Optional ones may not exist on every unit and are simply skipped with Next. */
    public static final String[] STEPS = {
            "A", "B", "X", "Y",
            "D-pad up", "D-pad right", "D-pad down", "D-pad left",
            "Left bumper (LB)", "Right bumper (RB)", "Left trigger (LT), pull fully", "Right trigger (RT), pull fully",
            "Left stick: push up", "Left stick: push right", "Left stick: click (L3)",
            "Right stick: push up", "Right stick: push right", "Right stick: click (R3)",
            "View (left small button)", "Menu (right small button)", "Razer / home button (optional, may leave the app)",
            "Screenshot button (optional)", "M1 (optional)", "M2 (optional)",
    };

    public static final class Result {
        public final String control;
        public final List<String> fields;
        public final boolean skipped;

        Result(String control, List<String> fields, boolean skipped) {
            this.control = control;
            this.fields = fields;
            this.skipped = skipped;
        }
    }

    private final Map<String, byte[]> firstSeen = new LinkedHashMap<>();
    private final Map<String, byte[]> last = new LinkedHashMap<>();
    private final List<String> raw = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private final Set<String> observed = new LinkedHashSet<>();
    private final Map<String, Integer> extremes = new LinkedHashMap<>();
    private int[] axisRest = { 0x80, 0x80, 0x80, 0x80 }, triggerRest = { 0, 0 };
    private boolean restMeasured, settling = true, finished;
    private int step, reports, rawDropped;
    private long startMs = -1;
    private String live = "";

    /** The first {@code settleMs} of reports are taken as hands-off rest values; without any, axes rest at 128 and triggers at 0. */
    private final long settleMs;

    public InputCapture(long settleMs) { this.settleMs = settleMs; }

    // ---------------------------------------------------------------- input

    public synchronized void feed(int iface, byte[] data, int len, long ms) {
        if (finished || len <= 0) return;
        if (startMs < 0) startMs = ms;
        byte[] r = new byte[len];
        System.arraycopy(data, 0, r, 0, len);
        reports++;
        String key = iface + ":" + (r[0] & 0xFF);
        byte[] prev = last.get(key);
        if (prev != null && java.util.Arrays.equals(prev, r)) return;   // only changes are interesting
        last.put(key, r);
        if (!firstSeen.containsKey(key)) firstSeen.put(key, r);
        rememberRaw(ms, iface, r);
        if (iface == GAMEPAD_IFACE && isGamepad(r)) live = describeGamepad(r);

        if (settling) {
            if (iface == GAMEPAD_IFACE && isGamepad(r)) {
                for (int a = 0; a < 4; a++) axisRest[a] = r[1 + a] & 0xFF;
                triggerRest[0] = r[9] & 0xFF;
                triggerRest[1] = r[10] & 0xFF;
                restMeasured = true;
            }
            if (ms - startMs < settleMs) return;
            settling = false;
        }

        List<String> active = activeFields();
        for (String f : active) {
            observed.add(f);
            noteExtreme(f);
        }
        if (active.isEmpty() && !observed.isEmpty()) advance(false);   // pressed, then released: this control is done
    }

    /** Ends the settling period even if no report arrived (many controllers only report changes). */
    public synchronized void tick(long ms) {
        if (startMs < 0) startMs = ms;
        if (settling && ms - startMs >= settleMs) settling = false;
    }

    /** Records whatever was seen for the current control (possibly nothing) and moves on. */
    public synchronized void next() { if (!finished && !settling) advance(observed.isEmpty()); }

    public synchronized void finish() { finished = true; }

    private void advance(boolean skipped) {
        List<String> fields = new ArrayList<>();
        for (String f : observed) {
            Integer x = extremes.get(f);
            fields.add(x == null ? f : f + " (reached " + x + ")");
        }
        results.add(new Result(STEPS[step], fields, skipped));
        observed.clear();
        extremes.clear();
        if (++step >= STEPS.length) finished = true;
    }

    // ---------------------------------------------------------------- decoding

    static boolean isGamepad(byte[] r) { return r.length >= GAMEPAD_BYTES && (r[0] & 0xFF) == GAMEPAD_ID; }

    /** Buttons are packed LSB first after the 32 axis bits and the 4 hat bits: payload bit 36 is button 1. */
    static boolean button(byte[] r, int n) {
        int bit = 36 + n - 1;
        return ((r[1 + bit / 8] >> (bit % 8)) & 1) != 0;
    }

    static int hat(byte[] r) { return r[5] & 0x0F; }

    private List<String> activeFields() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : last.entrySet()) {
            byte[] r = e.getValue();
            int iface = Integer.parseInt(e.getKey().substring(0, e.getKey().indexOf(':')));
            if (iface == GAMEPAD_IFACE && isGamepad(r)) {
                for (int n = 1; n <= 18; n++) if (button(r, n)) out.add("report 1 button " + n);
                int h = hat(r);
                if (h <= 7) out.add("report 1 hat = " + h + " (" + (h * 45) + " deg)");
                for (int a = 0; a < 4; a++) {
                    int v = r[1 + a] & 0xFF, d = v - axisRest[a];
                    if (Math.abs(d) >= AXIS_THRESHOLD) out.add("report 1 axis " + AXES[a] + (d < 0 ? " low" : " high"));
                }
                if ((r[9] & 0xFF) - triggerRest[0] >= AXIS_THRESHOLD) out.add("report 1 Brake");
                if ((r[10] & 0xFF) - triggerRest[1] >= AXIS_THRESHOLD) out.add("report 1 Accelerator");
                if ((r[11] & 0xFF) != (firstSeen.get(e.getKey())[11] & 0xFF) || (r[12] & 0xFF) != (firstSeen.get(e.getKey())[12] & 0xFF)) {
                    out.add(String.format("report 1 vendor bytes = %02X %02X", r[11] & 0xFF, r[12] & 0xFF));
                }
            } else if ((iface == 0 || iface == 1) && (r[0] & 0xFF) == CONSUMER_ID && r.length >= 2) {
                for (int b = 0; b < 8; b++) if (((r[1] >> b) & 1) != 0) out.add("iface " + iface + " report 3 " + CONSUMER[b]);
            } else {
                byte[] base = firstSeen.get(e.getKey());
                for (int i = 1; i < r.length; i++) {
                    int was = i < base.length ? base[i] & 0xFF : -1;
                    if ((r[i] & 0xFF) != was) out.add(String.format("iface %d report %d byte %d = %02X (first seen %s)", iface, r[0] & 0xFF, i, r[i] & 0xFF,
                            was < 0 ? "absent" : String.format("%02X", was)));
                }
            }
        }
        return out;
    }

    /** Axis and trigger entries keep the furthest value reached, which shows direction and full travel. */
    private void noteExtreme(String f) {
        byte[] r = last.get(GAMEPAD_IFACE + ":" + GAMEPAD_ID);
        if (r == null || !isGamepad(r)) return;
        int v = -1;
        for (int a = 0; a < 4; a++) if (f.startsWith("report 1 axis " + AXES[a] + " ")) v = r[1 + a] & 0xFF;
        if (f.equals("report 1 Brake")) v = r[9] & 0xFF;
        if (f.equals("report 1 Accelerator")) v = r[10] & 0xFF;
        if (v < 0) return;
        Integer old = extremes.get(f);
        boolean low = f.endsWith(" low");
        if (old == null || (low ? v < old : v > old)) extremes.put(f, v);
    }

    static String describeGamepad(byte[] r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("X %3d  Y %3d  Z %3d  Rz %3d  hat %2d  brake %3d  accel %3d  vendor %02X %02X\nbuttons:",
                r[1] & 0xFF, r[2] & 0xFF, r[3] & 0xFF, r[4] & 0xFF, hat(r), r[9] & 0xFF, r[10] & 0xFF, r[11] & 0xFF, r[12] & 0xFF));
        boolean any = false;
        for (int n = 1; n <= 18; n++) if (button(r, n)) { sb.append(' ').append(n); any = true; }
        if (!any) sb.append(" none");
        return sb.toString();
    }

    private void rememberRaw(long ms, int iface, byte[] r) {
        if (raw.size() >= MAX_RAW) { rawDropped++; return; }
        StringBuilder sb = new StringBuilder(String.format("%6d ms  if%d  [%s]  ", ms - startMs, iface, settling ? "rest" : finished ? "-" : "step " + (step + 1)));
        for (byte b : r) sb.append(String.format("%02X ", b & 0xFF));
        raw.add(sb.toString().trim());
    }

    // ---------------------------------------------------------------- state for the UI

    public synchronized boolean finished() { return finished; }

    public synchronized boolean settling() { return settling; }

    public synchronized int reports() { return reports; }

    public synchronized List<Result> results() { return new ArrayList<>(results); }

    /** What to ask the user now. */
    public synchronized String prompt() {
        if (finished) return "Done. Copy or save the report below.";
        if (settling) return "Hands off: don't touch the controller for a moment.";
        return "Step " + (step + 1) + " of " + STEPS.length + ": press and release " + STEPS[step] + ".";
    }

    /** What the current press has triggered so far, for the screen. */
    public synchronized String observedText() {
        if (observed.isEmpty()) return "Nothing seen yet for this control.";
        return "Seen: " + String.join(", ", observed);
    }

    public synchronized String liveText() { return live.isEmpty() ? "No gamepad report received yet." : live; }

    /** The mapping table and the raw reports, as plain text. */
    public synchronized String report() {
        StringBuilder sb = new StringBuilder("KishiDS V2 Pro input capture\n\n");
        sb.append(restMeasured ? String.format("Rest values measured: axes %d %d %d %d, brake %d, accelerator %d.\n", axisRest[0], axisRest[1], axisRest[2], axisRest[3], triggerRest[0], triggerRest[1])
                : "No report arrived while hands-off; assumed axes rest at 128 and triggers at 0.\n");
        sb.append("Reports received: ").append(reports).append(", distinct kept: ").append(raw.size());
        if (rawDropped > 0) sb.append(", not kept: ").append(rawDropped);
        sb.append("\n\nControl -> report fields\n");
        for (Result r : results) {
            sb.append(r.control).append(": ");
            sb.append(r.skipped ? "skipped (nothing seen)" : String.join("; ", r.fields)).append('\n');
        }
        if (results.size() < STEPS.length) sb.append("(stopped after ").append(results.size()).append(" of ").append(STEPS.length).append(" controls)\n");
        sb.append("\nRaw reports (only changes)\n");
        for (String s : raw) sb.append(s).append('\n');
        return sb.toString();
    }
}
