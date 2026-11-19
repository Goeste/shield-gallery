package app.galerie.tv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Schlichte Linien-Wettersymbole (WMO-Wettercodes), passend zur ruhigen Optik. */
final class WeatherIcons {
    private WeatherIcons() { }

    static String label(int code) {
        switch (code) {
            case 0: return "Klar";
            case 1: return "Überwiegend klar";
            case 2: return "Teils bewölkt";
            case 3: return "Bedeckt";
            case 45: case 48: return "Nebel";
            case 51: case 53: case 55: return "Nieselregen";
            case 56: case 57: return "Gefrierender Niesel";
            case 61: return "Leichter Regen";
            case 63: return "Regen";
            case 65: return "Starker Regen";
            case 66: case 67: return "Gefrierender Regen";
            case 71: return "Leichter Schneefall";
            case 73: return "Schneefall";
            case 75: return "Starker Schneefall";
            case 77: return "Schneegriesel";
            case 80: case 81: case 82: return "Regenschauer";
            case 85: case 86: return "Schneeschauer";
            case 95: return "Gewitter";
            case 96: case 99: return "Gewitter mit Hagel";
            default: return "";
        }
    }

    /** Zeichnet das Symbol zentriert bei (cx, cy) mit Kantenlänge s. Paint muss STROKE sein. */
    static void draw(Canvas c, Paint p, int code, boolean day, float cx, float cy, float s) {
        if (code == 0 || code == 1) {
            if (day) sun(c, p, cx, cy, s, code == 1 ? 0.85f : 1f);
            else moon(c, p, cx, cy, s);
            return;
        }
        if (code == 2) {
            c.save();
            Path cl = cloudPath(cx + s * 0.08f, cy + s * 0.1f, s * 0.8f);
            c.clipOutPath(cl);
            if (day) sun(c, p, cx - s * 0.18f, cy - s * 0.16f, s * 0.7f, 1f);
            else moon(c, p, cx - s * 0.18f, cy - s * 0.16f, s * 0.7f);
            c.restore();
            c.drawPath(cl, p);
            return;
        }
        if (code == 45 || code == 48) {
            for (int i = 0; i < 3; i++) {
                float y = cy - s * 0.22f + i * s * 0.22f;
                float off = (i % 2 == 0 ? 0 : s * 0.1f);
                c.drawLine(cx - s * 0.42f + off, y, cx + s * 0.42f - (s * 0.1f - off), y, p);
            }
            return;
        }
        if (code == 3) {
            c.drawPath(cloudPath(cx, cy, s), p);
            return;
        }
        // Niederschlag: Wolke oben, darunter Tropfen/Flocken/Blitz
        float ccy = cy - s * 0.14f;
        c.drawPath(cloudPath(cx, ccy, s * 0.9f), p);
        float top = cy + s * 0.2f;
        if (code >= 95) {
            Path bolt = new Path();
            bolt.moveTo(cx + s * 0.05f, top - s * 0.04f);
            bolt.lineTo(cx - s * 0.1f, top + s * 0.17f);
            bolt.lineTo(cx + s * 0.06f, top + s * 0.17f);
            bolt.lineTo(cx - s * 0.08f, top + s * 0.38f);
            c.drawPath(bolt, p);
        } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            Paint.Style st = p.getStyle();
            p.setStyle(Paint.Style.FILL);
            float r = s * 0.045f;
            c.drawCircle(cx - s * 0.2f, top + s * 0.08f, r, p);
            c.drawCircle(cx, top + s * 0.2f, r, p);
            c.drawCircle(cx + s * 0.2f, top + s * 0.08f, r, p);
            p.setStyle(st);
        } else {
            boolean drizzle = code >= 51 && code <= 57;
            float len = drizzle ? s * 0.1f : s * 0.22f;
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * s * 0.2f;
                c.drawLine(x + s * 0.04f, top, x - s * 0.04f, top + len, p);
            }
        }
    }

    private static void sun(Canvas c, Paint p, float cx, float cy, float s, float rays) {
        c.drawCircle(cx, cy, s * 0.2f, p);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float r1 = s * 0.3f, r2 = s * (0.3f + 0.13f * rays);
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, p);
        }
    }

    private static void moon(Canvas c, Paint p, float cx, float cy, float s) {
        Path a = new Path();
        a.addCircle(cx, cy, s * 0.3f, Path.Direction.CW);
        Path b = new Path();
        b.addCircle(cx + s * 0.16f, cy - s * 0.12f, s * 0.26f, Path.Direction.CW);
        a.op(b, Path.Op.DIFFERENCE);
        c.drawPath(a, p);
    }

    private static Path cloudPath(float cx, float cy, float s) {
        Path p = new Path();
        p.addCircle(cx - s * 0.2f, cy + s * 0.04f, s * 0.17f, Path.Direction.CW);
        Path b = new Path();
        b.addCircle(cx + s * 0.04f, cy - s * 0.06f, s * 0.24f, Path.Direction.CW);
        Path d = new Path();
        d.addCircle(cx + s * 0.26f, cy + s * 0.06f, s * 0.15f, Path.Direction.CW);
        Path base = new Path();
        base.addRoundRect(cx - s * 0.37f, cy + s * 0.04f, cx + s * 0.41f, cy + s * 0.21f, s * 0.085f, s * 0.085f, Path.Direction.CW);
        p.op(b, Path.Op.UNION);
        p.op(d, Path.Op.UNION);
        p.op(base, Path.Op.UNION);
        return p;
    }
}
