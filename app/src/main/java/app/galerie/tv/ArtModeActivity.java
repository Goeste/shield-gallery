package app.galerie.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Kunstmodus als App: läuft dauerhaft (Bildschirm bleibt an).
 * Fernbedienung: ◀ ▶ Bild wechseln · ▼ Stil (Art/Galerie) · OK Bildinfo · ▲ Einstellungen · Zurück beenden.
 */
public class ArtModeActivity extends Activity {
    private ArtFrameView view;
    private ArtController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new ArtFrameView(this);
        setContentView(view);
        controller = new ArtController(this, view);
        Toast.makeText(this, "◀ ▶ Bild · ▼ Stil · OK Info · ▲ Einstellungen", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        controller.start();
    }

    @Override
    protected void onPause() {
        controller.stop();
        super.onPause();
    }

    private static final String[] MODES = {"art", "gallery"};
    private static final String[] MODE_NAMES = {"Art", "Galerie"};

    private void cycleMode() {
        android.content.SharedPreferences p = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        String cur = p.getString("mode", "art");
        int i = 0;
        for (int k = 0; k < MODES.length; k++) if (MODES[k].equals(cur)) i = k;
        i = (i + 1) % MODES.length;
        p.edit().putString("mode", MODES[i]).apply();
        controller.reapplyPrefs();
        Toast.makeText(this, "Stil: " + MODE_NAMES[i], Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                controller.next();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                controller.previous();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                view.flashCaption(0);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                cycleMode();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
