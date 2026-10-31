package com.electrotamp.kishids.v2pro;

import com.electrotamp.kishids.core.DfuFlasher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Collects the HID report descriptor of every HID interface of a V2 Pro.  Reading one needs the interface claimed, so the claims go
 * through {@link Claims}; the phone supplies real ones and the JVM tests a fake device.  All requests pass through {@link ReadOnlyPipe}.
 * A failure on one interface is recorded and the next one is still tried: a partial report is worth more than none.
 */
public final class V2ProProbe {
    private V2ProProbe() {}

    /** Largest descriptor requested when the configuration does not state a length (usbhid's HID_MAX_DESCRIPTOR_SIZE). */
    public static final int MAX_DESCRIPTOR = 4096;
    private static final int TIMEOUT_MS = 1000, ATTEMPTS = 2;

    public interface Claims {
        boolean claim(int iface);
        void release(int iface);
    }

    public static final class Entry {
        public final int iface;
        public byte[] raw;
        public HidDescriptor parsed;
        public String error;

        Entry(int iface) { this.iface = iface; }
    }

    public static List<Entry> run(UsbLayout layout, DfuFlasher.Pipe pipe, Claims claims) {
        ReadOnlyPipe guard = new ReadOnlyPipe(pipe);
        List<Entry> out = new ArrayList<>();
        for (UsbLayout.Iface i : layout.mainInterfaces()) {
            Entry e = new Entry(i.number);
            out.add(e);
            if (!i.hid()) { e.error = String.format("Skipped: class %02X is not HID.", i.cls); continue; }
            if (!claims.claim(i.number)) { e.error = "Could not claim the interface."; continue; }
            try { read(e, i, guard); }
            finally { claims.release(i.number); }
        }
        return out;
    }

    private static void read(Entry e, UsbLayout.Iface i, ReadOnlyPipe pipe) {
        int want = i.reportLength > 0 ? i.reportLength : MAX_DESCRIPTOR;
        byte[] buf = new byte[want];
        int n = -1;
        for (int a = 0; a < ATTEMPTS && n <= 0; a++) {
            n = pipe.control(ReadOnlyPipe.REQUEST_TYPE, ReadOnlyPipe.GET_DESCRIPTOR, ReadOnlyPipe.REPORT_DESCRIPTOR, i.number, buf, want, TIMEOUT_MS);
        }
        if (n <= 0) { e.error = "GET_DESCRIPTOR failed (" + n + ")."; return; }
        e.raw = Arrays.copyOf(buf, n);
        e.parsed = HidDescriptor.parse(e.raw);
        if (i.reportLength > 0 && n != i.reportLength) e.error = "Received " + n + " bytes, the configuration states " + i.reportLength + ".";
    }

    /** The prototype's 13-byte layout measured against collected descriptors, in words that do not overstate a size match. */
    public static String hypothesisCheck(List<Entry> entries) {
        for (Entry e : entries) {
            if (e.iface != 0) continue;
            if (e.parsed == null) return "Input report 1 hypothesis: not checked, interface 0 was not read.";
            HidDescriptor.Report r = e.parsed.report(HidDescriptor.INPUT, V2ProExpected.HYPOTHESIS_REPORT_ID);
            if (r == null) return "Input report 1 hypothesis: rejected, interface 0 declares no input report 1.";
            if (r.bytes() != V2ProExpected.HYPOTHESIS_REPORT_BYTES) return "Input report 1 hypothesis: rejected, the descriptor says " + r.bytes() + " bytes, not " + V2ProExpected.HYPOTHESIS_REPORT_BYTES + ".";
            return "Input report 1 hypothesis: size matches (" + r.bytes() + " bytes). Field order is still unverified.";
        }
        return "Input report 1 hypothesis: not checked, no interface 0.";
    }

    /** The whole report as plain text, for the screen and for sharing. */
    public static String report(UsbLayout layout, List<Entry> entries, String footer) {
        StringBuilder sb = new StringBuilder("KishiDS V2 Pro USB diagnostics\n\n");
        sb.append(layout.text()).append('\n');
        List<String> diff = V2ProExpected.differences(layout);
        if (diff.isEmpty()) sb.append("Layout matches the recorded V2 Pro table.\n");
        else for (String d : diff) sb.append("Difference: ").append(d).append('\n');
        sb.append("DFU interface in this mode: ").append(layout.hasDfuInterface() ? "yes" : "no").append('\n');
        for (Entry e : entries) {
            sb.append(String.format("\n--- Interface %d (%s) ---\n", e.iface, V2ProExpected.role(e.iface)));
            if (e.error != null) sb.append(e.error).append('\n');
            if (e.parsed != null) sb.append(e.parsed.text());
            if (e.raw != null) sb.append("Raw (").append(e.raw.length).append(" bytes):\n").append(hex(e.raw)).append('\n');
        }
        if (!entries.isEmpty()) sb.append('\n').append(hypothesisCheck(entries)).append('\n');
        if (footer != null && !footer.isEmpty()) sb.append('\n').append(footer).append('\n');
        return sb.toString();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            sb.append(String.format("%02X", b[i]));
            sb.append((i + 1) % 16 == 0 ? '\n' : ' ');
        }
        return sb.toString().trim();
    }
}
