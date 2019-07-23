package wjhj.orbital.sportsmatchfindingapp.user;


import android.os.Bundle;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import wjhj.orbital.sportsmatchfindingapp.R;
import wjhj.orbital.sportsmatchfindingapp.databinding.DisplayUserProfileFragmentBinding;

/**
 * A simple {@link Fragment} subclass.
 */
public class DisplayUserProfileFragment extends Fragment {

    private static String USER_UID_TAG = "user_uid";

    private DisplayUserProfileFragmentBinding binding;
    private DisplayUserProfileViewModel viewModel;
    private String mUserUid;

    public DisplayUserProfileFragment() {
        // Required empty public constructor
    }

    public static DisplayUserProfileFragment newInstance(String userUid) {
        DisplayUserProfileFragment fragment = new DisplayUserProfileFragment();
        Bundle args = new Bundle();
        args.putString(USER_UID_TAG, userUid);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null) {
            mUserUid = getArguments().getString(USER_UID_TAG);
        }

        if (mUserUid != null) {
            DisplayUserProfileViewModelFactory factory = new DisplayUserProfileViewModelFactory(mUserUid);
            viewModel = ViewModelProviders.of(this, factory)
                    .get(DisplayUserProfileViewModel.class);
        } else {
            Toast.makeText(getActivity(), "Error occurred, please try again", Toast.LENGTH_SHORT)
                    .show();
        }

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        BottomNavigationView bottomNav = requireActivity().findViewById(R.id.bottom_nav);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.INVISIBLE);
        }

        binding = DisplayUserProfileFragmentBinding.inflate(inflater, container, false);
        binding.setLifecycleOwner(getViewLifecycleOwner());
        binding.setViewModel(viewModel);

        initActionButton(binding.displayUserActionButton);

        initPreferencesRecyclerView(binding.displayUserProfilePreferences);

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        BottomNavigationView bottomNav = requireActivity().findViewById(R.id.bottom_nav);
        bottomNav.setVisibility(View.VISIBLE);
    }

    private void initActionButton(Button actionButton) {
        if (viewModel.isCurrentUser()) {
            updateButton(actionButton,
                    ContextCompat.getColor(requireActivity(), R.color.offWhite),
                    requireActivity().getString(R.string.user_profile_edit_profile),
                    v -> {});
            actionButton.setTextColor(ContextCompat.getColor(requireActivity(), R.color.black));
        } else {
            viewModel.isFriend().observe(getViewLifecycleOwner(), isFriend -> {
                if (isFriend) {
                    updateButton(actionButton,
                            ContextCompat.getColor(requireActivity(), R.color.colorPrimary),
                            requireActivity().getString(R.string.display_profile_friends),
                            v -> {});
                } else {
                    updateButton(actionButton,
                            ContextCompat.getColor(requireActivity(), R.color.green),
                            requireActivity().getString(R.string.display_user_add_friend),
                            v-> {});
                }
            });
        }
    }

    private void updateButton(Button button, int color, CharSequence text, View.OnClickListener onClickListener) {
        button.setBackgroundColor(color);
        button.setText(text);
        button.setOnClickListener(onClickListener);
    }

    private void initPreferencesRecyclerView(RecyclerView recyclerView) {
        LinearLayoutManager manager = new LinearLayoutManager(getActivity(),
                RecyclerView.HORIZONTAL, false);

        PreferencesIconAdapter adapter = new PreferencesIconAdapter();
        viewModel.getPreferences().observe(getViewLifecycleOwner(), adapter::updatePreferences);

        recyclerView.setLayoutManager(manager);
        recyclerView.setAdapter(adapter);
    }

}
