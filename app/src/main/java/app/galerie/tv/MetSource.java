package app.galerie.tv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Gemeinfreie Kunstwerke aus der offenen Sammlungs-API des Metropolitan Museum of Art.
 * Kein API-Schlüssel nötig. Die Objekt-ID-Listen werden eine Woche lang lokal zwischengespeichert.
 */
final class MetSource {
    private static final String TAG = "MetSource";
    private static final String API = "https://collectionapi.metmuseum.org/public/collection/v1";
    private static final String SEARCH = "https://collectionapi.metmuseum.org/public/collection/v1.1/search";
    private static final long ID_CACHE_MS = 7L * 24 * 3600 * 1000;
    private static final int PAGE = 500;
    private static final int MAX_PAGES = 4;

    private static final Map<String, String> QUERIES = new HashMap<>();
    static {
        QUERIES.put("european", "departmentId=11&q=painting");
        QUERIES.put("highlights", "isHighlight=true&q=painting");
        QUERIES.put("landscape", "departmentId=11&q=landscape");
        QUERIES.put("impressionism", "departmentId=11&q=impressionism");
        QUERIES.put("stilllife", "departmentId=11&q=still%20life");
        QUERIES.put("portrait", "departmentId=11&q=portrait");
        QUERIES.put("asian", "departmentId=6&q=landscape%20painting");
        QUERIES.put("ukiyoe", "departmentId=6&q=ukiyo-e");
    }

    private final Context ctx;
    private final Map<String, List<Integer>> idsByCollection = new HashMap<>();
    private final ArrayDeque<Integer> recent = new ArrayDeque<>();
    private final Set<Integer> rejected = new HashSet<>();
    private boolean rejectedLoaded;

    MetSource(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    ArtItem next(Set<String> collections, boolean landscapeOnly, Random rnd) throws IOException {
        List<String> keys = new ArrayList<>();
        for (String c : collections) if (QUERIES.containsKey(c)) keys.add(c);
        if (keys.isEmpty()) keys.add("european");
        loadRejected();

        IOException last = null;
        for (int attempt = 0; attempt < 25; attempt++) {
            String key = keys.get(rnd.nextInt(keys.size()));
            List<Integer> ids;
            try {
                ids = ids(key);
            } catch (IOException e) {
                last = e;
                continue;
            }
            if (ids.isEmpty()) continue;
            int id = ids.get(rnd.nextInt(ids.size()));
            if (rejected.contains(id)) continue;
            if (recent.contains(id) && ids.size() > 60) continue;
            try {
                JSONObject o = new JSONObject(Net.getString(API + "/objects/" + id));
                String img = o.optString("primaryImage");
                if (!o.optBoolean("isPublicDomain") || img.isEmpty()) {
                    reject("met:" + id);
                    continue;
                }
                // Hochformat schon anhand der Maße (cm) aussortieren, bevor das große Bild geladen wird
                if (landscapeOnly) {
                    float ar = aspect(o.optJSONArray("measurements"));
                    if (ar > 0 && ar < 1.2f) {
                        reject("met:" + id);
                        continue;
                    }
                }
                remember(id);
                return new ArtItem("met:" + id, img.replace(" ", "%20"), null,
                        o.optString("title"), o.optString("artistDisplayName"), o.optString("objectDate"));
            } catch (IOException e) {
                last = e;
            } catch (Exception e) {
                Log.w(TAG, "Objekt " + id + " unbrauchbar", e);
            }
        }
        throw last != null ? last : new IOException("Kein passendes Kunstwerk gefunden");
    }

    /** Breite/Höhe aus den Objektmaßen, bevorzugt "Overall"; -1 wenn unbekannt. */
    private static float aspect(JSONArray m) {
        if (m == null) return -1;
        float best = -1;
        for (int i = 0; i < m.length(); i++) {
            JSONObject e = m.optJSONObject(i);
            if (e == null) continue;
            JSONObject em = e.optJSONObject("elementMeasurements");
            if (em == null) continue;
            double w = em.optDouble("Width", 0), h = em.optDouble("Height", 0);
            if (w <= 0 || h <= 0) continue;
            float ar = (float) (w / h);
            if ("Overall".equalsIgnoreCase(e.optString("elementName"))) return ar;
            if (best < 0) best = ar;
        }
        return best;
    }

    /** Objekt dauerhaft überspringen (zu klein, Hochformat, nicht gemeinfrei). */
    synchronized void reject(String key) {
        try {
            int id = Integer.parseInt(key.substring(4));
            if (rejected.add(id)) {
                try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "met_rejected.txt"), true)) {
                    out.write((id + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) { }
    }

    private synchronized void loadRejected() {
        if (rejectedLoaded) return;
        rejectedLoaded = true;
        File f = new File(ctx.getFilesDir(), "met_rejected.txt");
        if (!f.exists()) return;
        try {
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                if (!line.trim().isEmpty()) rejected.add(Integer.parseInt(line.trim()));
            }
        } catch (Exception ignored) { }
    }

    private void remember(int id) {
        recent.addLast(id);
        while (recent.size() > 50) recent.removeFirst();
    }

    private synchronized List<Integer> ids(String key) throws IOException {
        List<Integer> cached = idsByCollection.get(key);
        if (cached != null) return cached;

        File f = new File(ctx.getFilesDir(), "met_" + key + ".json");
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < ID_CACHE_MS) {
            try {
                List<Integer> ids = parseIds(new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
                if (!ids.isEmpty()) {
                    idsByCollection.put(key, ids);
                    return ids;
                }
            } catch (Exception ignored) { }
        }

        JSONArray all = new JSONArray();
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = SEARCH + "?hasImages=true&" + QUERIES.get(key)
                        + "&limit=" + PAGE + "&offset=" + (page * PAGE);
                JSONObject res = new JSONObject(Net.getString(url));
                JSONArray ids = res.optJSONArray("objectIDs");
                if (ids == null || ids.length() == 0) break;
                for (int i = 0; i < ids.length(); i++) all.put(ids.getInt(i));
                if ((page + 1) * PAGE >= res.optInt("total")) break;
            }
        } catch (IOException e) {
            if (all.length() == 0) {
                // Notfalls veralteten Cache verwenden
                if (f.exists()) {
                    try {
                        List<Integer> ids = parseIds(new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
                        idsByCollection.put(key, ids);
                        return ids;
                    } catch (Exception ignored) { }
                }
                throw e;
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(all.toString().getBytes(StandardCharsets.UTF_8));
        }
        List<Integer> ids = parseIds(all);
        idsByCollection.put(key, ids);
        return ids;
    }

    private static List<Integer> parseIds(JSONArray a) {
        List<Integer> ids = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) ids.add(a.optInt(i));
        return ids;
    }
}
