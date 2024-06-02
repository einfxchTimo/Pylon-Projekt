package de.timo.pilon_app;

import static android.content.Context.MODE_PRIVATE;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.util.Objects;

public class SettingsFragment extends Fragment {

    TextView filePath;
    String path;
    Boolean enabled;
    ActivityResultLauncher<Intent> someActivityResultLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        someActivityResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        assert data != null;
                        path = String.valueOf(data.getData());
                        filePath.setText(path);
                    }
                });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        SeekBar volumeBar = view.findViewById(R.id.seekBar);
        Button fileButton = view.findViewById(R.id.file_button);
        Button defaultButton = view.findViewById(R.id.default_sound);
        filePath = view.findViewById(R.id.file_path);
        Button saveButton = view.findViewById(R.id.save);
        Switch ton = view.findViewById(R.id.switch1);

        SharedPreferences sharedPreferences = getActivity().getSharedPreferences("Settings", MODE_PRIVATE);

        int volume = sharedPreferences.getInt("Volume", 5);
        path = sharedPreferences.getString("Path", "Standart Ton");
        enabled = sharedPreferences.getBoolean("Ton", true);
        volumeBar.setProgress(volume);
        ton.setChecked(enabled);
        filePath.setText(path);

        saveButton.setOnClickListener(v -> {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("Ton", ton.isChecked());
            editor.putInt("Volume", volumeBar.getProgress());
            editor.putString("Path", path);
            if(!Objects.equals(path, sharedPreferences.getString("Path", "Standart Ton")) && !Objects.equals(path, "Standart Ton")) {
                editor.putInt("SoundVersion", sharedPreferences.getInt("SoundVersion", 0)+1);
            }
            editor.apply();

            Intent intent = new Intent(getActivity(), MainActivity.class);
            startActivity(intent);
        });

        fileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/mpeg");
            someActivityResultLauncher.launch(intent);
        });

        defaultButton.setOnClickListener(v -> {
            path = "Standart Ton";
            filePath.setText(path);
        });


        return view;
    }


}
