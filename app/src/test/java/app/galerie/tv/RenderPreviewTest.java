package app.galerie.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;

/** Rendert die echte ArtFrameView (gleicher Zeichencode wie auf dem Fernseher) als Vorschau-Bilder. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class RenderPreviewTest {

    @Test
    public void renderStyles() throws Exception {
        WeatherData w = new WeatherData();
        w.place = "Hamburg";
        w.temp = 4.1;
        w.code = 2;
        w.isDay = true;
        w.days.add(new WeatherData.Day("Heute", 2, 6, 1));
        w.days.add(new WeatherData.Day("Fr", 61, 7, 3));
        w.days.add(new WeatherData.Day("Sa", 3, 5, 0));
        w.days.add(new WeatherData.Day("So", 0, 8, -1));

        String vg = "vangogh.jpg", an = "andes.jpg";
        String vgT = "Wheat Field with Cypresses", vgA = "Vincent van Gogh", vgD = "1889";
        String anT = "Heart of the Andes", anA = "Frederic Edwin Church", anD = "1859";
        // pos: "Wetter|Uhr" oder eine Position für beide; Modus-Suffix + = Uhr und Wetter, ~ = nur Wetter, - = ohne Rand
        render("01_art", "art", "bottom_left|bottom_right", "now", true, vg, vgT, vgA, vgD, w, 1920, 1080);
        render("02_galerie_unten_rechts", "gallery+-", "bottom_right", "forecast", false, an, anT, anA, anD, w, 1920, 1080);
        render("03_galerie_oben_links_12h", "gallery+-", "top_left", "now", false, vg, vgT, vgA, vgD, w, 1920, 1080);
        render("04_galerie_unten_mittig", "gallery+-", "bottom_center", "forecast", true, an, anT, anA, anD, w, 1920, 1080);
        render("05_galerie", "gallery", "bottom_left|bottom_right", "now", true, vg, vgT, vgA, vgD, w, 1920, 1080);
        render("06_art_geteilt", "art+", "bottom_left|bottom_right", "now", true, vg, vgT, vgA, vgD, w, 1920, 1080);
        render("07_art_uhr_wetter_oben_links_4k", "art+", "top_left", "forecast", true, an, anT, anA, anD, w, 3840, 2160);
        render("08_galerie_wetter_oben_rechts_uhr_unten_links", "gallery+", "top_right|bottom_left", "forecast", true, vg, vgT, vgA, vgD, w, 1920, 1080);
        render("09_galerie_uhr_wetter", "gallery+", "bottom_left|bottom_right", "now", true, an, anT, anA, anD, w, 1920, 1080);
        render("10_art_nur_wetter", "art~", "bottom_left|bottom_right", "forecast", true, an, anT, anA, anD, w, 1920, 1080);
        render("11_galerie_ohne_schattierung", "gallery+", "bottom_left|bottom_right", "now", false, an, anT, anA, anD, w, 1920, 1080);
        render("12_art_wetter_oben_uhr_unten_12h", "art+", "top_right|bottom_center", "now", true, vg, vgT, vgA, vgD, w, 1920, 1080);
    }

    private void render(String name, String mode, String pos, String weatherMode, boolean caption, String sample,
                        String title, String artist, String date, WeatherData weather, int W, int H) throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        boolean artOverlay = mode.contains("+");
        boolean weatherOnly = mode.contains("~");   // nur Wetter, ohne Uhr
        boolean noMatte = mode.contains("-");
        mode = mode.replace("+", "").replace("~", "").replace("-", "");
        String wPos = pos.contains("|") ? pos.split("\\|")[0] : pos;
        String cPos = pos.contains("|") ? pos.split("\\|")[1] : pos;
        p.edit().putBoolean("art_clock", artOverlay).putBoolean("art_weather", artOverlay || weatherOnly)
                .putBoolean("gallery_clock", artOverlay).putBoolean("gallery_weather", artOverlay)
                .putString("overlay_shade", name.contains("ohne_schattierung") ? "none" : "full")
                .putBoolean("gallery_matte", !noMatte).putString("mode", mode)
                .putString("weather_pos", wPos).putString("clock_pos", cPos)
                .putString("clock_format", name.contains("12h") ? "12" : "24")
                .putString("matte", "modern").putString("matte_color", "#F4F2EE").putString("art_size", "76")
                .putBoolean("canvas", true).putString("brightness", "100")
                .putBoolean("art_caption", caption).putBoolean("gallery_caption", caption)
                .putString("weather", weatherMode).commit();

        ArtFrameView v = new ArtFrameView(ctx);
        v.applyPrefs(p);
        v.setWeather(weather);

        // gleiche Aufbereitung wie ArtRepository.load: Rand entfernen, 16:9 zuschneiden, auf Bildschirm skalieren
        Bitmap art;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(sample)) {
            art = BitmapFactory.decodeStream(in);
        }
        System.out.println(sample + ": " + art.getWidth() + "x" + art.getHeight());
        art = BorderTrim.trim(art.copy(Bitmap.Config.ARGB_8888, false));
        art = ArtRepository.crop169(art);
        System.out.println("  nach Rand/16:9: " + art.getWidth() + "x" + art.getHeight());
        float scale = Math.min(1f, Math.min(W / (float) art.getWidth(), H / (float) art.getHeight()));
        art = Bitmap.createScaledBitmap(art, Math.round(art.getWidth() * scale), Math.round(art.getHeight() * scale), true);

        v.show(art, new ArtItem("t", null, null, title, artist, date));
        v.release();
        set(v, "fade", 1f);
        set(v, "captionAlpha", caption ? 1f : 0f);

        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        v.drawFrame(new Canvas(out), W, H);
        File dir = new File("build/previews");
        dir.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(dir, name + ".jpg"))) {
            out.compress(Bitmap.CompressFormat.JPEG, 90, fo);
        }
    }

    private static void set(Object o, String name, Object value) throws Exception {
        Field f = ArtFrameView.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }
}
