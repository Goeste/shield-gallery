package app.galerie.tv;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new Prefs())
                    .commit();
        }
    }

    public static class Prefs extends PreferenceFragmentCompat {
        private ActivityResultLauncher<String> permLauncher;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            permLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    SwitchPreferenceCompat sw = findPreference("source_usb");
                    if (sw != null) sw.setChecked(false);
                    Toast.makeText(requireContext(), "Ohne Speicherzugriff können keine Bilder vom USB-Stick angezeigt werden.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            Preference usb = findPreference("source_usb");
            if (usb != null) usb.setOnPreferenceChangeListener((p, v) -> {
                if (Boolean.TRUE.equals(v) && !LocalSource.hasPermission(requireContext())) {
                    permLauncher.launch(LocalSource.permission());
                }
                return true;
            });

            Preference city = findPreference("weather_city");
            if (city != null) {
                String place = getPreferenceManager().getSharedPreferences().getString("weather_place", "");
                city.setSummary(place.isEmpty() ? "Noch kein Ort – hier eingeben" : place);
                city.setOnPreferenceChangeListener((p, v) -> {
                    String q = String.valueOf(v).trim();
                    getPreferenceManager().getSharedPreferences().edit()
                            .remove("weather_lat").remove("weather_lon").remove("weather_place").apply();
                    if (q.isEmpty()) {
                        p.setSummary("Noch kein Ort – hier eingeben");
                        return true;
                    }
                    p.setSummary("Suche „" + q + "“ …");
                    final android.content.Context app = requireContext().getApplicationContext();
                    new Thread(() -> {
                        String msg;
                        try {
                            msg = WeatherService.geocode(app, q);
                        } catch (Exception e) {
                            msg = "Nicht gefunden: " + q;
                        }
                        final String m = msg;
                        android.app.Activity act = getActivity();
                        if (act != null) act.runOnUiThread(() -> {
                            p.setSummary(m);
                            Toast.makeText(app, "Wetter: " + m, Toast.LENGTH_SHORT).show();
                        });
                    }).start();
                    return true;
                });
            }

            click("upload", () -> startActivity(new Intent(requireContext(), UploadActivity.class)));

            click("preview", () -> startActivity(new Intent(requireContext(), ArtModeActivity.class)));
            click("set_screensaver", this::openDreamSettings);
            click("clear_cache", () -> {
                ArtRepository.clearCache(requireContext());
                Toast.makeText(requireContext(), "Cache geleert", Toast.LENGTH_SHORT).show();
            });
        }

        private void click(String key, Runnable r) {
            Preference p = findPreference(key);
            if (p != null) p.setOnPreferenceClickListener(x -> {
                r.run();
                return true;
            });
        }

        private void openDreamSettings() {
            String[] actions = {Settings.ACTION_DREAM_SETTINGS, "android.settings.DISPLAY_SETTINGS", Settings.ACTION_SETTINGS};
            for (String a : actions) {
                try {
                    startActivity(new Intent(a));
                    Toast.makeText(requireContext(), "Bildschirmschoner → „Galerie“ auswählen", Toast.LENGTH_LONG).show();
                    return;
                } catch (ActivityNotFoundException ignored) { }
            }
        }
    }
}
