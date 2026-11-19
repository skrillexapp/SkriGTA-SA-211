package com.kurdish.roleplay.launcher;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kurdish.roleplay.R;
import com.kurdish.roleplay.launcher.util.SkriSyncHelper;

public class HomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_home, container, false);

        View btnPlay = view.findViewById(R.id.btnPlay);
        btnPlay.setOnClickListener(v -> {
            android.util.Log.d("HomeFragment", "Play button clicked");
            SkriSyncHelper.launchWithSync(requireActivity());
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() != null) SkriSyncHelper.checkQuickCrashLog(getActivity());
    }
}
