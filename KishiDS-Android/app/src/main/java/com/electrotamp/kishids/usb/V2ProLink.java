package com.electrotamp.kishids.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.electrotamp.kishids.core.DfuFlasher;
import com.electrotamp.kishids.v2pro.UsbLayout;
import com.electrotamp.kishids.v2pro.V2ProProbe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android side of the V2 Pro diagnostics: turns UsbDevice objects into {@link UsbLayout}s and runs {@link V2ProProbe} over a real
 * connection.  Never writes to the controller.  Reading report descriptors detaches Android's HID driver, so input stops until
 * {@link #diagnose} has handed every interface back.
 */
final class V2ProLink {
    private final UsbManager mgr;
    private final UsbLink link;

    V2ProLink(UsbManager mgr, UsbLink link) {
        this.mgr = mgr;
        this.link = link;
    }

    /** Every attached USB device.  Needs no permission, which is what makes this useful for spotting an unknown update mode. */
    List<UsbLayout> attached() {
        List<UsbLayout> out = new ArrayList<>();
        for (UsbDevice d : mgr.getDeviceList().values()) out.add(describe(d));
        return out;
    }

    private UsbLayout describe(UsbDevice d) {
        UsbLayout l = new UsbLayout();
        l.vid = d.getVendorId();
        l.pid = d.getProductId();
        l.path = d.getDeviceName();
        l.manufacturer = d.getManufacturerName();
        l.product = d.getProductName();
        try { l.serial = d.getSerialNumber(); } catch (SecurityException ignored) { }   // needs the permission on Android 10 and later
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface u = d.getInterface(i);
            UsbLayout.Iface f = new UsbLayout.Iface(u.getId(), u.getAlternateSetting(), u.getInterfaceClass(), u.getInterfaceSubclass(), u.getInterfaceProtocol());
            for (int e = 0; e < u.getEndpointCount(); e++) {
                UsbEndpoint p = u.getEndpoint(e);
                f.endpoints.add(new UsbLayout.Endpoint(p.getAddress(), p.getAttributes(), p.getMaxPacketSize(), p.getInterval()));
            }
            l.interfaces.add(f);
        }
        return l;
    }

    /** Runs the whole collection on the calling thread and returns the report text. */
    String diagnose(UsbDevice d) {
        UsbLayout layout = describe(d);
        UsbDeviceConnection c = mgr.openDevice(d);
        if (c == null) return V2ProProbe.report(layout, Collections.<V2ProProbe.Entry>emptyList(), "Android would not open the device.");

        final boolean[] claimed = { false };
        try {
            byte[] raw = c.getRawDescriptors();
            if (raw != null) layout = merge(layout, UsbLayout.parseRaw(raw));

            final Map<Integer, UsbInterface> byId = new HashMap<>();
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface u = d.getInterface(i);
                if (u.getAlternateSetting() == 0) byId.put(u.getId(), u);
            }
            DfuFlasher.Pipe pipe = c::controlTransfer;
            List<V2ProProbe.Entry> entries = V2ProProbe.run(layout, pipe, new V2ProProbe.Claims() {
                @Override public boolean claim(int iface) {
                    UsbInterface u = byId.get(iface);
                    boolean ok = u != null && c.claimInterface(u, true);   // the force flag detaches Android's HID driver
                    if (ok) claimed[0] = true;
                    link.log("claim interface " + iface + ": " + ok);
                    return ok;
                }

                @Override public void release(int iface) {
                    UsbInterface u = byId.get(iface);
                    if (u != null) { try { c.releaseInterface(u); } catch (RuntimeException ignored) { } }
                }
            });
            return V2ProProbe.report(layout, entries, "Input was interrupted while this ran. If the controller stops responding, unplug it and plug it back in.");
        } finally {
            if (claimed[0]) link.resetQuietly(c);   // releasing leaves the interfaces without a driver; a reset rebinds them
            try { c.close(); } catch (RuntimeException ignored) { }
        }
    }

    /** Raw descriptors are the authority for the layout; the strings and path only exist on the UsbDevice. */
    private static UsbLayout merge(UsbLayout fromDevice, UsbLayout fromRaw) {
        if (fromRaw.interfaces.isEmpty()) return fromDevice;
        fromRaw.path = fromDevice.path;
        fromRaw.manufacturer = fromDevice.manufacturer;
        fromRaw.product = fromDevice.product;
        fromRaw.serial = fromDevice.serial;
        return fromRaw;
    }
}
