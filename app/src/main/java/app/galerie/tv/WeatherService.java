package app.galerie.tv;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Wetter von Open-Meteo (kostenlos, ohne API-Schlüssel). */
final class WeatherService {
    private WeatherService() { }

    /** Sucht einen Ort und speichert Koordinaten + Namen in den Einstellungen. Gibt den Anzeigenamen zurück. */
    static String geocode(Context ctx, String query) throws IOException {
        try {
            String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=de&name="
                    + URLEncoder.encode(query.trim(), "UTF-8");
            JSONObject res = new JSONObject(Net.getString(url));
            JSONArray r = res.optJSONArray("results");
            if (r == null || r.length() == 0) throw new IOException("Ort „" + query + "“ nicht gefunden");
            JSONObject o = r.getJSONObject(0);
            String name = o.optString("name");
            PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                    .putString("weather_lat", String.valueOf(o.getDouble("latitude")))
                    .putString("weather_lon", String.valueOf(o.getDouble("longitude")))
                    .putString("weather_place", name)
                    .apply();
            String country = o.optString("country");
            return country.isEmpty() ? name : name + ", " + country;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Aktuelles Wetter + 4 Tage, oder null wenn kein Ort eingestellt ist. */
    static WeatherData fetch(Context ctx) throws IOException {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        String lat = p.getString("weather_lat", null), lon = p.getString("weather_lon", null);
        if (lat == null || lon == null) {
            String city = p.getString("weather_city", "");
            if (city.trim().isEmpty()) return null;
            geocode(ctx, city);
            lat = p.getString("weather_lat", null);
            lon = p.getString("weather_lon", null);
            if (lat == null) return null;
        }
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=4"
                    + ("f".equals(p.getString("weather_unit", "c")) ? "&temperature_unit=fahrenheit" : "");
            JSONObject o = new JSONObject(Net.getString(url));
            JSONObject cur = o.getJSONObject("current");
            WeatherData w = new WeatherData();
            w.place = p.getString("weather_place", "");
            w.temp = cur.getDouble("temperature_2m");
            w.code = cur.getInt("weather_code");
            w.isDay = cur.optInt("is_day", 1) == 1;
            JSONObject d = o.getJSONObject("daily");
            JSONArray time = d.getJSONArray("time"), code = d.getJSONArray("weather_code"),
                    max = d.getJSONArray("temperature_2m_max"), min = d.getJSONArray("temperature_2m_min");
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("EE", Locale.GERMANY);
            for (int i = 0; i < time.length(); i++) {
                String label = i == 0 ? "Heute" : out.format(in.parse(time.getString(i))).replace(".", "");
                w.days.add(new WeatherData.Day(label, code.getInt(i), max.getDouble(i), min.getDouble(i)));
            }
            return w;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
