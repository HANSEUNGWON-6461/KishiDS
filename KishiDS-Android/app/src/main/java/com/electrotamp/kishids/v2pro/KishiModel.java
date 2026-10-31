package com.electrotamp.kishids.v2pro;

/**
 * Which piece of Kishi hardware a USB VID/PID belongs to.  The V1 and the V2 Pro share no firmware, so every code path that writes to a
 * controller asks this class first.  Nothing about the V2 Pro's bootloader is known yet, so no V2 Pro id ever counts as a flash target.
 */
public enum KishiModel {
    UNKNOWN, V1_STOCK, V1_BOOTLOADER, V2_PRO;

    public static final int VID_V1 = 0x27F8, PID_V1_STOCK = 0x0BBF, PID_V1_BOOT = 0x0BC0;
    public static final int VID_V2_PRO = 0x1532, PID_V2_PRO = 0x0717;

    public static KishiModel classify(int vid, int pid) {
        if (vid == VID_V1 && pid == PID_V1_STOCK) return V1_STOCK;
        if (vid == VID_V1 && pid == PID_V1_BOOT) return V1_BOOTLOADER;
        if (vid == VID_V2_PRO && pid == PID_V2_PRO) return V2_PRO;
        return UNKNOWN;
    }

    public String title() {
        switch (this) {
            case V1_STOCK: return "Kishi V1, Razer firmware";
            case V1_BOOTLOADER: return "Kishi V1 bootloader";
            case V2_PRO: return "Kishi V2 Pro";
            default: return "Other device";
        }
    }

    /**
     * May the V1 flash start with these devices attached?  Each element is { vid, pid }.  Needs the V1 bootloader, and refuses outright
     * while a V2 Pro is on the bus: V1 firmware must never get near one.
     */
    public static boolean v1FlashAllowed(int[][] attached) {
        boolean bootloader = false;
        for (int[] d : attached) {
            KishiModel m = classify(d[0], d[1]);
            if (m == V2_PRO) return false;
            if (m == V1_BOOTLOADER) bootloader = true;
        }
        return bootloader;
    }
}
