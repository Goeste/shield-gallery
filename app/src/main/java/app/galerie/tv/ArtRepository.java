package app.galerie.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Wählt Kunstwerke aus den aktivierten Quellen und bereitet sie für den Fernseher auf:
 * Fotohintergrund entfernen → auf 16:9 zuschneiden → Mindestauflösung prüfen → auf Bildschirmgröße skalieren.
 */
final class ArtRepository {
    private static final long CACHE_LIMIT = 600L * 1024 * 1024;
    static final float RATIO = 16f / 9f;

    /** Bild passt nicht (zu klein / Hochformat) – nächstes versuchen. */
    static final class RejectedException extends IOException {
        RejectedException(String m) { super(m); }
    }

    private final Context ctx;
    private final MetSource met;
    private final LocalSource local;
    private final Random rnd = new Random();
    private final File cacheDir;

    ArtRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.met = new MetSource(ctx);
        this.local = new LocalSource(ctx);
        this.cacheDir = new File(ctx.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
    }

    static void clearCache(Context ctx) {
        File[] files = new File(ctx.getCacheDir(), "art").listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Mindestgröße (Breite, Höhe) des 16:9-Ausschnitts in Originalpixeln. */
    static int[] minSize(SharedPreferences p) {
        String v = p.getString("min_res", "1080");
        if ("2160".equals(v)) return new int[]{3840, 2160};
        if ("1080".equals(v)) return new int[]{1920, 1080};
        return new int[]{0, 0};
    }

    /** Größe des größten mittigen 16:9-Ausschnitts. */
    static int[] cropSize(int w, int h) {
        if (w / (float) h > RATIO) return new int[]{Math.round(h * RATIO), h};
        return new int[]{w, Math.round(w / RATIO)};
    }

    ArtItem nextItem() throws IOException {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        boolean useMet = p.getBoolean("source_met", true);
        boolean useLocal = p.getBoolean("source_local", true);
        boolean useUsb = p.getBoolean("source_usb", false);
        String folder = p.getString("local_folder", "Galerie");
        boolean landscapeOnly = p.getBoolean("crop169", true);

        if (useLocal && (!useMet || rnd.nextBoolean())) {
            ArtItem item = local.next(useUsb ? folder : null, rnd);
            if (item != null) return item;
        }
        if (useMet) {
            Set<String> cols = p.getStringSet("collections",
                    new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.collection_defaults))));
            return met.next(cols, landscapeOnly, rnd);
        }
        throw new IOException("Keine Bilder: lade eigene Bilder hoch oder aktiviere die Museumskunst");
    }

    void reject(ArtItem item) {
        if (item.key.startsWith("met:")) met.reject(item.key);
        if (item.url != null) //noinspection ResultOfMethodCallIgnored
            new File(cacheDir, sha1(item.url) + ".img").delete();
    }

    /** Lädt das Bild passend für einen Bildschirm von maxW × maxH. */
    Bitmap load(ArtItem item, int maxW, int maxH) throws IOException {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        boolean crop = p.getBoolean("crop169", true);
        int[] min = minSize(p);

        File file = null;
        if (item.url != null) {
            file = new File(cacheDir, sha1(item.url) + ".img");
            if (!file.exists()) {
                trimCache();
                Net.download(item.url, file);
            } else {
                //noinspection ResultOfMethodCallIgnored
                file.setLastModified(System.currentTimeMillis());
            }
        }

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = open(item, file)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        boolean bfOk = o.outWidth > 0 && o.outHeight > 0;
        boolean heif = isHeif(o.outMimeType);

        Bitmap bmp;
        int w, h;
        if ((heif || !bfOk) && Build.VERSION.SDK_INT >= 28) {
            // HEIC/HEIF (iPhone-Fotos) und alles, was BitmapFactory nicht kann:
            // ImageDecoder dreht automatisch richtig und verkleinert schon beim Dekodieren.
            final float[] f = {1f};
            try {
                bmp = ImageDecoder.decodeBitmap(source(item, file), (dec, info, src) -> {
                    int iw = info.getSize().getWidth(), ih = info.getSize().getHeight();
                    f[0] = decodeScale(iw, ih, maxW, maxH);
                    if (f[0] < 1f) dec.setTargetSize(Math.max(1, Math.round(iw * f[0])), Math.max(1, Math.round(ih * f[0])));
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (Exception e) {
                if (file != null) //noinspection ResultOfMethodCallIgnored
                    file.delete();
                throw new IOException(heif ? "HEIC-Bild kann auf diesem Gerät nicht gelesen werden" : "Bild nicht lesbar", e);
            }
            w = Math.round(bmp.getWidth() / f[0]);
            h = Math.round(bmp.getHeight() / f[0]);
            try {
                checkSize(item, crop, min, w, h);
            } catch (RejectedException e) {
                bmp.recycle();
                throw e;
            }
        } else {
            if (!bfOk) {
                if (file != null) //noinspection ResultOfMethodCallIgnored
                    file.delete();
                throw new IOException("Bild nicht lesbar");
            }
            int rotation = item.uri != null || item.file != null ? exifRotation(item, file) : 0;
            w = o.outWidth;
            h = o.outHeight;
            if (rotation % 180 != 0) { int t = w; w = h; h = t; }
            checkSize(item, crop, min, w, h);
            int[] cs = crop ? cropSize(w, h) : new int[]{w, h};

            // Möglichst grob dekodieren, aber nie kleiner als der Bildschirm
            int sample = 1;
            if (crop) {
                while (cs[0] / (sample * 2) >= maxW && cs[1] / (sample * 2) >= maxH) sample *= 2;
            } else {
                while (w / (sample * 2) >= maxW || h / (sample * 2) >= maxH) sample *= 2;
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = open(item, file)) {
                bmp = BitmapFactory.decodeStream(in, null, d);
            }
            if (bmp == null) throw new IOException("Bild nicht dekodierbar");
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                bmp = r;
            }
        }
        float origPerPx = w / (float) bmp.getWidth();

        if (item.url != null) bmp = BorderTrim.trim(bmp);

        if (crop) {
            bmp = crop169(bmp);
            if (bmp.getWidth() * origPerPx < min[0] * 0.97f || bmp.getHeight() * origPerPx < min[1] * 0.97f) {
                bmp.recycle();
                throw new RejectedException("Nach Zuschnitt zu klein");
            }
        }

        float scale = Math.min(1f, Math.min(maxW / (float) bmp.getWidth(), maxH / (float) bmp.getHeight()));
        if (scale < 0.999f) {
            Bitmap s = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale),
                    Math.round(bmp.getHeight() * scale), true);
            if (s != bmp) bmp.recycle();
            bmp = s;
        }
        return bmp;
    }

    /** Hochformat-Museumsbilder und zu kleine Bilder aussortieren. w/h = Originalgröße, richtig gedreht. */
    private static void checkSize(ArtItem item, boolean crop, int[] min, int w, int h) throws RejectedException {
        // Museumsbilder im Hochformat lassen sich nicht sinnvoll auf 16:9 schneiden
        if (crop && item.url != null && w / (float) h < 1.2f) throw new RejectedException("Hochformat");
        int[] cs = crop ? cropSize(w, h) : new int[]{w, h};
        if (cs[0] < min[0] * 0.97f || cs[1] < min[1] * 0.97f)
            throw new RejectedException("Zu klein: " + w + "×" + h);
    }

    /**
     * Verkleinerungsfaktor beim Dekodieren: so klein wie möglich, aber der 16:9-Ausschnitt bleibt mindestens
     * bildschirmgroß – für beide möglichen Ausrichtungen (die Drehung steht erst nach dem Dekodieren fest).
     */
    static float decodeScale(int iw, int ih, int maxW, int maxH) {
        int[] a = cropSize(iw, ih), b = cropSize(ih, iw);
        float fa = Math.max(maxW / (float) a[0], maxH / (float) a[1]);
        float fb = Math.max(maxW / (float) b[0], maxH / (float) b[1]);
        return Math.min(1f, Math.max(fa, fb));
    }

    static boolean isHeif(String mime) {
        return mime != null && (mime.equals("image/heif") || mime.equals("image/heic")
                || mime.startsWith("image/heif-") || mime.startsWith("image/heic-") || mime.equals("image/avif"));
    }

    @androidx.annotation.RequiresApi(28)
    private ImageDecoder.Source source(ArtItem item, File cached) {
        if (cached != null) return ImageDecoder.createSource(cached);
        if (item.file != null) return ImageDecoder.createSource(item.file);
        return ImageDecoder.createSource(ctx.getContentResolver(), item.uri);
    }

    /** Mittiger 16:9-Ausschnitt; bei Hochformat etwas nach oben versetzt (Gesichter, Horizont). */
    static Bitmap crop169(Bitmap src) {
        int[] cs = cropSize(src.getWidth(), src.getHeight());
        if (Math.abs(cs[0] - src.getWidth()) <= 1 && Math.abs(cs[1] - src.getHeight()) <= 1) return src;
        int x = (src.getWidth() - cs[0]) / 2;
        int y = Math.round((src.getHeight() - cs[1]) * (src.getHeight() > src.getWidth() ? 0.35f : 0.5f));
        Bitmap out = Bitmap.createBitmap(src, x, y, cs[0], cs[1]);
        if (out != src) src.recycle();
        return out;
    }

    private int exifRotation(ArtItem item, File file) {
        try (InputStream in = open(item, file)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (ori == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (ori == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) { }
        return 0;
    }

    private InputStream open(ArtItem item, File cached) throws IOException {
        if (cached != null) return new FileInputStream(cached);
        if (item.file != null) return new FileInputStream(item.file);
        InputStream in = ctx.getContentResolver().openInputStream(item.uri);
        if (in == null) throw new IOException("Datei nicht lesbar: " + item.uri);
        return in;
    }

    private void trimCache() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total < CACHE_LIMIT) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total < CACHE_LIMIT * 3 / 4) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static String sha1(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
