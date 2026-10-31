package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;
import com.electrotamp.kishids.v2pro.InputCapture;

/** Read-only USB inspection: what is attached, and on a Kishi V2 Pro the report descriptors needed to work out how to support it. */
public final class DiagnosticsPage extends Page {
    private final TextView status, devices, report, capPrompt, capLive, capSeen, capTable;
    private final Ui.Button read, copy, save, capStart, capNext, capStop;
    private final LinearLayout reportCard;
    private long lastList;
    private String shownDevices = "", shownReport = "", shownTable = "";

    public DiagnosticsPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "USB diagnostics", "Read-only. Shows what is plugged in. Nothing is written to the controller.");

        LinearLayout v2 = card();
        v2.addView(Ui.section(act, "Kishi V2 Pro"));
        status = Ui.caption(act, "");
        v2.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 14));
        read = Ui.primary(act, "Read report descriptors", m::runDiagnostics);
        v2.addView(read, Ui.lp(Ui.MATCH, Ui.WRAP));
        add(v2, 16);

        LinearLayout cap = card();
        cap.addView(Ui.section(act, "Input mapping"));
        capPrompt = Ui.caption(act, "");
        cap.addView(capPrompt, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 10));
        capLive = Ui.monoText(act, "", 11.5f, Theme.textDim);
        cap.addView(capLive, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 6));
        capSeen = Ui.caption(act, "");
        cap.addView(capSeen, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
        LinearLayout capButtons = Ui.hbox(act);
        capStart = Ui.primary(act, "Start", m::startCapture);
        capNext = Ui.secondary(act, "Next", m::captureNext);
        capStop = Ui.secondary(act, "Stop", m::stopCapture);
        capButtons.addView(capStart, Ui.weight(1, 0, 0, 5, 0));
        capButtons.addView(capNext, Ui.weight(1, 5, 0, 5, 0));
        capButtons.addView(capStop, Ui.weight(1, 5, 0, 0, 0));
        cap.addView(capButtons, Ui.lp(Ui.MATCH, Ui.WRAP));
        capTable = Ui.monoText(act, "", 11.5f, Theme.textDim);
        capTable.setTextIsSelectable(true);
        cap.addView(capTable, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        add(cap, 14);

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

    private String capturePrompt() {
        if (!m.isV2Pro() && !m.captureRunning) return "Connect a Kishi V2 Pro to map its controls.";
        if (m.captureWaiting()) return "Allow USB access in the Android prompt.";
        if (m.captureRunning && m.capture != null) return m.capture.prompt();
        if (!m.captureError.isEmpty()) return "Stopped: " + m.captureError;
        if (m.capture != null) return "Capture ended. Copy or Save includes the full result with the raw reports.";
        return "Press each control when asked, to record which report field it drives. Only reads from the controller. Games get no input until it ends.";
    }

    private static String table(InputCapture c) {
        StringBuilder sb = new StringBuilder();
        for (InputCapture.Result r : c.results()) {
            sb.append(r.control).append(": ").append(r.skipped ? "skipped" : String.join("; ", r.fields)).append('\n');
        }
        return sb.toString().trim();
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
        capPrompt.setText(capturePrompt());
        boolean running = m.captureRunning && m.capture != null;
        capLive.setText(running ? m.capture.liveText() : "");
        capSeen.setText(running && !m.capture.settling() ? m.capture.observedText() : "");
        Ui.show(capLive, running);
        Ui.show(capSeen, running);
        capStart.setEnabled(m.canRunCapture());
        capNext.setEnabled(running && !m.capture.settling());
        capStop.setEnabled(running || m.captureWaiting());
        String t = m.capture == null ? "" : table(m.capture);
        if (!t.equals(shownTable)) { shownTable = t; capTable.setText(t); }
        Ui.show(capTable, !t.isEmpty());
        if (!m.diagnosticsReport.equals(shownReport)) { shownReport = m.diagnosticsReport; report.setText(shownReport); }
        Ui.show(reportCard, !shownReport.isEmpty());
    }
}
