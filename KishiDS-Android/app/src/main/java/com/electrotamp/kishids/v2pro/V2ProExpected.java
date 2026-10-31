package com.electrotamp.kishids.v2pro;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The USB shape previously observed on a Kishi V2 Pro, compared with what is actually attached.  A difference is a finding for the
 * report, not an error: the table is a record of one unit, not a specification.
 */
public final class V2ProExpected {
    private V2ProExpected() {}

    public static final int INTERFACES = 5;
    private static final int[] ENDPOINTS_IN = { 0x81, 0x82, 0x83 }, ENDPOINTS_OUT = { 0x02, 0x03 };
    private static final String[] ROLES = { "Gamepad HID", "Touch HID", "Additional HID", "Vendor output / feature", "Vendor output" };

    /** Unverified hypothesis from the earlier Python prototype: input report 1 is 13 bytes including the report ID. */
    public static final int HYPOTHESIS_REPORT_ID = 1, HYPOTHESIS_REPORT_BYTES = 13;

    public static String role(int iface) { return iface >= 0 && iface < ROLES.length ? ROLES[iface] : "Unlisted"; }

    /** Differences between {@code l} and the recorded layout; empty when they match. */
    public static List<String> differences(UsbLayout l) {
        List<String> out = new ArrayList<>();
        int n = l.mainInterfaces().size();
        if (n != INTERFACES) out.add("Interface count is " + n + ", recorded " + INTERFACES + ".");
        Set<Integer> seen = new TreeSet<>();
        for (UsbLayout.Iface i : l.mainInterfaces()) for (UsbLayout.Endpoint e : i.endpoints) seen.add(e.address);
        Set<Integer> want = new TreeSet<>();
        for (int e : ENDPOINTS_IN) want.add(e);
        for (int e : ENDPOINTS_OUT) want.add(e);
        for (int e : want) if (!seen.contains(e)) out.add(String.format("Endpoint 0x%02X is missing.", e));
        for (int e : seen) if (!want.contains(e)) out.add(String.format("Endpoint 0x%02X was not recorded before.", e));
        return out;
    }
}
