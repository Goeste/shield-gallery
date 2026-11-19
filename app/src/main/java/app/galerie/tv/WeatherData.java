package app.galerie.tv;

import java.util.ArrayList;
import java.util.List;

final class WeatherData {
    static final class Day {
        final String label;
        final int code;
        final double max, min;

        Day(String label, int code, double max, double min) {
            this.label = label;
            this.code = code;
            this.max = max;
            this.min = min;
        }
    }

    String place;
    double temp;
    int code;
    boolean isDay = true;
    final List<Day> days = new ArrayList<>();
}
