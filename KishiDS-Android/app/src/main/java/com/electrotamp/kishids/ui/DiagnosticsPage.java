package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;

/** Read-only USB inspection: what is attached, and on a Kishi V2 Pro the report descriptors needed to work out how to support it. */
public final class DiagnosticsPage extends Page {
    private final TextView status, devices, report;
    private final Ui.Button read, copy, save;
    private final LinearLayout reportCard;
    private long lastList;
    private String shownDevices = "", shownReport = "";

    public DiagnosticsPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "USB diagnostics", "Read-only. Shows what is plugged in. Nothing is written to the controller.");

        LinearLayout v2 = card();
        v2.addView(Ui.section(act, "Kishi V2 Pro"));
        status = Ui.caption(act, "");
        v2.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 14));
        read = Ui.primary(act, "Read report descriptors", m::runDiagnostics);
        v2.addView(read, Ui.lp(Ui.MATCH, Ui.WRAP));
        add(v2, 16);

        LinearLayout list = card();
        list.addView(Ui.section(act, "Attached USB devices"));
        devices = Ui.monoText(act, "", 11.5f, Theme.textDim);
        devices.setTextIsSelectable(true);
        list.addView(devices, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        add(list, 14);

        reportCard = card();
        reportCard.addView(Ui.section(act, "Report"));
        report = Ui.monoText(act, "", 11.5f, Theme.textDim);
        report.setTextIsSelectable(true);
        reportCard.addView(report, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 8, 0, 14));
        add(reportCard, 14);

        LinearLayout buttons = Ui.hbox(act);
        copy = Ui.secondary(act, "Copy", this::copyAll);
        save = Ui.secondary(act, "Save…", host::saveDiagnostics);
        buttons.addView(copy, Ui.weight(1, 0, 0, 5, 0));
        buttons.addView(save, Ui.weight(1, 5, 0, 0, 0));
        add(buttons, 14);
    }

    private void copyAll() {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("KishiDS USB diagnostics", m.diagnosticsText()));
        host.toast("Diagnostics copied.");
    }

    private String statusText() {
        if (!m.isV2Pro()) return "No Kishi V2 Pro attached. The list below still shows every USB device, which helps identify an unknown update mode.";
        if (m.diagnosticsRunning) return "Reading. Input is interrupted for a moment.";
        if (m.diagnosticsWaiting()) return "Allow USB access in the Android prompt.";
        if (m.accessDenied) return "USB access was refused. Tap the button to be asked again.";
        return "Reads each HID interface's report descriptor. Android's driver is detached while this runs, so input stops and the controller reconnects afterwards.";
    }

    @Override protected void onUpdate() {
        status.setText(statusText());
        read.setEnabled(m.canRunDiagnostics());
        long now = SystemClock.uptimeMillis();
        if (now - lastList > 1000) {
            lastList = now;
            String text = m.attachedDevicesText();
            if (!text.equals(shownDevices)) { shownDevices = text; devices.setText(text); }
        }
        if (!m.diagnosticsReport.equals(shownReport)) { shownReport = m.diagnosticsReport; report.setText(shownReport); }
        Ui.show(reportCard, !shownReport.isEmpty());
    }
}
