package app.galerie.tv;

import android.service.dreams.DreamService;

/** Der Bildschirmschoner: zeigt Kunstwerke im Rahmen, jede Taste beendet ihn. */
public class ArtDreamService extends DreamService {
    private ArtFrameView view;
    private ArtController controller;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        view = new ArtFrameView(this);
        setContentView(view);
        controller = new ArtController(this, view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        controller.start();
    }

    @Override
    public void onDreamingStopped() {
        controller.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (controller != null) controller.stop();
        super.onDetachedFromWindow();
    }
}
