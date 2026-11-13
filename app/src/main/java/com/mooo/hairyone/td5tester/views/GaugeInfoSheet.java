package com.mooo.hairyone.td5tester.views;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;

import com.mooo.hairyone.td5tester.R;

/** The note behind a gauge's info dot, with its session peak when it keeps one. */
public final class GaugeInfoSheet {

    private GaugeInfoSheet() { }

    public static void attach(GaugeView... gauges) {
        for (GaugeView gauge : gauges) {
            if (gauge == null || gauge.getInfoBody() == null) continue;
            gauge.setOnClickListener(GaugeInfoSheet::show);
        }
    }

    private static void show(View view) {
        GaugeView gauge = (GaugeView) view;
        Context context = view.getContext();

        StringBuilder body = new StringBuilder(gauge.getInfoBody());
        String peak = gauge.getPeakText();
        if (peak != null) {
            body.append("\n\n").append(context.getString(R.string.session_peak)).append(" ").append(peak);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.InfoDialog)
                .setTitle(gauge.getInfoTitle())
                .setMessage(body.toString())
                .setPositiveButton(R.string.close, null);

        if (peak != null) {
            builder.setNeutralButton(R.string.reset_peak, (d, which) -> gauge.resetPeak());
        }
        builder.show();
    }
}
