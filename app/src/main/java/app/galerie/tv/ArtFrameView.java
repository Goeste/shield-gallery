package app.galerie.tv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Zeichnet das Kunstwerk wie ein Bild an der Wand – wahlweise randlos in 16:9 oder mit Passepartout –
 * plus optional Uhr und Wetter.
 *
 * Es wird auf eine SurfaceView in der physischen Auflösung des Displays gezeichnet (bis 3840×2160),
 * damit auf einem 4K-Fernseher wirklich 4K-Pixel ankommen (die normale Android-TV-Oberfläche läuft nur in 1080p).
 */
public class ArtFrameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final class Scene {
        final Bitmap bmp;
        final ArtItem item;
        final int matte;
        final int text;
        final float dx, dy; // kleiner Versatz gegen Einbrennen (Anteil der Höhe)

        Scene(Bitmap bmp, ArtItem item, int matte, float dx, float dy) {
            this.bmp = bmp;
            this.item = item;
            this.matte = matte;
            this.text = luminance(matte) > 0.45f ? 0xFF3A3632 : 0xFFE8E2D8;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private final Object lock = new Object();
    private Scene cur, prev;
    private float fade = 1f;
    private float captionAlpha = 0f;
    private String message;
    private WeatherData weather;

    // Einstellungen
    private String mode = "art";          // art | weather | gallery
    private String overlayPos = "bottom_split";
    private String matteStyle = "modern";
    private String matteColorPref = "#F4F2EE";
    private int sizePct = 76;
    private boolean canvasEffect = true;
    private int brightness = 85;
    private boolean captionEnabled = true;
    // Uhr/Datum und Wetter je Modus getrennt schaltbar
    private boolean weatherClock = true, weatherWeather = true;
    private boolean artClock = false, artWeather = false;
    private boolean galleryClock = false, galleryWeather = false;
    private String overlayShade = "full";  // full = Abdunklung + Schriftschatten, text = nur Schriftschatten, none
    private boolean galleryMatte = true;  // sehr schmales Passepartout am Bildschirmrand
    private String weatherMode = "now";

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint canvasBmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texture = new Paint();
    private final Paint grad = new Paint(Paint.DITHER_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint clockPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint infoPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF art = new RectF();
    private final RectF tmp = new RectF();
    private final Random rnd = new Random();
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.GERMANY);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY);

    private ValueAnimator fadeAnim, captionAnim;
    private float lastTexScale = 1f;

    // Render-Thread
    private HandlerThread renderThread;
    private Handler renderHandler;
    private volatile boolean renderPending;
    private int bufW, bufH;
    private final Runnable renderRunnable = this::renderNow;

    public ArtFrameView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        getHolder().addCallback(this);

        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.92f);
        // leicht reduzierter Kontrast + minimal wärmer -> wirkt wie bedrucktes Papier statt Leuchtschirm
        float c = 0.94f, t = (1f - c) * 128f;
        cm.postConcat(new ColorMatrix(new float[]{
                c, 0, 0, 0, t + 4,
                0, c, 0, 0, t + 1,
                0, 0, c, 0, t - 4,
                0, 0, 0, 1, 0}));
        canvasBmpPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        texture.setShader(new BitmapShader(makeNoise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));

        titlePaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        subPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockPaint.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        infoPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    // ---------------------------------------------------------------- Surface / Rendering

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        int[] s = Screen.size(getContext());
        getHolder().setFixedSize(s[0], s[1]);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        renderThread = new HandlerThread("art-render");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (lock) {
            bufW = width;
            bufH = height;
        }
        requestRender();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Handler h = renderHandler;
        renderHandler = null;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            // warten, bis ein laufender Frame fertig ist
            final Object done = new Object();
            synchronized (done) {
                h.post(() -> { synchronized (done) { done.notifyAll(); } });
                try { done.wait(500); } catch (InterruptedException ignored) { }
            }
        }
        if (renderThread != null) renderThread.quitSafely();
        renderThread = null;
    }

    public void requestRender() {
        Handler h = renderHandler;
        if (h == null || renderPending) return;
        renderPending = true;
        h.post(renderRunnable);
    }

    private void renderNow() {
        renderPending = false;
        SurfaceHolder holder = getHolder();
        Canvas c = null;
        try {
            try {
                c = holder.lockHardwareCanvas();
            } catch (Exception e) {
                c = holder.lockCanvas();
            }
            if (c == null) return;
            synchronized (lock) {
                drawFrame(c, c.getWidth(), c.getHeight());
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
            }
        }
    }

    // ---------------------------------------------------------------- Öffentliche API

    public void applyPrefs(SharedPreferences p) {
        synchronized (lock) {
            mode = p.getString("mode", "art");
            overlayPos = p.getString("overlay_pos", "bottom_split");
            matteStyle = p.getString("matte", "modern");
            if ("none".equals(matteStyle)) matteStyle = "modern";
            matteColorPref = p.getString("matte_color", "#F4F2EE");
            sizePct = parse(p.getString("art_size", "76"), 76);
            canvasEffect = p.getBoolean("canvas", true);
            brightness = parse(p.getString("brightness", "85"), 85);
            captionEnabled = p.getBoolean("caption", true);
            weatherClock = p.getBoolean("clock", true);
            weatherWeather = p.getBoolean("weather_on", true);
            artClock = p.getBoolean("art_clock", false);
            artWeather = p.getBoolean("art_weather", false);
            galleryClock = p.getBoolean("gallery_clock", false);
            galleryWeather = p.getBoolean("gallery_weather", false);
            overlayShade = p.getString("overlay_shade", "full");
            galleryMatte = p.getBoolean("gallery_matte", true);
            weatherMode = p.getString("weather", "now");
            if (cur != null) cur = new Scene(cur.bmp, cur.item, matteFor(cur.bmp), cur.dx, cur.dy);
        }
        requestRender();
    }

    public void setMessage(String msg) {
        synchronized (lock) { message = msg; }
        requestRender();
    }

    public void setWeather(WeatherData w) {
        synchronized (lock) { weather = w; }
        requestRender();
    }

    public boolean hasArt() {
        synchronized (lock) { return cur != null; }
    }

    /** Neues Bild mit Überblendung anzeigen. Die View übernimmt die Bitmap. */
    public void show(Bitmap bmp, ArtItem item) {
        if (fadeAnim != null) fadeAnim.cancel();
        synchronized (lock) {
            message = null;
            if (prev != null && (cur == null || prev.bmp != cur.bmp) && prev.bmp != bmp) prev.bmp.recycle();
            prev = cur;
            float s = 0.006f;
            cur = new Scene(bmp, item, matteFor(bmp), (rnd.nextFloat() * 2 - 1) * s, (rnd.nextFloat() * 2 - 1) * s);
            fade = 0f;
        }
        fadeAnim = ValueAnimator.ofFloat(0f, 1f);
        fadeAnim.setDuration(prev == null ? 1200 : 2200);
        fadeAnim.setInterpolator(new DecelerateInterpolator(1.4f));
        fadeAnim.addUpdateListener(a -> {
            synchronized (lock) { fade = (float) a.getAnimatedValue(); }
            requestRender();
        });
        fadeAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                synchronized (lock) {
                    if (fade >= 1f && prev != null) {
                        if (prev.bmp != cur.bmp) prev.bmp.recycle();
                        prev = null;
                    }
                }
                requestRender();
            }
        });
        fadeAnim.start();
        if (captionEnabled) flashCaption(1800);
    }

    /** Bildinfo kurz einblenden (auch per OK-Taste). */
    public void flashCaption(long delay) {
        synchronized (lock) {
            if (cur == null || TextUtils.isEmpty(cur.item.title)) return;
        }
        if (captionAnim != null) captionAnim.cancel();
        captionAnim = ValueAnimator.ofFloat(0f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0f);
        captionAnim.setStartDelay(delay);
        captionAnim.setDuration(10000);
        captionAnim.addUpdateListener(a -> {
            synchronized (lock) { captionAlpha = (float) a.getAnimatedValue(); }
            requestRender();
        });
        captionAnim.start();
    }

    public void release() {
        if (fadeAnim != null) fadeAnim.cancel();
        if (captionAnim != null) captionAnim.cancel();
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Zeichnet ein komplettes Bild. Öffentlich, damit es auch ohne Surface (Vorschau/Test) nutzbar ist. */
    public void drawFrame(Canvas canvas, int W, int H) {
        float texScale = Math.max(1f, H / 1080f);
        if (texScale != lastTexScale) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(texScale, texScale);
            texture.getShader().setLocalMatrix(m);
            lastTexScale = texScale;
        }
        titlePaint.setTextSize(H * 0.022f);
        subPaint.setTextSize(H * 0.016f);
        canvas.drawColor(Color.BLACK);
        if (cur == null) {
            if (message != null) {
                subPaint.setColor(0xFFB0A898);
                subPaint.setAlpha(255);
                subPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(message, W / 2f, H / 2f, subPaint);
            }
            drawOverlay(canvas, null, W, H);
            return;
        }
        if (prev != null && fade < 1f) {
            drawScene(canvas, prev, W, H);
            int sc = canvas.saveLayerAlpha(0, 0, W, H, Math.round(fade * 255));
            drawScene(canvas, cur, W, H);
            canvas.restoreToCount(sc);
        } else if (fade < 1f) {
            int sc = canvas.saveLayerAlpha(0, 0, W, H, Math.round(fade * 255));
            drawScene(canvas, cur, W, H);
            canvas.restoreToCount(sc);
        } else {
            drawScene(canvas, cur, W, H);
        }
        drawCaption(canvas, cur, W, H);
        drawOverlay(canvas, cur, W, H);

        if (brightness < 100) {
            fill.setShader(null);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.argb(Math.round((100 - brightness) * 2.3f), 0, 0, 0));
            canvas.drawRect(0, 0, W, H, fill);
        }
    }

    private void drawScene(Canvas c, Scene s, int W, int H) {
        final float bw = s.bmp.getWidth(), bh = s.bmp.getHeight();
        final boolean effect = artMode() && canvasEffect;
        final Paint ap = effect ? canvasBmpPaint : bmpPaint;
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(null);

        if (galleryMode() && galleryMatte) {
            // Sehr schmales Passepartout entlang des Bildschirmrands: der Fernseher wirkt wie der Rahmen
            float b = Math.round(H * 0.024f);
            fill.setColor(s.matte);
            c.drawRect(0, 0, W, H, fill);
            c.drawRect(0, 0, W, H, texture);
            RectF inner = new RectF(b, b, W - b, H - b);
            float scale = Math.max(inner.width() / bw, inner.height() / bh);
            float w = bw * scale, h = bh * scale;
            art.set(inner.centerX() - w / 2f, inner.centerY() - h / 2f, inner.centerX() + w / 2f, inner.centerY() + h / 2f);
            float bevel = Math.max(2f, H * 0.0035f);
            drawBevel(c, inner, bevel, s.matte);
            int sc = c.save();
            c.clipRect(inner);
            c.drawBitmap(s.bmp, null, art, ap);
            c.restoreToCount(sc);
            drawInnerShadow(c, inner, bevel * 1.6f);
            art.set(inner);
            return;
        }

        if (fullBleed()) {
            float screenAr = W / (float) H, ar = bw / bh;
            boolean cover = Math.abs(Math.log(ar / screenAr)) < 0.35;
            float scale = cover ? Math.max(W / bw, H / bh) : Math.min(W / bw, H / bh);
            float w = bw * scale, h = bh * scale;
            art.set((W - w) / 2f, (H - h) / 2f, (W + w) / 2f, (H + h) / 2f);
            if (!cover) {
                fill.setColor(darken(s.matte, 0.82f));
                c.drawRect(0, 0, W, H, fill);
            }
            c.drawBitmap(s.bmp, null, art, ap);
            if (effect) c.drawRect(0, 0, W, H, texture);
            return;
        }

        // Passepartout-Fläche mit Kartonstruktur
        fill.setColor(s.matte);
        c.drawRect(0, 0, W, H, fill);
        c.drawRect(0, 0, W, H, texture);

        float maxH = H * sizePct / 100f;
        float maxW = Math.min(W * 0.92f, W * sizePct / 100f * 1.2f);
        float scale = Math.min(maxW / bw, maxH / bh);
        float w = bw * scale, h = bh * scale;
        float cx = W / 2f + s.dx * H, cy = H / 2f + s.dy * H;
        art.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        final float bevel = H * 0.0105f;

        switch (matteStyle) {
            case "shadowbox":
                drawDropShadow(c, art, H * 0.004f, H * 0.012f);
                c.drawBitmap(s.bmp, null, art, ap);
                break;
            case "flat":
                c.drawBitmap(s.bmp, null, art, ap);
                fill.setColor(darken(s.matte, 0.25f));
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(Math.max(1f, H * 0.0012f));
                c.drawRect(art, fill);
                fill.setStyle(Paint.Style.FILL);
                break;
            default: // modern
                drawBevel(c, art, bevel, s.matte);
                c.drawBitmap(s.bmp, null, art, ap);
                drawInnerShadow(c, art, bevel * 1.3f);
                break;
        }
        if (effect) {
            texture.setAlpha(150);
            c.drawRect(art, texture);
            texture.setAlpha(255);
        }
        // Weiches Raumlicht: oben heller, zu den Rändern hin abfallend
        grad.setShader(new RadialGradient(W * 0.5f, -H * 0.25f, H * 1.75f,
                new int[]{0x00000000, 0x00000000, 0x2A000000, 0x55000000},
                new float[]{0f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, grad);
        grad.setShader(null);
    }

    /** Art = Bild im Rahmen-Stil, Wetter = Vollbild mit Uhr/Wetter, Galerie = Vollbild ohne Effekte. */
    private boolean artMode() {
        return "art".equals(mode);
    }

    private boolean galleryMode() {
        return "gallery".equals(mode);
    }

    private boolean fullBleed() {
        return !artMode();
    }

    private boolean overlayActive() {
        return showClock() || showWeather();
    }

    /** Uhrzeit/Datum im aktuellen Modus eingeschaltet? */
    private boolean showClock() {
        return artMode() ? artClock : galleryMode() ? galleryClock : "weather".equals(mode) && weatherClock;
    }

    /** Wetter im aktuellen Modus eingeschaltet und Daten vorhanden? */
    private boolean showWeather() {
        boolean on = artMode() ? artWeather : galleryMode() ? galleryWeather : "weather".equals(mode) && weatherWeather;
        return on && weather != null;
    }

    /** Bildtitel: im Vollbild in einer Ecke (nie dort, wo Uhr/Wetter stehen), im Art-Modus mittig unter dem Bild. */
    private void drawCaption(Canvas c, Scene s, int W, int H) {
        if (captionAlpha <= 0.01f || TextUtils.isEmpty(s.item.title)) return;
        String sub = s.item.artist;
        if (!TextUtils.isEmpty(s.item.date)) sub = TextUtils.isEmpty(sub) ? s.item.date : sub + ", " + s.item.date;
        boolean overlay = fullBleed();
        float maxTextW = overlay ? W * 0.42f : W * 0.7f;
        CharSequence title = TextUtils.ellipsize(s.item.title, titlePaint, maxTextW, TextUtils.TruncateAt.END);
        CharSequence subT = TextUtils.ellipsize(sub, subPaint, maxTextW, TextUtils.TruncateAt.END);

        int a = Math.round(captionAlpha * 255);
        float x, y;
        int color;
        if (overlay) {
            // Ecke für den Titel: unten links, außer Uhr/Wetter belegen die Ecke
            boolean right = false, top = false;
            if (overlayActive()) {
                if ("bottom_left".equals(overlayPos)) right = true;
                else if ("bottom_center".equals(overlayPos) || split()) top = true;
            }
            float m = H * 0.06f;
            float cx = right ? W : 0, cy = top ? 0 : H;
            int ca = Math.round(captionAlpha * 140);
            grad.setShader(new RadialGradient(cx, cy, H * 0.75f,
                    new int[]{Color.argb(ca, 0, 0, 0), Color.argb(ca * 6 / 10, 0, 0, 0), 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(right ? W - H * 0.75f : 0, top ? 0 : H * 0.25f, right ? W : H * 0.75f, top ? H * 0.75f : H, grad);
            grad.setShader(null);
            Paint.Align al = right ? Paint.Align.RIGHT : Paint.Align.LEFT;
            titlePaint.setTextAlign(al);
            subPaint.setTextAlign(al);
            x = right ? W - m : m;
            y = top ? m + titlePaint.getTextSize() : H - m - subPaint.getTextSize() * 1.6f;
            color = 0xFFF2EEE6;
            titlePaint.setShadowLayer(H * 0.004f, 0, 0, 0x99000000);
            subPaint.setShadowLayer(H * 0.004f, 0, 0, 0x99000000);
        } else {
            titlePaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setTextAlign(Paint.Align.CENTER);
            x = W / 2f;
            color = s.text;
            if (overlayActive() && !overlayPos.startsWith("top")) {
                // Uhr/Wetter belegen den Streifen unter dem Bild: Titel darüber setzen
                float space = art.top;
                float blockH = titlePaint.getTextSize() + subPaint.getTextSize() * 1.6f;
                y = (space - blockH) / 2f + titlePaint.getTextSize() * 0.85f;
                if (space < titlePaint.getTextSize() * 3f) y = titlePaint.getTextSize() * 1.2f;
            } else {
                float space = H - art.bottom;
                y = art.bottom + space * 0.5f - titlePaint.getTextSize() * 0.2f;
                if (space < titlePaint.getTextSize() * 3f) y = H - titlePaint.getTextSize() * 1.6f;
            }
            titlePaint.clearShadowLayer();
            subPaint.clearShadowLayer();
        }
        titlePaint.setColor(color);
        titlePaint.setAlpha(a);
        subPaint.setColor(color);
        subPaint.setAlpha(Math.round(a * 0.8f));
        c.drawText(title, 0, title.length(), x, y, titlePaint);
        c.drawText(subT, 0, subT.length(), x, y + subPaint.getTextSize() * 1.6f, subPaint);
    }

    /**
     * Uhr + Wetter (nur im Wetter-Modus). Position: oben/unten × links/mittig/rechts.
     * Zeilen von oben nach unten: Uhrzeit, Datum, aktuelles Wetter, Ort, 3-Tage-Vorschau.
     */
    private void drawOverlay(Canvas c, Scene s, int W, int H) {
        if (artMode()) {
            if (s != null) drawArtOverlay(c, s, W, H);
            return;
        }
        boolean clockOn = showClock(), showWeather = showWeather();
        if (!clockOn && !showWeather) return;
        if (split()) {
            if (showWeather) drawOverlayBlock(c, W, H, false, -1, false, true);
            if (clockOn) drawOverlayBlock(c, W, H, false, 1, true, false);
            return;
        }
        drawOverlayBlock(c, W, H, overlayPos.startsWith("top"),
                overlayPos.endsWith("left") ? -1 : overlayPos.endsWith("center") ? 0 : 1, clockOn, showWeather);
    }

    /** Ein Uhr/Wetter-Block im Wetter-Modus an Position top/h (-1 links, 0 mittig, 1 rechts). */
    private void drawOverlayBlock(Canvas c, int W, int H, boolean top, int h, boolean withClock, boolean withWeather) {
        boolean forecast = withWeather && "forecast".equals(weatherMode) && weather.days.size() > 1;

        float u = H / 1080f;
        float margin = 66 * u;

        // Höhe des Blocks
        float blockH = 0;
        if (withClock) blockH += 136 * u;                    // Uhr + Datum
        if (withWeather) blockH += 76 * u;                      // Wetterzeile + Ort
        if (forecast) blockH += 104 * u;
        float y = top ? margin : H - margin - blockH;          // Oberkante des Blocks
        float ax = h < 0 ? margin : h > 0 ? W - margin : W / 2f; // Ausrichtungskante

        // Abdunklung hinter dem Block, damit Schrift auf hellen Bildern lesbar bleibt (abschaltbar)
        if ("full".equals(overlayShade)) {
        float gx = h < 0 ? 0 : h > 0 ? W : W / 2f, gy = top ? 0 : H;
        float r = H * (h == 0 ? 0.55f : 0.65f);
        grad.setShader(new RadialGradient(gx, gy, r,
                new int[]{0x8C000000, 0x50000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(Math.max(0, gx - r), Math.max(0, gy - r), Math.min(W, gx + r), Math.min(H, gy + r), grad);
        grad.setShader(null);
        }

        int color = 0xFFF4F0E8;
        float sh = 5 * u;
        boolean textShadow = !"none".equals(overlayShade);
        for (TextPaint p : new TextPaint[]{clockPaint, infoPaint}) {
            p.setColor(color);
            p.setTextAlign(Paint.Align.LEFT);
            if (textShadow) p.setShadowLayer(sh, 0, u, 0x80000000); else p.clearShadowLayer();
        }
        iconPaint.setColor(color);
        if (textShadow) iconPaint.setShadowLayer(sh, 0, u, 0x80000000); else iconPaint.clearShadowLayer();

        if (withClock) {
            Date now = new Date();
            clockPaint.setTextSize(96 * u);
            clockPaint.setAlpha(245);
            String t = clockFmt.format(now);
            float tw = clockPaint.measureText(t);
            // dünne Ziffern optisch bündig zur Kante
            c.drawText(t, startX(ax, h, tw) + (h < 0 ? -4 * u : h > 0 ? 4 * u : 0), y + 84 * u, clockPaint);
            infoPaint.setTextSize(24 * u);
            infoPaint.setAlpha(200);
            String d = dateFmt.format(now);
            c.drawText(d, startX(ax, h, infoPaint.measureText(d)), y + 124 * u, infoPaint);
            y += 136 * u;
        }

        if (withWeather) {
            infoPaint.setTextSize(30 * u);
            infoPaint.setAlpha(240);
            String line = Math.round(weather.temp) + "°  " + WeatherIcons.label(weather.code);
            float iconW = 42 * u, gap = 14 * u;
            float total = iconW + gap + infoPaint.measureText(line);
            float x0 = startX(ax, h, total);
            iconPaint.setStrokeWidth(2.6f * u);
            iconPaint.setAlpha(240);
            WeatherIcons.draw(c, iconPaint, weather.code, weather.isDay, x0 + iconW / 2f, y + 26 * u, iconW);
            c.drawText(line, x0 + iconW + gap, y + 37 * u, infoPaint);
            if (!TextUtils.isEmpty(weather.place)) {
                infoPaint.setTextSize(20 * u);
                infoPaint.setAlpha(170);
                c.drawText(weather.place, startX(ax, h, infoPaint.measureText(weather.place)), y + 68 * u, infoPaint);
            }
            y += 76 * u;
        }

        if (forecast) {
            infoPaint.setTextSize(22 * u);
            float colW = 118 * u;
            int n = Math.min(3, weather.days.size() - 1);
            float x0 = startX(ax, h, n * colW);
            infoPaint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                WeatherData.Day d = weather.days.get(i + 1);
                float cx = x0 + colW * i + colW / 2f;
                infoPaint.setAlpha(210);
                c.drawText(d.label, cx, y + 36 * u, infoPaint);
                iconPaint.setStrokeWidth(2.2f * u);
                iconPaint.setAlpha(230);
                WeatherIcons.draw(c, iconPaint, d.code, true, cx, y + 64 * u, 34 * u);
                infoPaint.setAlpha(230);
                c.drawText(Math.round(d.max) + "° / " + Math.round(d.min) + "°", cx, y + 100 * u, infoPaint);
            }
            infoPaint.setTextAlign(Paint.Align.LEFT);
        }
    }

    /**
     * Uhr + Wetter im Art-Modus: ruhige Zeile(n) auf dem Passepartout, wie ein Museumsschild,
     * im Streifen über oder unter dem Bild, bündig zur Bildkante. Bei "bottom_split" steht das Wetter
     * links und Uhrzeit/Datum rechts. Farbe passend zum Passepartout, keine Abdunklung.
     */
    private void drawArtOverlay(Canvas c, Scene s, int W, int H) {
        boolean clockOn = showClock(), showWeather = showWeather();
        if (!clockOn && !showWeather) return;
        boolean split = split();
        boolean top = overlayPos.startsWith("top");
        int h = split ? 0 : overlayPos.endsWith("left") ? -1 : overlayPos.endsWith("center") ? 0 : 1;
        float strip = top ? art.top : H - art.bottom;
        float size = Math.min(H * 0.026f, strip * 0.34f);
        if (size < H * 0.012f) return;   // kein Platz (sehr großes Bild)

        infoPaint.setTextAlign(Paint.Align.LEFT);
        infoPaint.clearShadowLayer();
        infoPaint.setColor(s.text);
        infoPaint.setTextSize(size);
        clockPaint.setTextAlign(Paint.Align.LEFT);
        clockPaint.clearShadowLayer();
        clockPaint.setColor(s.text);
        clockPaint.setTextSize(size * 1.35f);
        iconPaint.clearShadowLayer();
        iconPaint.setColor(s.text);
        iconPaint.setStrokeWidth(Math.max(1f, size * 0.085f));

        Date now = new Date();
        java.util.List<Seg> clock = new java.util.ArrayList<>();
        java.util.List<Seg> wx = new java.util.ArrayList<>();
        Seg fc = null;
        if (clockOn) {
            // Datum links, Uhrzeit rechts daneben
            clock.add(new Seg(dateFmt.format(now), infoPaint, 190, -1));
            clock.add(new Seg(clockFmt.format(now), clockPaint, 235, -1));
        }
        if (showWeather) {
            wx.add(new Seg(Math.round(weather.temp) + "°  " + WeatherIcons.label(weather.code), infoPaint, 190, weather.code));
            if ("forecast".equals(weatherMode)) {
                StringBuilder b = new StringBuilder();
                int n = Math.min(3, weather.days.size() - 1);
                for (int i = 0; i < n; i++) {
                    WeatherData.Day d = weather.days.get(i + 1);
                    if (b.length() > 0) b.append("   ");
                    b.append(d.label).append(' ').append(Math.round(d.max)).append("°/").append(Math.round(d.min)).append('°');
                }
                if (b.length() > 0) { fc = new Seg(b.toString(), infoPaint, 150, -1); wx.add(fc); }
            }
        }
        float baseline = (top ? art.top / 2f : art.bottom + strip / 2f) + size * 0.36f;
        float gap = size * 2.5f;   // Mindestabstand zwischen linker und rechter Gruppe

        if (split) {
            if (fc != null && segsWidth(wx, size) + segsWidth(clock, size) + gap > art.width()) wx.remove(fc);
            drawSegs(c, wx, art.left, baseline, size);
            drawSegs(c, clock, art.right - segsWidth(clock, size), baseline, size);
            return;
        }
        java.util.List<Seg> all = new java.util.ArrayList<>(clock);
        all.addAll(wx);
        if (fc != null && segsWidth(all, size) > art.width()) all.remove(fc);
        float total = segsWidth(all, size);
        float x = h < 0 ? art.left : h > 0 ? art.right - total : W / 2f - total / 2f;
        drawSegs(c, all, x, baseline, size);
    }

    /** Ein Textstück der Art-Modus-Zeile, optional mit Wettersymbol davor. */
    private static final class Seg {
        final String text; final TextPaint paint; final int alpha; final int icon;
        Seg(String text, TextPaint paint, int alpha, int icon) {
            this.text = text; this.paint = paint; this.alpha = alpha; this.icon = icon;
        }
    }

    private static final String SEP = "   ·   ";

    private float segsWidth(java.util.List<Seg> segs, float size) {
        float w = 0;
        for (int i = 0; i < segs.size(); i++) {
            Seg g = segs.get(i);
            if (i > 0) w += infoPaint.measureText(SEP);
            if (g.icon >= 0) w += size * 1.75f;
            w += g.paint.measureText(g.text);
        }
        return w;
    }

    private void drawSegs(Canvas c, java.util.List<Seg> segs, float x, float baseline, float size) {
        for (int i = 0; i < segs.size(); i++) {
            Seg g = segs.get(i);
            if (i > 0) {
                infoPaint.setAlpha(150);
                c.drawText(SEP, x, baseline, infoPaint);
                x += infoPaint.measureText(SEP);
            }
            if (g.icon >= 0) {
                float iconW = size * 1.3f;
                iconPaint.setAlpha(200);
                WeatherIcons.draw(c, iconPaint, g.icon, weather.isDay, x + iconW / 2f, baseline - size * 0.36f, iconW);
                x += size * 1.75f;
            }
            g.paint.setAlpha(g.alpha);
            c.drawText(g.text, x, baseline, g.paint);
            x += g.paint.measureText(g.text);
        }
    }

    /** Geteilte Position: Wetter unten links, Uhrzeit und Datum unten rechts. */
    private boolean split() {
        return "bottom_split".equals(overlayPos);
    }

    /** Linker Startpunkt eines Elements der Breite w bei Ausrichtung h (-1 links, 0 mittig, 1 rechts). */
    private static float startX(float ax, int h, float w) {
        return h < 0 ? ax : h > 0 ? ax - w : ax - w / 2f;
    }

    /** Schrägschnitt des Passepartouts: helle Kartonkante, von oben links beleuchtet. */
    private void drawBevel(Canvas c, RectF r, float b, int matte) {
        int core = blend(0xFFFBF8F1, matte, 0.18f);
        float l = r.left, t = r.top, rr = r.right, bb = r.bottom;
        facet(c, l - b, t - b, rr + b, t - b, rr, t, l, t, shade(core, 1.0f));
        facet(c, l - b, t - b, l, t, l, bb, l - b, bb + b, shade(core, 0.95f));
        facet(c, rr + b, t - b, rr + b, bb + b, rr, bb, rr, t, shade(core, 0.88f));
        facet(c, l - b, bb + b, l, bb, rr, bb, rr + b, bb + b, shade(core, 0.84f));
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(Math.max(1f, b * 0.08f));
        fill.setColor(darken(matte, 0.12f));
        tmp.set(l - b, t - b, rr + b, bb + b);
        c.drawRect(tmp, fill);
        fill.setStyle(Paint.Style.FILL);
    }

    private void facet(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int color) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.lineTo(x4, y4);
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    private void drawInnerShadow(Canvas c, RectF r, float d) {
        grad.setShader(new LinearGradient(0, r.top, 0, r.top + d, 0x60000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.right, r.top + d, grad);
        grad.setShader(new LinearGradient(r.left, 0, r.left + d * 0.7f, 0, 0x38000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.left + d * 0.7f, r.bottom, grad);
        grad.setShader(new LinearGradient(r.right, 0, r.right - d * 0.5f, 0, 0x22000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(r.right - d * 0.5f, r.top, r.right, r.bottom, grad);
        grad.setShader(null);
    }

    private void drawDropShadow(Canvas c, RectF r, float offset, float radius) {
        int steps = 16;
        for (int i = steps; i >= 1; i--) {
            float grow = radius * i / steps;
            tmp.set(r.left - grow, r.top - grow + offset * 2.5f, r.right + grow, r.bottom + grow + offset * 2.5f);
            fill.setColor(Color.argb(11, 0, 0, 0));
            c.drawRoundRect(tmp, grow, grow, fill);
        }
        tmp.set(r.left, r.top + offset, r.right, r.bottom + offset);
        fill.setColor(Color.argb(40, 0, 0, 0));
        c.drawRect(tmp, fill);
    }

    // ---------------------------------------------------------------- Farbe

    private int matteFor(Bitmap bmp) {
        if (!"auto".equals(matteColorPref)) {
            try {
                return Color.parseColor(matteColorPref);
            } catch (Exception ignored) {
                return 0xFFF4F2EE;
            }
        }
        Bitmap one = Bitmap.createScaledBitmap(bmp, 1, 1, true);
        int avg = one.getPixel(0, 0);
        if (one != bmp) one.recycle();
        float[] hsv = new float[3];
        Color.colorToHSV(avg, hsv);
        hsv[1] = Math.min(hsv[1] * 0.45f, 0.16f);
        hsv[2] = 0.87f;
        return Color.HSVToColor(hsv);
    }

    private static float luminance(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    private static int shade(int c, float f) {
        return Color.rgb(Math.min(255, Math.round(Color.red(c) * f)),
                Math.min(255, Math.round(Color.green(c) * f)),
                Math.min(255, Math.round(Color.blue(c) * f)));
    }

    private static int darken(int c, float amount) {
        return shade(c, 1f - amount);
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private static Bitmap makeNoise() {
        int n = 256;
        int[] px = new int[n * n];
        Random r = new Random(42);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int a = r.nextInt(14);
                if ((x % 3 == 0) ^ (y % 3 == 0)) a += 3;
                px[y * n + x] = r.nextBoolean() ? Color.argb(a, 255, 255, 255) : Color.argb(a, 0, 0, 0);
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
