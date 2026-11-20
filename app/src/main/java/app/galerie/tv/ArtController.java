package app.galerie.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Steuert die Diashow: lädt im Hintergrund, hält das nächste Bild vorgeladen bereit,
 * wechselt im eingestellten Intervall und merkt sich den Verlauf für "zurück".
 */
final class ArtController {
    private static final String TAG = "ArtController";
    private static final long RETRY_MS = 30_000;

    private final Context ctx;
    private final ArtFrameView view;
    private final ArtRepository repo;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;

    private final List<ArtItem> history = new ArrayList<>();
    private int historyPos = -1;

    // Vorgeladenes nächstes Bild (nur im Worker-Thread schreiben, im Main-Thread abholen)
    private volatile Bitmap preBmp;
    private volatile ArtItem preItem;
    private volatile boolean loading;
    private boolean running;
    private long intervalMs = 600_000;
    private int maxW, maxH;

    private final Runnable tick = this::next;

    ArtController(Context ctx, ArtFrameView view) {
        this.ctx = ctx;
        this.view = view;
        this.repo = new ArtRepository(ctx);
    }

    void start() {
        if (running) return;
        running = true;
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        migratePrefs(p);
        view.applyPrefs(p);
        try {
            intervalMs = Long.parseLong(p.getString("interval", "600")) * 1000L;
        } catch (NumberFormatException e) {
            intervalMs = 600_000;
        }

        int[] screen = Screen.size(ctx);
        maxW = screen[0];
        maxH = screen[1];

        thread = new HandlerThread("art-loader");
        thread.start();
        worker = new Handler(thread.getLooper());
        if (!view.hasArt()) view.setMessage(ctx.getString(R.string.loading));
        next();
        main.post(clockTick);
        weatherStarted = false;
        maybeStartWeather(p);
    }

    private boolean weatherStarted;

    private void maybeStartWeather(SharedPreferences p) {
        if (weatherStarted) return;
        String mode = p.getString("mode", "art");
        boolean overlay = "weather".equals(mode) || ("art".equals(mode) && p.getBoolean("art_overlay", false));
        if (!overlay || "off".equals(p.getString("weather", "now"))) return;
        weatherStarted = true;
        main.post(weatherTick);
    }

    /**
     * Version 1.3: Passepartout schmaler. Alte Bildgrößen (55/68/80) auf die neuen Stufen (62/76/84) umstellen,
     * einmalig, damit die Auswahl in den Einstellungen gültig bleibt.
     */
    static void migratePrefs(SharedPreferences p) {
        // Version 1.4: Standard-Position "Wetter unten links, Uhr unten rechts" einmalig übernehmen
        if (!p.getBoolean("overlay_split_v1", false)) {
            p.edit().putBoolean("overlay_split_v1", true).putString("overlay_pos", "bottom_split").apply();
        }
        if (p.getBoolean("art_size_v2", false)) return;
        String old = p.getString("art_size", null);
        String neu = "55".equals(old) ? "62" : "68".equals(old) ? "76" : "80".equals(old) ? "84" : old;
        SharedPreferences.Editor e = p.edit().putBoolean("art_size_v2", true);
        if (neu != null && !neu.equals(old)) e.putString("art_size", neu);
        e.apply();
    }

    /** Einstellungen neu anwenden (z. B. nach Moduswechsel per Fernbedienung), ohne das Bild neu zu laden. */
    void reapplyPrefs() {
        if (!running) return;
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        view.applyPrefs(p);
        maybeStartWeather(p);
    }

    // Uhr: zur vollen Minute neu zeichnen
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            view.requestRender();
            long now = System.currentTimeMillis();
            main.postDelayed(this, 60_000 - (now % 60_000) + 50);
        }
    };

    // Wetter: alle 30 Minuten aktualisieren
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Handler w = worker;
            if (w != null) w.post(() -> {
                try {
                    WeatherData d = WeatherService.fetch(ctx);
                    main.post(() -> view.setWeather(d));
                } catch (Exception e) {
                    Log.w(TAG, "Wetter nicht verfügbar", e);
                }
            });
            main.postDelayed(this, 30 * 60_000);
        }
    };

    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        worker = null;
        Bitmap b = preBmp;
        preBmp = null;
        preItem = null;
        loading = false;
        if (b != null) b.recycle();
        view.release();
    }

    /** Nächstes Bild – das vorgeladene, falls bereit. */
    void next() {
        if (!running) return;
        main.removeCallbacks(tick);
        if (historyPos < history.size() - 1) {
            // Wir waren im Verlauf zurück: wieder vorwärts gehen
            historyPos++;
            loadSpecific(history.get(historyPos));
            return;
        }
        Bitmap b = preBmp;
        ArtItem it = preItem;
        if (b != null && it != null) {
            preBmp = null;
            preItem = null;
            present(b, it, true);
            prefetch();
        } else if (!loading) {
            loadAndShow();
        } else {
            // Vorladen läuft noch – gleich nochmal versuchen
            main.postDelayed(tick, 3000);
        }
    }

    void previous() {
        if (!running || historyPos <= 0) return;
        main.removeCallbacks(tick);
        historyPos--;
        loadSpecific(history.get(historyPos));
    }

    private void present(Bitmap b, ArtItem it, boolean addToHistory) {
        if (!running) {
            b.recycle();
            return;
        }
        if (addToHistory) {
            history.add(it);
            if (history.size() > 30) history.remove(0);
            historyPos = history.size() - 1;
        }
        view.show(b, it);
        main.removeCallbacks(tick);
        main.postDelayed(tick, intervalMs);
    }

    private void loadAndShow() {
        Handler w = worker;
        if (w == null) return;
        loading = true;
        w.post(() -> {
            Exception err = null;
            for (int i = 0; i < 25 && running; i++) {
                ArtItem it = null;
                try {
                    it = repo.nextItem();
                    Bitmap b = repo.load(it, maxW, maxH);
                    final ArtItem fit = it;
                    loading = false;
                    main.post(() -> {
                        present(b, fit, true);
                        prefetch();
                    });
                    return;
                } catch (ArtRepository.RejectedException e) {
                    repo.reject(it);
                    err = e;
                } catch (Exception e) {
                    err = e;
                    Log.w(TAG, "Laden fehlgeschlagen", e);
                }
            }
            loading = false;
            final Exception fe = err;
            main.post(() -> {
                if (!view.hasArt()) {
                    view.setMessage(ctx.getString(R.string.no_art)
                            + (fe != null && fe.getMessage() != null ? "  (" + fe.getMessage() + ")" : ""));
                }
                main.postDelayed(tick, RETRY_MS);
            });
        });
    }

    private void loadSpecific(ArtItem item) {
        Handler w = worker;
        if (w == null) return;
        w.post(() -> {
            try {
                Bitmap b = repo.load(item, maxW, maxH);
                main.post(() -> present(b, item, false));
            } catch (Exception e) {
                Log.w(TAG, "Verlauf-Bild nicht ladbar", e);
                main.post(() -> main.postDelayed(tick, 2000));
            }
        });
    }

    private void prefetch() {
        Handler w = worker;
        if (w == null || preBmp != null || loading) return;
        loading = true;
        w.post(() -> {
            for (int i = 0; i < 25 && running; i++) {
                ArtItem it = null;
                try {
                    it = repo.nextItem();
                    Bitmap b = repo.load(it, maxW, maxH);
                    if (!running) {
                        b.recycle();
                        break;
                    }
                    preItem = it;
                    preBmp = b;
                    break;
                } catch (ArtRepository.RejectedException e) {
                    repo.reject(it);
                } catch (Exception e) {
                    Log.w(TAG, "Vorladen fehlgeschlagen", e);
                }
            }
            loading = false;
        });
    }
}
