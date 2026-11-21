package app.galerie.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.ExifInterface;
import android.os.Build;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Kleiner Webserver im Heimnetz: Bilder vom Handy/PC hochladen, ansehen, löschen. */
final class UploadServer extends NanoHTTPD {
    static final int PORT = 8765;
    private final Context ctx;

    UploadServer(Context ctx) {
        super(PORT);
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession s) {
        try {
            String uri = s.getUri();
            if (Method.GET.equals(s.getMethod()) && ("/".equals(uri) || "/index.html".equals(uri))) {
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", asset("upload.html"));
            }
            if ("/heic2any.min.js".equals(uri)) {
                InputStream in = ctx.getAssets().open("heic2any.min.js");
                Response r = newChunkedResponse(Response.Status.OK, "application/javascript", in);
                r.addHeader("Cache-Control", "max-age=86400");
                return r;
            }
            if ("/api/list".equals(uri)) return json(list());
            if ("/api/thumb".equals(uri)) return thumb(param(s, "name"), "1".equals(param(s, "full")));
            if ("/api/crop".equals(uri) && Method.POST.equals(s.getMethod())) return crop(s);
            if ("/api/upload".equals(uri) && Method.POST.equals(s.getMethod())) return upload(s);
            if ("/api/delete".equals(uri) && Method.POST.equals(s.getMethod())) {
                File f = safeFile(param(s, "name"));
                if (f != null && f.delete()) {
                    Crops.remove(ctx, f.getName());
                    touchDir();
                }
                return json(new JSONObject().put("ok", true));
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Nicht gefunden");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, String.valueOf(e.getMessage()));
        }
    }

    private Response upload(IHTTPSession s) throws Exception {
        Map<String, String> files = new HashMap<>();
        s.parseBody(files);
        String tmp = files.get("file");
        List<String> names = s.getParameters().get("file");
        if (tmp == null) return error("Keine Datei empfangen");
        String original = names != null && !names.isEmpty() ? names.get(0) : "bild.jpg";

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp, o);
        boolean bfOk = o.outWidth > 0;
        boolean heif = ArtRepository.isHeif(o.outMimeType) || original.toLowerCase(Locale.ROOT).matches(".*\\.(heic|heif)$");
        int rotation = bfOk && !heif ? exifRotation(tmp) : 0;

        String base = original.replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[^A-Za-z0-9äöüÄÖÜß _-]", "_");
        if (base.length() > 60) base = base.substring(0, 60);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dir = LocalSource.uploadDir(ctx);

        if (heif || !bfOk || rotation != 0) {
            // HEIC (iPhone) oder gedrehtes Foto: einmal sauber als JPEG speichern – richtig herum gedreht,
            // damit Vorschau, Auflösungsangabe und Anzeige überall stimmen.
            if (Build.VERSION.SDK_INT < 28) {
                if (!bfOk) return error(original + ": Format wird nicht unterstützt");
            } else {
                Bitmap b;
                try {
                    b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(new File(tmp)), (dec, info, src) -> {
                        int iw = info.getSize().getWidth(), ih = info.getSize().getHeight();
                        // höchstens ~24 Megapixel behalten (reicht für 4K-Zuschnitt, schont den Speicher)
                        double f = Math.min(1.0, Math.sqrt(24_000_000.0 / ((double) iw * ih)));
                        if (f < 1.0) dec.setTargetSize((int) Math.round(iw * f), (int) Math.round(ih * f));
                        dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
                } catch (Exception e) {
                    return error(original + (heif
                            ? ": HEIC kann auf dem Shield nicht gelesen werden. Bitte die Seite neu laden und erneut hochladen – der Browser wandelt dann um."
                            : ": kein unterstütztes Bildformat (JPG, PNG, WebP, HEIC)"));
                }
                File dest = new File(dir, stamp + "_" + base + ".jpg");
                try (OutputStream out = new FileOutputStream(dest)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 94, out);
                } finally {
                    b.recycle();
                }
                touchDir();
                return json(describe(dest));
            }
        }

        String ext = "image/png".equals(o.outMimeType) ? ".png" : "image/webp".equals(o.outMimeType) ? ".webp" : ".jpg";
        File dest = new File(dir, stamp + "_" + base + ext);
        try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(dest)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        touchDir();
        return json(describe(dest));
    }

    private JSONArray list() throws Exception {
        File[] files = LocalSource.uploadDir(ctx).listFiles();
        JSONArray a = new JSONArray();
        if (files == null) return a;
        Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        for (File f : files) if (f.isFile()) a.put(describe(f));
        return a;
    }

    private JSONObject describe(File f) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        boolean crop = p.getBoolean("crop169", true);
        float[] custom = Crops.get(ctx, f.getName());
        int[] r = Crops.rect(o.outWidth, o.outHeight, custom);
        int[] cs = crop ? new int[]{r[2], r[3]} : new int[]{o.outWidth, o.outHeight};
        int[] min = ArtRepository.minSize(p);
        boolean ok = cs[0] >= min[0] * 0.97f && cs[1] >= min[1] * 0.97f;
        JSONObject j = new JSONObject()
                .put("name", f.getName())
                .put("w", o.outWidth).put("h", o.outHeight)
                .put("cw", cs[0]).put("ch", cs[1])
                .put("ok", ok)
                .put("min", min[0] > 0 ? min[0] + "×" + min[1] : "")
                .put("minW", min[0]).put("minH", min[1])
                .put("crop", crop)
                .put("custom", custom != null)
                .put("size", f.length());
        // aktueller Ausschnitt als Bruchteile – Startwert für den Editor
        if (o.outWidth > 0 && o.outHeight > 0)
            j.put("cx", r[0] / (double) o.outWidth).put("cy", r[1] / (double) o.outHeight)
                    .put("cwf", r[2] / (double) o.outWidth);
        return j;
    }

    /** Eigenen 16:9-Ausschnitt speichern (x, y, w als Bruchteile) oder mit reset=1 auf automatisch zurücksetzen. */
    private Response crop(IHTTPSession s) throws Exception {
        Map<String, String> body = new HashMap<>();
        s.parseBody(body);
        File f = safeFile(param(s, "name"));
        if (f == null || !f.isFile()) return error("Bild nicht gefunden");
        if ("1".equals(param(s, "reset"))) {
            Crops.remove(ctx, f.getName());
        } else {
            float x, y, w;
            try {
                x = Float.parseFloat(param(s, "x"));
                y = Float.parseFloat(param(s, "y"));
                w = Float.parseFloat(param(s, "w"));
            } catch (Exception e) {
                return error("Ungültiger Ausschnitt");
            }
            if (!(w > 0.02f && w <= 1.0001f) || !(x >= -0.0001f && x < 1f) || !(y >= -0.0001f && y < 1f))
                return error("Ungültiger Ausschnitt");
            Crops.set(ctx, f.getName(), Math.max(0f, x), Math.max(0f, y), Math.min(1f, w));
        }
        return json(describe(f));
    }

    /** Vorschau: so wie auf dem Fernseher (mit Ausschnitt), oder mit full=1 das ganze Bild für den Editor. */
    private Response thumb(String name, boolean full) throws Exception {
        File f = safeFile(name);
        if (f == null || !f.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        boolean crop = !full && PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("crop169", true);
        float[] custom = crop ? Crops.get(ctx, f.getName()) : null;
        // Breite des sichtbaren Teils (ganzes Bild oder Ausschnitt) soll nach dem Verkleinern noch ≥ target sein
        int target = full ? 1400 : 640;
        int visible = crop ? Crops.rect(o.outWidth, o.outHeight, custom)[2] : Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (visible / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), d);
        if (b == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "");
        if (crop) b = ArtRepository.crop169(b, custom);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, full ? 85 : 80, bo);
        b.recycle();
        byte[] bytes = bo.toByteArray();
        Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(bytes), bytes.length);
        r.addHeader("Cache-Control", "no-cache");
        return r;
    }

    private static int exifRotation(String path) {
        try {
            int ori = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (ori == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (ori == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) { }
        return 0;
    }

    private File safeFile(String name) throws IOException {
        if (name == null || name.isEmpty()) return null;
        File dir = LocalSource.uploadDir(ctx);
        File f = new File(dir, name);
        if (!f.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) return null;
        return f;
    }

    private void touchDir() {
        //noinspection ResultOfMethodCallIgnored
        LocalSource.uploadDir(ctx).setLastModified(System.currentTimeMillis());
    }

    private static String param(IHTTPSession s, String k) {
        List<String> v = s.getParameters().get(k);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private Response json(Object o) {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString());
    }

    private Response error(String msg) throws Exception {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8",
                new JSONObject().put("error", msg).toString());
    }

    private String asset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString(StandardCharsets.UTF_8.name());
        }
    }
}
