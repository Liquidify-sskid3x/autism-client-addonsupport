package autismclient.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class AutismHttp {
    private AutismHttp() {
    }

    public static JsonObject getJson(String url, String bearerToken) {
        try {
            HttpURLConnection connection = open(url);
            connection.setRequestMethod("GET");
            if (bearerToken != null && !bearerToken.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setRequestProperty("Accept", "application/json");
            return readJson(connection);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonObject postJson(String url, String body) {
        try {
            HttpURLConnection connection = open(url);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            writeBody(connection, body);
            return readJson(connection);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonObject postForm(String url, String body) {
        try {
            HttpURLConnection connection = open(url);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            writeBody(connection, body);
            return readJson(connection);
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(Minecraft.getInstance().getProxy());
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "Autism");
        return connection;
    }

    private static void writeBody(HttpURLConnection connection, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
    }

    private static JsonObject readJson(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) return null;
        try (InputStream input = connection.getInputStream()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        }
    }
}
