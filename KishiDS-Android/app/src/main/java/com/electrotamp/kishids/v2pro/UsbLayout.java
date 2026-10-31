package com.electrotamp.kishids.v2pro;

import java.util.ArrayList;
import java.util.List;

/**
 * A USB device's descriptors as plain data, so the diagnostics can be tested without Android.  Built from the UsbDevice object (works
 * without USB permission) or, with permission, from the raw descriptors, which add bcdDevice and each HID report descriptor's length.
 */
public final class UsbLayout {
    public static final int CLASS_HID = 0x03, CLASS_APP_SPECIFIC = 0xFE, SUBCLASS_DFU = 0x01;
    private static final int DESC_DEVICE = 1, DESC_CONFIG = 2, DESC_INTERFACE = 4, DESC_ENDPOINT = 5, DESC_HID = 0x21, DESC_REPORT = 0x22;

    public static final class Endpoint {
        public final int address, attributes, maxPacket, interval;

        public Endpoint(int address, int attributes, int maxPacket, int interval) {
            this.address = address;
            this.attributes = attributes;
            this.maxPacket = maxPacket;
            this.interval = interval;
        }

        public boolean in() { return (address & 0x80) != 0; }

        public String typeName() {
            switch (attributes & 3) {
                case 0: return "control";
                case 1: return "isochronous";
                case 2: return "bulk";
                default: return "interrupt";
            }
        }

        public String text() {
            return String.format("0x%02X %s %s, max %d bytes, interval %d", address, in() ? "IN" : "OUT", typeName(), maxPacket, interval);
        }
    }

    public static final class Iface {
        public final int number, alternate, cls, subclass, protocol;
        /** wDescriptorLength of the HID report descriptor, or 0 when it is not known. */
        public int reportLength;
        public final List<Endpoint> endpoints = new ArrayList<>();

        public Iface(int number, int alternate, int cls, int subclass, int protocol) {
            this.number = number;
            this.alternate = alternate;
            this.cls = cls;
            this.subclass = subclass;
            this.protocol = protocol;
        }

        public boolean hid() { return cls == CLASS_HID; }

        public boolean dfu() { return cls == CLASS_APP_SPECIFIC && subclass == SUBCLASS_DFU; }
    }

    public int vid, pid;
    /** bcdUSB and bcdDevice; -1 until raw descriptors have been read. */
    public int bcdUsb = -1, bcdDevice = -1;
    public String path = "", manufacturer, product, serial;
    public final List<Iface> interfaces = new ArrayList<>();

    /** Interfaces in their default alternate setting: the count a host sees, and the list a report descriptor is requested for. */
    public List<Iface> mainInterfaces() {
        List<Iface> out = new ArrayList<>();
        for (Iface i : interfaces) if (i.alternate == 0) out.add(i);
        return out;
    }

    public boolean hasDfuInterface() {
        for (Iface i : interfaces) if (i.dfu()) return true;
        return false;
    }

    /** Parse what UsbDeviceConnection.getRawDescriptors() returns.  Stops quietly at the first malformed descriptor. */
    public static UsbLayout parseRaw(byte[] raw) {
        UsbLayout out = new UsbLayout();
        Iface cur = null;
        for (int at = 0; at + 2 <= raw.length; ) {
            int len = raw[at] & 0xFF, type = raw[at + 1] & 0xFF;
            if (len < 2 || at + len > raw.length) break;
            if (type == DESC_DEVICE && len >= 18) {
                out.bcdUsb = u16(raw, at + 2);
                out.vid = u16(raw, at + 8);
                out.pid = u16(raw, at + 10);
                out.bcdDevice = u16(raw, at + 12);
            } else if (type == DESC_CONFIG) {
                cur = null;
            } else if (type == DESC_INTERFACE && len >= 9) {
                cur = new Iface(raw[at + 2] & 0xFF, raw[at + 3] & 0xFF, raw[at + 5] & 0xFF, raw[at + 6] & 0xFF, raw[at + 7] & 0xFF);
                out.interfaces.add(cur);
            } else if (type == DESC_ENDPOINT && len >= 7 && cur != null) {
                cur.endpoints.add(new Endpoint(raw[at + 2] & 0xFF, raw[at + 3] & 0xFF, u16(raw, at + 4) & 0x7FF, raw[at + 6] & 0xFF));
            } else if (type == DESC_HID && len >= 9 && cur != null) {
                int entries = raw[at + 5] & 0xFF;
                for (int i = 0; i < entries && at + 9 + i * 3 <= at + len; i++) {
                    if ((raw[at + 6 + i * 3] & 0xFF) == DESC_REPORT) cur.reportLength = u16(raw, at + 7 + i * 3);
                }
            }
            at += len;
        }
        return out;
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

    /** A device identity in the 1532:0717 form. */
    public static String ids(int vid, int pid) { return String.format("%04X:%04X", vid, pid); }

    /** A BCD version in the 2.70 form, or "unknown" before raw descriptors have been read. */
    public static String bcd(int v) { return v < 0 ? "unknown" : String.format("%x.%02x", v >> 8, v & 0xFF); }

    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append(ids(vid, pid)).append("  ").append(KishiModel.classify(vid, pid).title()).append('\n');
        if (!path.isEmpty()) sb.append("Path: ").append(path).append('\n');
        sb.append("Manufacturer: ").append(orUnknown(manufacturer)).append('\n');
        sb.append("Product: ").append(orUnknown(product)).append('\n');
        sb.append("Serial: ").append(orUnknown(serial)).append('\n');
        sb.append("bcdUSB ").append(bcd(bcdUsb)).append(", bcdDevice ").append(bcd(bcdDevice)).append('\n');
        sb.append("Interfaces: ").append(mainInterfaces().size()).append('\n');
        for (Iface i : interfaces) {
            sb.append(String.format("  #%d alt %d  class %02X:%02X:%02X", i.number, i.alternate, i.cls, i.subclass, i.protocol));
            if (i.hid()) sb.append("  HID");
            if (i.dfu()) sb.append("  DFU");
            if (i.reportLength > 0) sb.append("  report descriptor ").append(i.reportLength).append(" bytes");
            sb.append('\n');
            for (Endpoint e : i.endpoints) sb.append("     ").append(e.text()).append('\n');
        }
        return sb.toString();
    }

    private static String orUnknown(String s) { return s == null || s.isEmpty() ? "unknown" : s; }
}
