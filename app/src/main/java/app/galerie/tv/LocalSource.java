package app.galerie.tv;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Eigene Bilder:
 *  1. per Handy/PC hochgeladene Bilder (liegen im App-Speicher, keine Berechtigung nötig)
 *  2. optional alle Bilder in einem Ordner "Galerie" auf USB-Stick oder intern (Pictures/Galerie)
 */
final class LocalSource {
    private final Context ctx;
    private List<ArtItem> bag = new ArrayList<>();
    private String bagKey;
    private long scannedAt;

    LocalSource(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    static File uploadDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "eigene_bilder");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static String permission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** Nächstes Bild aus einem gemischten Stapel, oder null wenn keine Bilder vorhanden. usbFolder=null → nur Uploads. */
    synchronized ArtItem next(String usbFolder, Random rnd) {
        String key = String.valueOf(usbFolder);
        long newest = uploadDir(ctx).lastModified();
        boolean stale = System.currentTimeMillis() - scannedAt > 10 * 60 * 1000 || newest > scannedAt;
        if (bag.isEmpty() || stale || !key.equals(bagKey)) {
            bag = scan(usbFolder);
            Collections.shuffle(bag, rnd);
            bagKey = key;
            scannedAt = System.currentTimeMillis();
        }
        if (bag.isEmpty()) return null;
        return bag.remove(bag.size() - 1);
    }

    private List<ArtItem> scan(String usbFolder) {
        List<ArtItem> out = new ArrayList<>();
        File[] files = uploadDir(ctx).listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                out.add(new ArtItem("file:" + f.getName(), null, null, f, "", "", ""));
            }
        }
        if (usbFolder != null && hasPermission(ctx)) out.addAll(scanMediaStore(usbFolder));
        return out;
    }

    private List<ArtItem> scanMediaStore(String folder) {
        List<ArtItem> out = new ArrayList<>();
        folder = folder.trim().replaceAll("^/+|/+$", "");
        if (folder.isEmpty()) folder = "Galerie";

        List<Uri> volumes = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            Set<String> names = MediaStore.getExternalVolumeNames(ctx);
            for (String n : names) volumes.add(MediaStore.Images.Media.getContentUri(n));
        } else {
            volumes.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        }

        for (Uri vol : volumes) {
            String sel;
            String[] args;
            if (Build.VERSION.SDK_INT >= 29) {
                sel = "(" + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR "
                        + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?)";
                args = new String[]{folder + "/%", "%/" + folder + "/%"};
            } else {
                sel = MediaStore.MediaColumns.DATA + " LIKE ?";
                args = new String[]{"%/" + folder + "/%"};
            }
            String[] proj = {MediaStore.MediaColumns._ID};
            try (Cursor c = ctx.getContentResolver().query(vol, proj, sel, args, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    Uri uri = ContentUris.withAppendedId(vol, c.getLong(0));
                    out.add(new ArtItem("local:" + uri, null, uri, "", "", ""));
                }
            } catch (Exception ignored) {
                // Volume evtl. nicht eingehängt
            }
        }
        return out;
    }
}
