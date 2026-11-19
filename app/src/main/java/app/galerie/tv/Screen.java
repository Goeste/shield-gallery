package app.galerie.tv;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/** Physische Auflösung des Fernsehers (nicht die 1080p-Oberfläche), begrenzt auf 4K. */
final class Screen {
    private Screen() { }

    static int[] size(Context ctx) {
        int w = 1920, h = 1080;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            Display.Mode m = d.getMode();
            w = m.getPhysicalWidth();
            h = m.getPhysicalHeight();
        } catch (Exception ignored) { }
        if (w < h) { int t = w; w = h; h = t; }
        if (w > 3840) { h = Math.round(h * 3840f / w); w = 3840; }
        if (w <= 0 || h <= 0) { w = 1920; h = 1080; }
        return new int[]{w, h};
    }
}
