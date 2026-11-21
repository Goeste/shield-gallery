package app.galerie.tv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Selbst gewählte 16:9-Ausschnitte für hochgeladene Bilder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CropsTest {

    @Test
    public void autoCropMatchesOldBehaviour() {
        // Querformat 4:3 → mittig, volle Breite
        assertArrayEquals(new int[]{0, 378, 4032, 2268}, Crops.rect(4032, 3024, null));
        // Hochformat → etwas nach oben versetzt (35 %)
        int[] r = Crops.rect(3024, 4032, null);
        assertEquals(1701, r[3]);
        assertEquals(Math.round((4032 - 1701) * 0.35f), r[1]);
    }

    @Test
    public void customCropIsClampedInsideImage() {
        // halbe Breite, ganz rechts unten gewünscht, aber über den Rand hinaus
        int[] r = Crops.rect(4000, 3000, new float[]{0.9f, 0.9f, 0.5f});
        assertEquals(2000, r[2]);
        assertEquals(1125, r[3]);
        assertEquals(2000, r[0]);
        assertEquals(1875, r[1]);
        // zu breiter Wunsch wird auf den größten 16:9-Ausschnitt begrenzt
        r = Crops.rect(3024, 4032, new float[]{0f, 0f, 1f});
        assertEquals(3024, r[2]);
        assertEquals(1701, r[3]);
    }

    @Test
    public void storeRoundTripAndRemove() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Crops.set(ctx, "a.jpg", 0.1f, 0.2f, 0.5f);
        float[] c = Crops.get(ctx, "a.jpg");
        assertEquals(0.1f, c[0], 1e-4f);
        assertEquals(0.2f, c[1], 1e-4f);
        assertEquals(0.5f, c[2], 1e-4f);
        Crops.remove(ctx, "a.jpg");
        assertNull(Crops.get(ctx, "a.jpg"));
    }

    @Test
    public void bitmapIsCutAtChosenPlace() {
        Bitmap b = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888);
        Bitmap out = ArtRepository.crop169(b, new float[]{0.25f, 0.5f, 0.5f});
        assertEquals(800, out.getWidth());
        assertEquals(450, out.getHeight());
    }
}
