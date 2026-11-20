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

            click("use_art", () -> useStyle("art"));
            click("use_gallery", () -> useStyle("gallery"));
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
                case "prefs_style": return R.xml.prefs_style;
                case "prefs_art": return R.xml.prefs_art;
                case "prefs_gallery": return R.xml.prefs_gallery;
                case "prefs_weather": return R.xml.prefs_weather;
                case "prefs_clock": return R.xml.prefs_clock;
                case "prefs_images": return R.xml.prefs_images;
                case "prefs_source": return R.xml.prefs_source;
                case "prefs_format": return R.xml.prefs_format;
                default: return R.xml.preferences;
            }
        }

        private void useStyle(String mode) {
            getPreferenceManager().getSharedPreferences().edit().putString("mode", mode).apply();
            Toast.makeText(requireContext(), "Stil: " + ("gallery".equals(mode) ? "Galerie" : "Art"), Toast.LENGTH_SHORT).show();
            updateMenuSummaries();
        }

        @Override
        public void onResume() {
            super.onResume();
            updateMenuSummaries();
        }

        /** Menüs: unter jedem Untermenü kurz anzeigen, was eingestellt ist. */
        private void updateMenuSummaries() {
            android.content.SharedPreferences p = getPreferenceManager().getSharedPreferences();
            if (p == null) return;

            boolean gallery = "gallery".equals(p.getString("mode", "art"));
            String active = "● Wird verwendet · ";
            summary("menu_style", "Aktiv: " + (gallery ? "Galerie" : "Art") + " · "
                    + (gallery ? clockWeather(p, "gallery_clock", "gallery_weather") : clockWeather(p, "art_clock", "art_weather")));
            summary("menu_art", (gallery ? "" : active)
                    + label(p, "matte", "modern", R.array.matte_labels, R.array.matte_values)
                    + " · " + label(p, "matte_color", "#F4F2EE", R.array.matte_color_labels, R.array.matte_color_values)
                    + " · " + clockWeather(p, "art_clock", "art_weather"));
            summary("menu_gallery", (gallery ? active : "")
                    + (p.getBoolean("gallery_matte", true) ? "Mit schmalem Rand" : "Ohne Rand")
                    + " · " + clockWeather(p, "gallery_clock", "gallery_weather"));
            summary("use_art", gallery ? "Jetzt zum Art-Stil wechseln" : "● Wird gerade verwendet");
            summary("use_gallery", gallery ? "● Wird gerade verwendet" : "Jetzt zum Galerie-Stil wechseln");

            String place = p.getString("weather_place", "");
            summary("menu_weather", (place.isEmpty() ? "Kein Ort eingestellt" : place)
                    + " · " + ("f".equals(p.getString("weather_unit", "c")) ? "°F" : "°C")
                    + " · " + label(p, "weather_pos", "bottom_left", R.array.pos_labels, R.array.pos_values));
            summary("menu_clock", ("12".equals(p.getString("clock_format", "24")) ? "12 Stunden" : "24 Stunden")
                    + " · " + label(p, "clock_pos", "bottom_right", R.array.pos_labels, R.array.pos_values));

            StringBuilder img = new StringBuilder();
            if (p.getBoolean("source_local", true)) img.append("Eigene Bilder");
            if (p.getBoolean("source_met", true)) {
                if (img.length() > 0) img.append(" · ");
                int n = p.getStringSet("collections", java.util.Collections.emptySet()).size();
                if (n == 0) n = getResources().getStringArray(R.array.collection_defaults).length;
                img.append("Museum (").append(n).append(n == 1 ? " Sammlung)" : " Sammlungen)");
            }
            String src = img.length() > 0 ? img.toString() : "Keine Bildquelle aktiv";
            summary("menu_source", src);
            summary("menu_images", src + " · Wechsel alle "
                    + label(p, "interval", "600", R.array.interval_labels, R.array.interval_values));
            summary("menu_format", (p.getBoolean("crop169", true) ? "16:9-Zuschnitt" : "Ohne Zuschnitt")
                    + " · Mindestauflösung " + label(p, "min_res", "1080", R.array.minres_labels, R.array.minres_values));
        }

        private static String clockWeather(android.content.SharedPreferences p, String clockKey, String weatherKey) {
            boolean c = p.getBoolean(clockKey, false), w = p.getBoolean(weatherKey, false);
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
                    for (String cut : new String[]{" (", " – "}) {
                        int k = l.indexOf(cut);
                        if (k > 0) l = l.substring(0, k);
                    }
                    return l;
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
