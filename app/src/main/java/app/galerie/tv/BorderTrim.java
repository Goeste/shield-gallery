package app.galerie.tv;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Entfernt einfarbige, neutrale Fotohintergründe (schwarz/grau/weiß) rund um Gemälde,
 * wie sie in Museumsaufnahmen häufig vorkommen – damit das Passepartout direkt am Bild anliegt.
 */
final class BorderTrim {
    private static final int SAMPLE_W = 240;
    private static final float MAX_CROP = 0.22f;

    private BorderTrim() { }

    static Bitmap trim(Bitmap src) {
        int sw = Math.min(SAMPLE_W, src.getWidth());
        int sh = Math.max(1, Math.round(src.getHeight() * sw / (float) src.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] px = new int[sw * sh];
        s.getPixels(px, 0, sw, 0, 0, sw, sh);
        if (s != src) s.recycle();

        int ref = px[0];
        if (!neutral(ref)) ref = px[sw - 1];
        if (!neutral(ref)) return src;

        int maxX = Math.round(sw * MAX_CROP), maxY = Math.round(sh * MAX_CROP);
        int top = 0, bottom = 0, left = 0, right = 0;
        while (top < maxY && rowUniform(px, sw, top, ref)) top++;
        while (bottom < maxY && rowUniform(px, sw, sh - 1 - bottom, ref)) bottom++;
        while (left < maxX && colUniform(px, sw, sh, left, ref)) left++;
        while (right < maxX && colUniform(px, sw, sh, sw - 1 - right, ref)) right++;
        if (top + bottom + left + right == 0) return src;
        // bei Gemälden: an allen Seiten etwas wegnehmen (Leinwandkanten, Rahmenschatten)
        top = Math.max(top, 1); bottom = Math.max(bottom, 1); left = Math.max(left, 1); right = Math.max(right, 1);

        // etwas Zugabe, damit keine Reste des Hintergrunds am unregelmäßigen Bildrand bleiben
        float f = src.getWidth() / (float) sw;
        int pad = Math.round(Math.min(src.getWidth(), src.getHeight()) * 0.011f);
        int x0 = left > 0 ? Math.round(left * f) + pad : 0;
        int y0 = top > 0 ? Math.round(top * f) + pad : 0;
        int x1 = src.getWidth() - (right > 0 ? Math.round(right * f) + pad : 0);
        int y1 = src.getHeight() - (bottom > 0 ? Math.round(bottom * f) + pad : 0);
        if (x1 - x0 < src.getWidth() / 2 || y1 - y0 < src.getHeight() / 2) return src;

        Bitmap out = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0);
        if (out != src) src.recycle();
        return out;
    }

    private static boolean neutral(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 28;
    }

    private static boolean close(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b)) < 60;
    }

    private static boolean rowUniform(int[] px, int w, int y, int ref) {
        int ok = 0;
        for (int x = 0; x < w; x++) if (close(px[y * w + x], ref)) ok++;
        return ok >= w * 0.92f;
    }

    private static boolean colUniform(int[] px, int w, int h, int x, int ref) {
        int ok = 0;
        for (int y = 0; y < h; y++) if (close(px[y * w + x], ref)) ok++;
        return ok >= h * 0.92f;
    }
}
