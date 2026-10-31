package com.electrotamp.kishids.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import com.electrotamp.kishids.core.DfuFlasher;
import com.electrotamp.kishids.v2pro.InputCapture;
import com.electrotamp.kishids.v2pro.UsbLayout;
import com.electrotamp.kishids.v2pro.V2ProProbe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Reads the input reports of every HID interface that has an interrupt IN endpoint and hands them to {@code cap}, until {@code stop}
     * is set, the capture finishes or the controller is unplugged.  Only IN transfers are made: no control request and nothing on an OUT
     * endpoint.  Claiming detaches Android's HID driver, so games see no input meanwhile; a USB reset at the end rebinds it.
     * Returns null on success, otherwise why it could not start.
     */
    String capture(UsbDevice d, final InputCapture cap, final AtomicBoolean stop) {
        final UsbDeviceConnection c = mgr.openDevice(d);
        if (c == null) return "Android would not open the device.";
        List<UsbInterface> claimed = new ArrayList<>();
        List<Thread> readers = new ArrayList<>();
        try {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface u = d.getInterface(i);
                if (u.getAlternateSetting() != 0 || u.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
                UsbEndpoint in = null;
                for (int e = 0; e < u.getEndpointCount(); e++) {
                    UsbEndpoint p = u.getEndpoint(e);
                    if (p.getDirection() == UsbConstants.USB_DIR_IN && p.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) in = p;
                }
                if (in == null) continue;                          // interfaces 3 and 4 only have OUT endpoints: left alone
                boolean ok = c.claimInterface(u, true);
                link.log("capture: claim interface " + u.getId() + ": " + ok);
                if (!ok) continue;
                claimed.add(u);
                final int id = u.getId();
                final UsbEndpoint ep = in;
                Thread t = new Thread(() -> {
                    byte[] buf = new byte[Math.max(64, ep.getMaxPacketSize())];
                    while (!stop.get()) {
                        int n = c.bulkTransfer(ep, buf, buf.length, 100);   // usbfs serves interrupt endpoints through the bulk call
                        if (n > 0) cap.feed(id, buf, n, SystemClock.uptimeMillis());
                    }
                }, "v2pro-in-" + id);
                t.setDaemon(true);
                t.start();
                readers.add(t);
            }
            if (claimed.isEmpty()) return "No input interface could be claimed.";
            long lastCheck = 0;
            while (!stop.get() && !cap.finished()) {
                long now = SystemClock.uptimeMillis();
                cap.tick(now);
                if (now - lastCheck > 1000) {
                    lastCheck = now;
                    if (!mgr.getDeviceList().containsKey(d.getDeviceName())) { link.log("capture: controller unplugged"); break; }
                }
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
            return null;
        } finally {
            stop.set(true);
            for (Thread t : readers) { try { t.join(500); } catch (InterruptedException ignored) { } }
            for (UsbInterface u : claimed) { try { c.releaseInterface(u); } catch (RuntimeException ignored) { } }
            if (!claimed.isEmpty()) link.resetQuietly(c);   // releasing leaves the interfaces without a driver; a reset rebinds them
            try { c.close(); } catch (RuntimeException ignored) { }
            link.log("capture: ended after " + cap.reports() + " reports");
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
