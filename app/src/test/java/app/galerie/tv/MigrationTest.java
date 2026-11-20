package app.galerie.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Einstellungen von Version 1.8 müssen beim Update auf das neue Menü (1.9) übernommen werden. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MigrationTest {

    private SharedPreferences v18() {
        Context ctx = ApplicationProvider.getApplicationContext();
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        p.edit().clear()
                .putBoolean("clock_weather_v1", true).putBoolean("overlay_split_v1", true).putBoolean("art_size_v2", true)
                .commit();
        return p;
    }

    @Test
    public void weatherModeBecomesGalleryWithoutBorder() {
        SharedPreferences p = v18();
        p.edit().putString("mode", "weather").putBoolean("clock", true).putBoolean("weather_on", false)
                .putBoolean("caption", false).putString("overlay_pos", "top_left").commit();
        ArtController.migratePrefs(p);
        assertEquals("gallery", p.getString("mode", null));
        assertFalse(p.getBoolean("gallery_matte", true));
        assertTrue(p.getBoolean("gallery_clock", false));
        assertFalse(p.getBoolean("gallery_weather", true));
        assertFalse(p.getBoolean("art_caption", true));
        assertFalse(p.getBoolean("gallery_caption", true));
        assertEquals("top_left", p.getString("clock_pos", null));
        assertEquals("top_left", p.getString("weather_pos", null));
        assertFalse(p.contains("overlay_pos"));
    }

    @Test
    public void splitBecomesWeatherLeftClockRight() {
        SharedPreferences p = v18();
        p.edit().putString("mode", "art").putString("overlay_pos", "bottom_split").putBoolean("gallery_matte", true).commit();
        ArtController.migratePrefs(p);
        assertEquals("art", p.getString("mode", null));
        assertEquals("bottom_left", p.getString("weather_pos", null));
        assertEquals("bottom_right", p.getString("clock_pos", null));
        assertTrue(p.getBoolean("gallery_matte", false));
        assertTrue(p.getBoolean("art_caption", false));
    }

    @Test
    public void freshInstall() {
        Context ctx = ApplicationProvider.getApplicationContext();
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        p.edit().clear().commit();
        ArtController.migratePrefs(p);
        assertEquals("bottom_left", p.getString("weather_pos", null));
        assertEquals("bottom_right", p.getString("clock_pos", null));
        assertFalse(p.getBoolean("gallery_clock", true));
    }
}
