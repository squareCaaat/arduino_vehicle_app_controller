package com.example.arduinobluetoothcontroller.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.arduinobluetoothcontroller.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

public class LogDialogFragment extends DialogFragment {

    private static final String ARG_LOGS = "logs";

    public static LogDialogFragment newInstance(ArrayList<String> logs) {
        LogDialogFragment fragment = new LogDialogFragment();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_LOGS, logs);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View contentView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_log, null, false);

        RecyclerView recyclerView = contentView.findViewById(R.id.logRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        LogAdapter adapter = new LogAdapter();
        adapter.submit(getArguments() != null
                ? getArguments().getStringArrayList(ARG_LOGS)
                : new ArrayList<>());
        recyclerView.setAdapter(adapter);
        if (adapter.getItemCount() > 0) {
            recyclerView.scrollToPosition(adapter.getItemCount() - 1);
        }

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_log_title)
                .setView(contentView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> dismiss())
                .create();
    }
}

