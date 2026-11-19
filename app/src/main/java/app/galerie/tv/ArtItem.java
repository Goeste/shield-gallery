package app.galerie.tv;

import android.net.Uri;

import java.io.File;

/** Ein einzelnes Kunstwerk – aus dem Netz (url), vom USB-Stick (uri) oder hochgeladen (file). */
public final class ArtItem {
    public final String key;       // eindeutige ID (für "zuletzt gezeigt")
    public final String url;       // Remote-Bild, oder null
    public final Uri uri;          // Bild über MediaStore (USB/intern), oder null
    public final File file;        // hochgeladenes Bild im App-Speicher, oder null
    public final String title;
    public final String artist;
    public final String date;

    public ArtItem(String key, String url, Uri uri, String title, String artist, String date) {
        this(key, url, uri, null, title, artist, date);
    }

    public ArtItem(String key, String url, Uri uri, File file, String title, String artist, String date) {
        this.key = key;
        this.url = url;
        this.uri = uri;
        this.file = file;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.date = date == null ? "" : date;
    }
}
