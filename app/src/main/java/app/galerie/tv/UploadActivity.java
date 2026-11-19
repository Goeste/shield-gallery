package app.galerie.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Zeigt Adresse + QR-Code; solange der Bildschirm offen ist, nimmt das Shield Bilder vom Handy/PC an. */
public class UploadActivity extends Activity {
    private UploadServer server;
    private TextView count;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            File[] f = LocalSource.uploadDir(UploadActivity.this).listFiles();
            int n = f == null ? 0 : f.length;
            count.setText(n == 1 ? "1 eigenes Bild in der Galerie" : n + " eigene Bilder in der Galerie");
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String ip = localIp();
        String url = ip == null ? null : "http://" + ip + ":" + UploadServer.PORT;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(0xFF1E1C1A);
        int pad = dp(64);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        root.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        text.addView(tv("Bilder hinzufügen", 34, 0xFFF2EEE6, Typeface.create(Typeface.SERIF, Typeface.ITALIC)));
        text.addView(space(18));
        if (url == null) {
            text.addView(tv("Keine Netzwerkverbindung gefunden. Verbinde das Shield mit dem WLAN/LAN.", 18, 0xFFE0A46A, null));
        } else {
            text.addView(tv("1.  Handy oder Computer ins selbe WLAN", 18, 0xFFB0A898, null));
            text.addView(tv("2.  QR-Code scannen oder im Browser öffnen:", 18, 0xFFB0A898, null));
            TextView u = tv(url, 30, 0xFFC9B79C, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            u.setPadding(dp(28), dp(8), 0, dp(8));
            text.addView(u);
            text.addView(tv("3.  Bilder auswählen – sie erscheinen beim nächsten Bildwechsel", 18, 0xFFB0A898, null));
        }
        text.addView(space(28));
        count = tv("", 16, 0xFF8A8278, null);
        text.addView(count);
        text.addView(tv("Tipp: Querformat, mindestens 1920×1080, am besten 4K (3840×2160).", 16, 0xFF8A8278, null));
        text.addView(tv("Zurück-Taste beendet den Upload-Modus.", 16, 0xFF8A8278, null));

        if (url != null) {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qr(url, 600));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(14));
            qr.setBackground(bg);
            qr.setPadding(dp(14), dp(14), dp(14), dp(14));
            root.addView(qr, new LinearLayout.LayoutParams(dp(260), dp(260)));
        }
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            server = new UploadServer(this);
            server.start(30000, false);
        } catch (Exception e) {
            count.setText("Server konnte nicht starten: " + e.getMessage());
        }
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        if (server != null) server.stop();
        server = null;
        super.onStop();
    }

    private TextView tv(String s, int sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private android.view.View space(int dp) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    px[y * size + x] = m.get(x, y) ? 0xFF1E1C1A : Color.WHITE;
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}
