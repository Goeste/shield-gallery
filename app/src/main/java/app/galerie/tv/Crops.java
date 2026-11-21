package app.galerie.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Selbst gewählte 16:9-Ausschnitte für hochgeladene Bilder, je Dateiname.
 * Gespeichert als Bruchteile des (richtig gedrehten) Bildes: links, oben, Breite.
 */
final class Crops {
    private Crops() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("zuschnitte", Context.MODE_PRIVATE);
    }

    /** {x, y, w} als Bruchteile, oder null für den automatischen Ausschnitt. */
    static float[] get(Context ctx, String name) {
        if (name == null) return null;
        String v = prefs(ctx).getString(name, null);
        if (v == null) return null;
        try {
            String[] s = v.split(";");
            float x = Float.parseFloat(s[0]), y = Float.parseFloat(s[1]), w = Float.parseFloat(s[2]);
            if (!(w > 0f && w <= 1f) || !(x >= 0f && x < 1f) || !(y >= 0f && y < 1f)) return null;
            return new float[]{x, y, w};
        } catch (Exception e) {
            return null;
        }
    }

    static void set(Context ctx, String name, float x, float y, float w) {
        prefs(ctx).edit().putString(name, String.format(Locale.US, "%.5f;%.5f;%.5f", x, y, w)).apply();
    }

    static void remove(Context ctx, String name) {
        prefs(ctx).edit().remove(name).apply();
    }

    /**
     * Ausschnitt in Pixeln {x, y, w, h} für ein Bild von W × H. Ohne eigene Wahl: größter 16:9-Ausschnitt,
     * mittig (bei Hochformat etwas nach oben versetzt – Gesichter, Horizont).
     */
    static int[] rect(int W, int H, float[] c) {
        int[] max = ArtRepository.cropSize(W, H);
        int cw, ch, x, y;
        if (c == null) {
            cw = max[0];
            ch = max[1];
            x = (W - cw) / 2;
            y = Math.round((H - ch) * (H > W ? 0.35f : 0.5f));
        } else {
            cw = Math.max(1, Math.min(max[0], Math.round(c[2] * W)));
            ch = Math.max(1, Math.min(H, Math.round(cw / ArtRepository.RATIO)));
            x = Math.round(c[0] * W);
            y = Math.round(c[1] * H);
        }
        x = Math.max(0, Math.min(W - cw, x));
        y = Math.max(0, Math.min(H - ch, y));
        return new int[]{x, y, cw, ch};
    }
}
