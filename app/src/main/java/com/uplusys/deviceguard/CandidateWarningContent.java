package com.uplusys.deviceguard;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

final class CandidateWarningContent {
    private CandidateWarningContent() {}

    static View create(Context context, ScanObservation row, Runnable openDetails) {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_candidate_warning, null);
        bind(content, row);
        content.findViewById(R.id.candidate_warning_details).setOnClickListener(view -> openDetails.run());
        return content;
    }

    static void bind(View content, ScanObservation row) {
        TextView name = content.findViewById(R.id.candidate_warning_name);
        TextView match = content.findViewById(R.id.candidate_warning_match);
        name.setText(row.name());
        match.setText(DetectionText.targets(row));
    }
}
