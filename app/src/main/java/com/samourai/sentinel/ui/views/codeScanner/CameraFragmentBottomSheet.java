package com.samourai.sentinel.ui.views.codeScanner;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.samourai.sentinel.R;
import com.samourai.sentinel.util.PrefsUtil;


public class CameraFragmentBottomSheet extends BottomSheetDialogFragment {
    public static final String ARG_REQUEST_KEY = "camera_bottom_sheet_request_key";
    public static final String RESULT_TEXT = "camera_bottom_sheet_result_text";

    private CodeScanner mCodeScanner;

    public static CameraFragmentBottomSheet newInstance(String requestKey) {
        CameraFragmentBottomSheet sheet = new CameraFragmentBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_REQUEST_KEY, requestKey);
        sheet.setArguments(args);
        return sheet;
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottomsheet_camera, null);
        CodeScannerView scannerView = view.findViewById(R.id.scanner_view);
        mCodeScanner = new CodeScanner(getActivity().getApplicationContext(), scannerView);
        mCodeScanner.setAutoFocusEnabled(true);
        mCodeScanner.setDecodeCallback(result -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> deliver(result.getText()));
        });
        scannerView.setOnClickListener(view1 -> mCodeScanner.startPreview());
        return view;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        if (PrefsUtil.getInstance(requireContext()).getValue("displaySecure", true))
            dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        return dialog;
    }
    @Override
    public void onResume() {
        super.onResume();
        mCodeScanner.startPreview();
    }

    /**
     * #218: the decoded text is posted to the request key this sheet was
     * opened with. The host re-registers that key after a recreation, so
     * the scan is not dropped with the old listener field.
     */
    private void deliver(String text) {
        Bundle args = getArguments();
        if (args == null || !isAdded()) return;
        String key = args.getString(ARG_REQUEST_KEY);
        if (key == null) return;
        Bundle result = new Bundle();
        result.putString(RESULT_TEXT, text);
        getParentFragmentManager().setFragmentResult(key, result);
    }

    @Override
    public void onPause() {
        mCodeScanner.releaseResources();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mCodeScanner.releaseResources();
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();

        View view = getView();
        if (view != null) {
            view.post(() -> {
                View parent = (View) view.getParent();
                CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) (parent).getLayoutParams();
                CoordinatorLayout.Behavior behavior = params.getBehavior();
                BottomSheetBehavior bottomSheetBehavior = (BottomSheetBehavior) behavior;
                if (bottomSheetBehavior != null) {
                    bottomSheetBehavior.setPeekHeight(view.getMeasuredHeight());
                }

            });
        }
    }
}