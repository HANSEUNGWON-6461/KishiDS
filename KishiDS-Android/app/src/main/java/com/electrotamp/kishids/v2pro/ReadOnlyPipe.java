package com.electrotamp.kishids.v2pro;

import com.electrotamp.kishids.core.DfuFlasher;

/**
 * Wraps a control pipe so that only GET_DESCRIPTOR for a HID report descriptor reaches the controller.  The diagnostics run on hardware
 * whose vendor requests are undocumented, so nothing that could change its state is allowed through, whatever the calling code does.
 */
public final class ReadOnlyPipe implements DfuFlasher.Pipe {
    public static final int REFUSED = -1000;
    public static final int REQUEST_TYPE = 0x81, GET_DESCRIPTOR = 0x06, REPORT_DESCRIPTOR = 0x2200;

    private final DfuFlasher.Pipe inner;
    /** How many requests this wrapper has stopped. */
    public int refused;

    public ReadOnlyPipe(DfuFlasher.Pipe inner) { this.inner = inner; }

    @Override public int control(int requestType, int request, int value, int index, byte[] buffer, int length, int timeoutMs) {
        if (requestType != REQUEST_TYPE || request != GET_DESCRIPTOR || value != REPORT_DESCRIPTOR) {
            refused++;
            return REFUSED;
        }
        return inner.control(requestType, request, value, index, buffer, length, timeoutMs);
    }
}
