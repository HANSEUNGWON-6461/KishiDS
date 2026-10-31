import com.electrotamp.kishids.core.DfuFlasher;
import com.electrotamp.kishids.v2pro.HidDescriptor;
import com.electrotamp.kishids.v2pro.InputCapture;
import com.electrotamp.kishids.v2pro.KishiModel;
import com.electrotamp.kishids.v2pro.ReadOnlyPipe;
import com.electrotamp.kishids.v2pro.UsbLayout;
import com.electrotamp.kishids.v2pro.V2ProExpected;
import com.electrotamp.kishids.v2pro.V2ProProbe;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain-JVM checks of the V2 Pro module.  Every device here is simulated: the descriptors are built by hand from the recorded USB
 * layout, so a pass shows the code handles that shape, not that a real controller looks like it.
 */
public final class V2ProSelfTest {
    static int failures;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // ---- identification ----
        check("V2 Pro is identified", KishiModel.classify(0x1532, 0x0717) == KishiModel.V2_PRO);
        check("V1 stock and bootloader keep their identities", KishiModel.classify(0x27F8, 0x0BBF) == KishiModel.V1_STOCK && KishiModel.classify(0x27F8, 0x0BC0) == KishiModel.V1_BOOTLOADER);
        check("a matching PID under another vendor is not a V2 Pro", KishiModel.classify(0x27F8, 0x0717) == KishiModel.UNKNOWN);
        check("another Razer product is not a V2 Pro", KishiModel.classify(0x1532, 0x0718) == KishiModel.UNKNOWN);
        check("a DualShock 4 is not a Kishi model", KishiModel.classify(0x054C, 0x05C4) == KishiModel.UNKNOWN);

        // ---- flash gate ----
        int[] v1Boot = { 0x27F8, 0x0BC0 }, v2 = { 0x1532, 0x0717 }, other = { 0x18D1, 0x4EE1 };
        check("flash gate: V1 bootloader alone is allowed", KishiModel.v1FlashAllowed(new int[][] { v1Boot }));
        check("flash gate: V1 bootloader beside an unrelated device is allowed", KishiModel.v1FlashAllowed(new int[][] { other, v1Boot }));
        check("flash gate: V2 Pro alone is refused", !KishiModel.v1FlashAllowed(new int[][] { v2 }));
        check("flash gate: V2 Pro beside a V1 bootloader is refused, in either order",
                !KishiModel.v1FlashAllowed(new int[][] { v1Boot, v2 }) && !KishiModel.v1FlashAllowed(new int[][] { v2, v1Boot }));
        check("flash gate: nothing attached is refused", !KishiModel.v1FlashAllowed(new int[0][]));

        // ---- descriptors ----
        byte[] gamepad = gamepadDescriptor();
        byte[] raw = rawDescriptors(gamepad.length);
        UsbLayout l = UsbLayout.parseRaw(raw);
        check("raw: identity and versions", l.vid == 0x1532 && l.pid == 0x0717 && l.bcdDevice == 0x0123 && l.bcdUsb == 0x0200);
        check("raw: five interfaces with their endpoints", l.mainInterfaces().size() == 5 && l.interfaces.get(0).endpoints.get(0).address == 0x81
                && l.interfaces.get(3).endpoints.get(0).address == 0x02 && !l.interfaces.get(3).endpoints.get(0).in());
        check("raw: report descriptor length comes from the HID descriptor", l.interfaces.get(0).reportLength == gamepad.length);
        check("raw: the recorded layout produces no differences", V2ProExpected.differences(l).isEmpty());
        check("raw: no DFU interface", !l.hasDfuInterface());
        check("raw: a truncated buffer stops without throwing", UsbLayout.parseRaw(Arrays.copyOf(raw, 31)).vid == 0x1532);
        check("raw: a zero-length descriptor stops without throwing", UsbLayout.parseRaw(new byte[] { 0, 0, 0, 0 }).interfaces.isEmpty());
        l.interfaces.remove(4);
        List<String> diff = V2ProExpected.differences(l);
        check("expected: a missing interface and endpoint are reported", diff.size() == 2 && diff.get(0).contains("count is 4"));
        l = UsbLayout.parseRaw(raw);

        HidDescriptor h = HidDescriptor.parse(gamepad);
        HidDescriptor.Report in1 = h.report(HidDescriptor.INPUT, 1);
        check("hid: input report 1 is 13 bytes with its ID", in1 != null && in1.bytes() == 13 && in1.bits == 96);
        check("hid: top collection is a gamepad", h.topUsagePage == 1 && h.topUsage == 5);
        check("hid: feature report is kept apart", h.report(HidDescriptor.FEATURE, 2).bytes() == 9);
        check("hid: an unknown report is null", h.report(HidDescriptor.INPUT, 9) == null);
        check("hid: push and pop restore the report size", HidDescriptor.parse(new byte[] { 0x75, 8, (byte) 0x95, 1, (byte) 0xA4, 0x75, 1, (byte) 0xB4, (byte) 0x81, 2 }).report(HidDescriptor.INPUT, 0).bits == 8);
        check("hid: an unnumbered report has no ID byte", HidDescriptor.parse(new byte[] { 0x75, 8, (byte) 0x95, 2, (byte) 0x81, 2 }).report(HidDescriptor.INPUT, 0).bytes() == 2);
        check("hid: a long item is skipped", HidDescriptor.parse(new byte[] { (byte) 0xFE, 2, 0x10, 1, 2, 0x75, 8, (byte) 0x95, 1, (byte) 0x81, 2 }).report(HidDescriptor.INPUT, 0) != null);
        check("hid: a truncated item warns", HidDescriptor.parse(new byte[] { 0x26, 1 }).warning != null);
        check("hid: an unbalanced pop warns", HidDescriptor.parse(new byte[] { (byte) 0xB4 }).warning != null);
        check("hid: an unclosed collection warns", HidDescriptor.parse(new byte[] { (byte) 0xA1, 1 }).warning != null);
        check("hid: random bytes never throw", fuzz());

        // ---- read-only guard ----
        int[] reached = { 0 };
        ReadOnlyPipe guard = new ReadOnlyPipe((t, r, v, i, b, n, ms) -> { reached[0]++; return n; });
        int[][] writes = { { 0x21, 0x09, 0x0301 }, { 0x21, 0x0A, 0 }, { 0x21, 0x0B, 0 }, { 0xA1, 0x01, 0x0301 }, { 0x21, 0x01, 0 }, { 0x21, 0x00, 0 },
                { 0xA1, 0x03, 0 }, { 0x41, 0x01, 0 }, { 0xC1, 0x01, 0 }, { 0x00, 0x09, 1 }, { 0x81, 0x06, 0x2100 }, { 0x81, 0x06, 0x2201 } };
        boolean stopped = true;
        for (int[] w : writes) stopped &= guard.control(w[0], w[1], w[2], 0, new byte[8], 8, 100) == ReadOnlyPipe.REFUSED;
        check("guard: SET_REPORT, GET_REPORT, DFU, vendor and other descriptors never reach the device", stopped && reached[0] == 0 && guard.refused == writes.length);
        check("guard: a report descriptor read passes", guard.control(0x81, 0x06, 0x2200, 0, new byte[8], 8, 100) == 8 && reached[0] == 1);

        // ---- probe against a simulated controller ----
        FakeV2 dev = new FakeV2(gamepad);
        List<V2ProProbe.Entry> res = V2ProProbe.run(l, dev, dev);
        check("probe: all five interfaces were read", res.size() == 5 && allRead(res));
        check("probe: only report descriptor reads went out", dev.foreign == 0 && dev.requests == 5);
        check("probe: every claim was released", dev.claimed.isEmpty() && dev.claims == 5);
        check("probe: size hypothesis is stated with its limit", V2ProProbe.hypothesisCheck(res).contains("size matches") && V2ProProbe.hypothesisCheck(res).contains("unverified"));
        String text = V2ProProbe.report(l, res, null);
        check("report: identity, table result and raw bytes are present", text.contains("1532:0717") && text.contains("matches the recorded") && text.contains("05 01 09 05"));

        FakeV2 stall = new FakeV2(gamepad);
        stall.failing.add(2);
        res = V2ProProbe.run(l, stall, stall);
        check("probe: a stalled interface is reported and the rest still run", res.get(2).error != null && res.get(2).raw == null && res.get(4).raw != null);
        check("probe: a stalled interface is retried once, then released", stall.perInterface(2) == 2 && stall.claimed.isEmpty());

        FakeV2 busy = new FakeV2(gamepad);
        busy.refuseClaim.add(1);
        res = V2ProProbe.run(l, busy, busy);
        check("probe: a refused claim sends nothing to that interface", res.get(1).error.contains("claim") && busy.perInterface(1) == 0 && res.get(0).raw != null);

        FakeV2 unplugged = new FakeV2(gamepad);
        unplugged.gone = true;
        res = V2ProProbe.run(l, unplugged, unplugged);
        check("probe: an unplugged device fails cleanly and releases everything", res.size() == 5 && res.get(0).error != null && unplugged.claimed.isEmpty());
        check("probe: a report still builds after a total failure", V2ProProbe.report(l, res, "x").contains("GET_DESCRIPTOR failed"));

        FakeV2 shortRead = new FakeV2(Arrays.copyOf(gamepad, gamepad.length - 5));
        res = V2ProProbe.run(l, shortRead, shortRead);
        check("probe: a short descriptor keeps the bytes and says so", res.get(0).error.contains("Received") && res.get(0).raw.length == gamepad.length - 5);

        byte[] wrongReport = bytes(0x85, 1, 0x75, 8, 0x95, 5, 0x81, 2);
        FakeV2 other2 = new FakeV2(wrongReport);
        res = V2ProProbe.run(UsbLayout.parseRaw(rawDescriptors(wrongReport.length)), other2, other2);
        check("hypothesis: a different size rejects the prototype's layout", V2ProProbe.hypothesisCheck(res).contains("rejected"));

        // ---- recovery-style mode: a DFU interface must be noticed, and not treated as HID ----
        byte[] dfuMode = dfuRawDescriptors();
        UsbLayout dl = UsbLayout.parseRaw(dfuMode);
        check("raw: a DFU interface is noticed", dl.hasDfuInterface() && !dl.interfaces.get(0).hid());
        FakeV2 dfu = new FakeV2(gamepad);
        res = V2ProProbe.run(dl, dfu, dfu);
        check("probe: a non-HID interface is skipped without a claim or a request", res.get(0).error.contains("not HID") && dfu.claims == 0 && dfu.requests == 0);

        captureTests();

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Interface 0's report descriptor exactly as read from a real V2 Pro on 2026-11-01 (firmware-research/v2pro/USB_DESCRIPTORS.md). */
    static final String MEASURED_IF0 = "05 01 09 05 A1 01 85 01 09 30 09 31 09 32 09 35 15 00 26 FF 00 75 08 95 04 81 02 09 39 15 00 25 "
            + "07 35 00 46 3B 01 65 14 75 04 95 01 81 42 65 00 05 09 19 01 29 12 15 00 25 01 75 01 95 12 81 02 75 0A 95 01 81 03 05 02 09 C5 09 C4 15 00 26 FF "
            + "00 35 00 46 FF 00 75 08 95 02 81 02 06 00 FF 09 21 95 02 81 02 C0 05 0C 09 01 A1 01 85 03 0A 23 02 0A 24 02 09 40 09 E9 09 EA 09 30 09 32 0A A2 "
            + "01 15 00 25 01 75 01 95 08 81 02 75 08 95 07 81 01 C0";

    static byte[] hex(String h) {
        String[] p = h.trim().split("\\s+");
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) b[i] = (byte) Integer.parseInt(p[i], 16);
        return b;
    }

    /** A gamepad report 1 at rest, then modified by the caller. */
    static byte[] rest() { return bytes(1, 0x80, 0x80, 0x80, 0x80, 0x0F, 0, 0, 0, 0, 0, 0, 0); }

    static byte[] withButton(int n) {
        byte[] r = rest();
        int bit = 36 + n - 1;
        r[1 + bit / 8] |= 1 << (bit % 8);
        return r;
    }

    static void captureTests() {
        // ---- the decoder's fixed offsets must agree with the measured descriptor ----
        byte[] measured = hex(MEASURED_IF0);
        check("measured: interface 0 descriptor is 146 bytes", measured.length == 146);
        HidDescriptor md = HidDescriptor.parse(measured);
        HidDescriptor.Report r1 = md.report(HidDescriptor.INPUT, 1);
        check("measured: report 1 is 13 bytes and report 3 is 9", r1 != null && r1.bytes() == InputCapture.GAMEPAD_BYTES
                && md.report(HidDescriptor.INPUT, 3).bytes() == InputCapture.CONSUMER_BYTES && md.warning == null);
        int at = 0, buttonsAt = -1, hatAt = -1, brakeAt = -1;
        for (HidDescriptor.Field f : r1.fields) {
            if (f.usagePage == 9 && !f.constant()) buttonsAt = at;
            if (f.usagePage == 1 && f.firstUsage == 0x39) hatAt = at;
            if (f.usagePage == 2 && f.firstUsage == 0xC5) brakeAt = at;
            at += f.bitSize * f.count;
        }
        check("measured: hat at payload bit 32, buttons at bit 36, brake at bit 64", hatAt == 32 && buttonsAt == 36 && brakeAt == 64);

        // ---- guided capture against simulated reports ----
        InputCapture c = new InputCapture(1000);
        c.feed(0, rest(), 13, 0);
        check("capture: starts by asking for hands off", c.settling() && c.prompt().contains("Hands off"));
        c.feed(0, withButton(1), 13, 500);
        c.feed(0, rest(), 13, 600);
        c.tick(1000);
        check("capture: a press while settling is not recorded", !c.settling() && c.results().isEmpty() && c.prompt().contains("Step 1"));

        c.feed(0, withButton(1), 13, 1100);
        check("capture: a pressed button shows while held", c.observedText().contains("button 1"));
        c.feed(0, rest(), 13, 1200);
        List<InputCapture.Result> res = c.results();
        check("capture: release records the control and moves on", res.size() == 1 && res.get(0).control.equals("A") && res.get(0).fields.equals(Arrays.asList("report 1 button 1")));

        byte[] b18 = withButton(18);
        c.feed(0, b18, 13, 1300);
        c.feed(0, rest(), 13, 1400);
        check("capture: button 18 is the last bit before the padding", c.results().get(1).fields.get(0).equals("report 1 button 18"));

        c.next();                                                                     // X: nothing pressed
        check("capture: Next with nothing seen is recorded as skipped", c.results().get(2).skipped);

        byte[] hatUp = rest();
        hatUp[5] = 0x00;
        c.feed(0, hatUp, 13, 1500);
        c.feed(0, rest(), 13, 1600);
        check("capture: hat value 0 is reported with its angle", c.results().get(3).fields.get(0).equals("report 1 hat = 0 (0 deg)"));

        byte[] stick = rest();
        stick[2] = 0x30;
        c.feed(0, stick, 13, 1700);
        stick[2] = 0x00;
        c.feed(0, stick, 13, 1710);
        stick[2] = 0x40;
        c.feed(0, stick, 13, 1720);
        c.feed(0, rest(), 13, 1800);
        check("capture: an axis records direction and the furthest value", c.results().get(4).fields.get(0).equals("report 1 axis Y low (reached 0)"));

        byte[] brake = rest();
        brake[9] = (byte) 0xFF;
        c.feed(0, brake, 13, 1900);
        c.feed(0, rest(), 13, 2000);
        check("capture: the brake byte is a trigger", c.results().get(5).fields.get(0).equals("report 1 Brake (reached 255)"));

        c.feed(1, bytes(3, 0x01, 0, 0, 0, 0, 0, 0, 0), 9, 2100);
        c.feed(1, bytes(3, 0x00, 0, 0, 0, 0, 0, 0, 0), 9, 2200);
        check("capture: a consumer bit on interface 1 is named", c.results().get(6).fields.get(0).equals("iface 1 report 3 AC Home"));

        c.feed(2, bytes(7, 0, 0, 0, 0, 0, 0, 0, 0), 9, 2300);                      // first copy of an unknown report: the reference
        c.feed(2, bytes(7, 0, 0x04, 0, 0, 0, 0, 0, 0), 9, 2400);
        c.feed(2, bytes(7, 0, 0, 0, 0, 0, 0, 0, 0), 9, 2500);
        check("capture: an unknown report is compared byte by byte", c.results().get(7).fields.get(0).startsWith("iface 2 report 7 byte 2 = 04"));

        int before = c.reports();
        c.feed(0, rest(), 13, 2600);
        c.feed(0, rest(), 13, 2610);
        check("capture: repeats are counted but not kept twice", c.reports() == before + 2 && c.results().size() == 8 && c.report().split("\n").length < 60);

        String text = c.report();
        check("capture: the report has the rest values, the table and the raw bytes",
                text.contains("Rest values measured: axes 128 128 128 128") && text.contains("A: report 1 button 1") && text.contains("01 80 80 80 80 0F")
                        && text.contains("stopped after 8 of"));
        c.finish();
        c.feed(0, withButton(2), 13, 3000);
        check("capture: nothing is taken after finish", c.reports() == before + 2 && c.finished());

        InputCapture quiet = new InputCapture(1000);
        quiet.tick(0);
        quiet.tick(1000);
        quiet.feed(0, withButton(3), 13, 1100);
        quiet.feed(0, rest(), 13, 1200);
        check("capture: with no rest report, settling ends on time and defaults are stated",
                !quiet.settling() && quiet.results().size() == 1 && quiet.report().contains("assumed axes rest at 128"));

        InputCapture all = new InputCapture(0);
        all.tick(0);
        for (int i = 0; i < InputCapture.STEPS.length; i++) all.next();
        check("capture: finishes after the last control", all.finished() && all.results().size() == InputCapture.STEPS.length && all.prompt().startsWith("Done"));
    }

    static boolean allRead(List<V2ProProbe.Entry> res) {
        for (V2ProProbe.Entry e : res) if (e.raw == null || e.error != null) return false;
        return true;
    }

    static boolean fuzz() {
        java.util.Random r = new java.util.Random(0x0717);
        try {
            for (int i = 0; i < 20000; i++) {
                byte[] b = new byte[r.nextInt(64)];
                r.nextBytes(b);
                HidDescriptor.parse(b).text();
                UsbLayout.parseRaw(b).text();
            }
            return true;
        } catch (RuntimeException e) {
            System.out.println(e);
            return false;
        }
    }

    /** A gamepad collection with input report 1 of 12 payload bytes, the size the earlier prototype assumed, and a feature report. */
    static byte[] gamepadDescriptor() {
        return bytes(0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01,
                0x09, 0x30, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,     // four axes
                0x75, 0x04, 0x95, 0x01, 0x81, 0x42,                 // hat
                0x05, 0x09, 0x19, 0x01, 0x29, 0x12, 0x75, 0x01, 0x95, 0x12, 0x81, 0x02,   // 18 buttons
                0x75, 0x0A, 0x95, 0x01, 0x81, 0x03,                 // padding up to the next byte boundary
                0x75, 0x08, 0x95, 0x04, 0x81, 0x02,                 // brake, accelerator, two vendor bytes
                0x85, 0x02, 0x75, 0x08, 0x95, 0x08, 0xB1, 0x02, 0xC0);
    }

    static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    static void put(ByteArrayOutputStream o, int... v) { for (int x : v) o.write(x); }

    static void device(ByteArrayOutputStream o) {
        put(o, 18, 1, 0x00, 0x02, 0, 0, 0, 64, 0x32, 0x15, 0x17, 0x07, 0x23, 0x01, 1, 2, 3, 1);
    }

    static void iface(ByteArrayOutputStream o, int n, int reportLength, int... endpoints) {
        put(o, 9, 4, n, 0, endpoints.length, 3, 0, 0, 0);
        put(o, 9, 0x21, 0x11, 0x01, 0, 1, 0x22, reportLength & 0xFF, reportLength >> 8);
        for (int e : endpoints) put(o, 7, 5, e, 3, 64, 0, 1);
    }

    /** The recorded layout: five HID interfaces, IN 0x81-0x83, OUT 0x02-0x03. */
    static byte[] rawDescriptors(int reportLength) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        device(o);
        put(o, 9, 2, 0, 0, 5, 1, 0, 0x80, 50);
        iface(o, 0, reportLength, 0x81);
        iface(o, 1, reportLength, 0x82);
        iface(o, 2, reportLength, 0x83);
        iface(o, 3, reportLength, 0x02);
        iface(o, 4, reportLength, 0x03);
        return o.toByteArray();
    }

    static byte[] dfuRawDescriptors() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        device(o);
        put(o, 9, 2, 0, 0, 1, 1, 0, 0x80, 50);
        put(o, 9, 4, 0, 0, 0, 0xFE, 0x01, 0x02, 0);
        return o.toByteArray();
    }

    /** A simulated controller that records everything it is asked to do. */
    static final class FakeV2 implements DfuFlasher.Pipe, V2ProProbe.Claims {
        final byte[] descriptor;
        final Set<Integer> claimed = new HashSet<>(), failing = new HashSet<>(), refuseClaim = new HashSet<>();
        final List<Integer> seen = new ArrayList<>();
        int requests, foreign, claims;
        boolean gone;

        FakeV2(byte[] descriptor) { this.descriptor = descriptor; }

        int perInterface(int iface) {
            int n = 0;
            for (int i : seen) if (i == iface) n++;
            return n;
        }

        @Override public boolean claim(int iface) {
            if (refuseClaim.contains(iface)) return false;
            claims++;
            claimed.add(iface);
            return true;
        }

        @Override public void release(int iface) { claimed.remove(iface); }

        @Override public int control(int type, int req, int value, int index, byte[] buf, int len, int timeout) {
            requests++;
            seen.add(index);
            if (type != 0x81 || req != 6 || value != 0x2200 || !claimed.contains(index)) { foreign++; return -1; }
            if (gone || failing.contains(index)) return -1;
            int n = Math.min(len, descriptor.length);
            System.arraycopy(descriptor, 0, buf, 0, n);
            return n;
        }
    }
}
