package app.galerie.tv;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

/**
 * Einstellungen als Hauptmenü mit Untermenüs (je eine XML-Datei). Alle Bildschirme nutzen dasselbe Fragment;
 * welches Menü es zeigt, steht im Argument "xml".
 */
public class SettingsActivity extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new Prefs())
                    .commit();
        }
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        Fragment f = getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), pref.getFragment());
        f.setArguments(pref.getExtras());
        getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, f)
                .addToBackStack(null)
                .commit();
        return true;
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
            ArtController.migratePrefs(getPreferenceManager().getSharedPreferences());
            setPreferencesFromResource(xmlRes(), rootKey);

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
            click("upload_quick", () -> startActivity(new Intent(requireContext(), UploadActivity.class)));

            click("preview", () -> startActivity(new Intent(requireContext(), ArtModeActivity.class)));
            click("set_screensaver", this::openDreamSettings);
            click("clear_cache", () -> {
                ArtRepository.clearCache(requireContext());
                Toast.makeText(requireContext(), "Cache geleert", Toast.LENGTH_SHORT).show();
            });
        }

        private int xmlRes() {
            String xml = getArguments() != null ? getArguments().getString("xml") : null;
            if (xml == null) return R.xml.preferences;
            switch (xml) {
                case "prefs_images": return R.xml.prefs_images;
                case "prefs_art": return R.xml.prefs_art;
                case "prefs_gallery": return R.xml.prefs_gallery;
                case "prefs_weathermode": return R.xml.prefs_weathermode;
                case "prefs_clock": return R.xml.prefs_clock;
                case "prefs_display": return R.xml.prefs_display;
                default: return R.xml.preferences;
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            updateMenuSummaries();
        }

        /** Hauptmenü: unter jedem Untermenü kurz anzeigen, was eingestellt ist. */
        private void updateMenuSummaries() {
            android.content.SharedPreferences p = getPreferenceManager().getSharedPreferences();
            if (p == null || findPreference("menu_art") == null) return;

            StringBuilder img = new StringBuilder();
            if (p.getBoolean("source_local", true)) img.append("Eigene Bilder");
            if (p.getBoolean("source_met", true)) {
                if (img.length() > 0) img.append(" · ");
                int n = p.getStringSet("collections", java.util.Collections.emptySet()).size();
                if (n == 0) n = getResources().getStringArray(R.array.collection_defaults).length;
                img.append("Museum (").append(n).append(n == 1 ? " Sammlung)" : " Sammlungen)");
            }
            summary("menu_images", img.length() > 0 ? img.toString() : "Keine Bildquelle aktiv");

            summary("menu_art", label(p, "matte", "modern", R.array.matte_labels, R.array.matte_values)
                    + " · " + label(p, "matte_color", "#F4F2EE", R.array.matte_color_labels, R.array.matte_color_values)
                    + " · " + label(p, "art_size", "76", R.array.size_labels, R.array.size_values)
                    + " · " + clockWeather(p, "art_clock", "art_weather", false));

            summary("menu_weathermode", clockWeather(p, "clock", "weather_on", true));

            summary("menu_gallery", (p.getBoolean("gallery_matte", true) ? "Mit schmalem Rand" : "Ohne Rand")
                    + " · " + clockWeather(p, "gallery_clock", "gallery_weather", false));

            String place = p.getString("weather_place", "");
            summary("menu_clock", (place.isEmpty() ? "Kein Ort eingestellt" : place)
                    + " · " + label(p, "overlay_pos", "bottom_split", R.array.pos_labels, R.array.pos_values));

            summary("menu_display", "Wechsel alle " + label(p, "interval", "600", R.array.interval_labels, R.array.interval_values)
                    + " · Helligkeit " + label(p, "brightness", "85", R.array.brightness_labels, R.array.brightness_values));
        }

        private static String clockWeather(android.content.SharedPreferences p, String clockKey, String weatherKey, boolean def) {
            boolean c = p.getBoolean(clockKey, def), w = p.getBoolean(weatherKey, def);
            return c && w ? "Uhr und Wetter" : c ? "Nur Uhr" : w ? "Nur Wetter" : "Ohne Uhr und Wetter";
        }

        private void summary(String key, CharSequence s) {
            Preference pref = findPreference(key);
            if (pref != null) pref.setSummary(s);
        }

        private String label(android.content.SharedPreferences p, String key, String def, int labelsRes, int valuesRes) {
            String v = p.getString(key, def);
            String[] labels = getResources().getStringArray(labelsRes);
            String[] values = getResources().getStringArray(valuesRes);
            for (int i = 0; i < values.length && i < labels.length; i++) {
                if (values[i].equals(v)) {
                    String l = labels[i];
                    int k = l.indexOf(" (");
                    return k > 0 ? l.substring(0, k) : l;
                }
            }
            return v;
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
