package com.electrotamp.kishids.v2pro;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a HID report descriptor (HID 1.11 section 6.2.2) into the reports it declares.  Only sizes and usages are extracted: enough to
 * show what an interface sends and accepts.  Malformed input ends the parse with {@link #warning} set instead of throwing, because the
 * bytes come straight from an unknown device.
 */
public final class HidDescriptor {
    public static final int INPUT = 0, OUTPUT = 1, FEATURE = 2;
    private static final String[] KIND_NAMES = { "Input", "Output", "Feature" };

    public static final class Field {
        public final int usagePage, firstUsage, bitSize, count, flags;

        Field(int usagePage, int firstUsage, int bitSize, int count, int flags) {
            this.usagePage = usagePage;
            this.firstUsage = firstUsage;
            this.bitSize = bitSize;
            this.count = count;
            this.flags = flags;
        }

        public boolean constant() { return (flags & 1) != 0; }

        public boolean variable() { return (flags & 2) != 0; }
    }

    public static final class Report {
        public final int kind, id;
        public int bits;
        public final List<Field> fields = new ArrayList<>();

        Report(int kind, int id) { this.kind = kind; this.id = id; }

        /** Size on the wire, counting the report ID byte when the device uses IDs. */
        public int bytes() { return (bits + 7) / 8 + (id != 0 ? 1 : 0); }
    }

    private static final class Globals {
        int usagePage, reportSize, reportCount, reportId;

        Globals copy() {
            Globals g = new Globals();
            g.usagePage = usagePage;
            g.reportSize = reportSize;
            g.reportCount = reportCount;
            g.reportId = reportId;
            return g;
        }
    }

    public final Map<Long, Report> reports = new LinkedHashMap<>();
    /** The usage page and usage of the first top-level collection, or -1. */
    public int topUsagePage = -1, topUsage = -1;
    public String warning;

    public static HidDescriptor parse(byte[] d) {
        HidDescriptor out = new HidDescriptor();
        out.run(d);
        return out;
    }

    private void run(byte[] d) {
        Globals g = new Globals();
        Deque<Globals> stack = new ArrayDeque<>();
        List<Integer> usages = new ArrayList<>();
        int depth = 0;
        for (int at = 0; at < d.length; ) {
            int prefix = d[at] & 0xFF;
            if (prefix == 0xFE) {                                  // long item: skipped, none are defined by the HID specification
                if (at + 1 >= d.length) { warning = "Truncated long item at offset " + at + "."; return; }
                at += 3 + (d[at + 1] & 0xFF);
                continue;
            }
            int size = prefix & 3;
            if (size == 3) size = 4;
            if (at + 1 + size > d.length) { warning = "Truncated item at offset " + at + "."; return; }
            int value = 0;
            for (int i = 0; i < size; i++) value |= (d[at + 1 + i] & 0xFF) << (8 * i);
            int type = (prefix >> 2) & 3, tag = prefix >> 4;
            at += 1 + size;

            if (type == 1) {                                       // global
                switch (tag) {
                    case 0: g.usagePage = value; break;
                    case 7: g.reportSize = value; break;
                    case 8: g.reportId = value & 0xFF; break;
                    case 9: g.reportCount = value; break;
                    case 10: stack.push(g.copy()); break;
                    case 11:
                        if (stack.isEmpty()) { warning = "Pop without push at offset " + (at - 1 - size) + "."; return; }
                        g = stack.pop();
                        break;
                    default: break;
                }
            } else if (type == 2) {                                // local
                if (tag == 0 || tag == 1) usages.add(value);       // Usage, or Usage Minimum for a range
            } else if (type == 0) {                                // main
                if (tag == 10) {
                    if (depth == 0 && topUsagePage < 0) { topUsagePage = g.usagePage; topUsage = usages.isEmpty() ? 0 : usages.get(0); }
                    depth++;
                } else if (tag == 12) {
                    if (depth == 0) { warning = "End Collection without a collection at offset " + (at - 1 - size) + "."; return; }
                    depth--;
                } else if (tag == 8 || tag == 9 || tag == 11) {
                    addField(tag == 8 ? INPUT : tag == 9 ? OUTPUT : FEATURE, g, usages, value);
                }
                usages.clear();                                    // every main item resets the local state
            }
        }
        if (depth != 0) warning = "Descriptor ends with " + depth + " open collection(s).";
    }

    private void addField(int kind, Globals g, List<Integer> usages, int flags) {
        long key = ((long) kind << 8) | g.reportId;
        Report r = reports.get(key);
        if (r == null) { r = new Report(kind, g.reportId); reports.put(key, r); }
        r.bits += g.reportSize * g.reportCount;
        r.fields.add(new Field(g.usagePage, usages.isEmpty() ? -1 : usages.get(0), g.reportSize, g.reportCount, flags));
    }

    /** The report of {@code kind} with this ID, or null. */
    public Report report(int kind, int id) { return reports.get(((long) kind << 8) | id); }

    public String text() {
        StringBuilder sb = new StringBuilder();
        if (topUsagePage >= 0) sb.append(String.format("Top collection: usage page 0x%04X, usage 0x%04X\n", topUsagePage, topUsage));
        if (reports.isEmpty()) sb.append("No reports declared.\n");
        for (Report r : reports.values()) {
            sb.append(String.format("%s report ID %d: %d bytes on the wire (%d bits)\n", KIND_NAMES[r.kind], r.id, r.bytes(), r.bits));
            for (Field f : r.fields) {
                sb.append(String.format("  page 0x%04X usage %s: %d x %d bits%s\n", f.usagePage, f.firstUsage < 0 ? "-" : String.format("0x%02X", f.firstUsage),
                        f.count, f.bitSize, f.constant() ? " (padding)" : f.variable() ? "" : " (array)"));
            }
        }
        if (warning != null) sb.append("Warning: ").append(warning).append('\n');
        return sb.toString();
    }
}
