package autismclient.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.util.Tuple;
import net.minecraft.util.Util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class AutismMicrosoftLogin {
    public static final String CLIENT_ID = "4673b348-3efa-4f6a-bbb6-34e141cdc638";
    public static final int PORT = 9675;
    private static volatile HttpServer server;
    private static volatile Consumer<String> callback;

    private AutismMicrosoftLogin() {
    }

    public static String getRefreshToken(Consumer<String> callback) {
        AutismMicrosoftLogin.callback = callback;
        startServer();
        String url = "https://login.live.com/oauth20_authorize.srf?client_id=" + CLIENT_ID + "&response_type=code&redirect_uri=http://127.0.0.1:" + PORT + "&scope=XboxLive.signin%20offline_access&prompt=select_account";
        Util.getPlatform().openUri(url);
        return url;
    }

    public static LoginData login(String refreshToken) {
        JsonObject tokenResponse = AutismHttp.postForm("https://login.live.com/oauth20_token.srf", "client_id=" + CLIENT_ID + "&refresh_token=" + refreshToken + "&grant_type=refresh_token&redirect_uri=http://127.0.0.1:" + PORT);
        if (tokenResponse == null || !tokenResponse.has("access_token") || !tokenResponse.has("refresh_token")) return new LoginData();
        String accessToken = tokenResponse.get("access_token").getAsString();
        refreshToken = tokenResponse.get("refresh_token").getAsString();

        JsonObject xbl = AutismHttp.postJson("https://user.auth.xboxlive.com/user/authenticate", "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=" + accessToken + "\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}");
        if (xbl == null || !xbl.has("Token")) return new LoginData();

        JsonObject xsts = AutismHttp.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + xbl.get("Token").getAsString() + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}");
        if (xsts == null || !xsts.has("Token")) return new LoginData();

        String uhs = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        JsonObject mc = AutismHttp.postJson("https://api.minecraftservices.com/authentication/login_with_xbox", "{\"identityToken\":\"XBL3.0 x=" + uhs + ";" + xsts.get("Token").getAsString() + "\"}");
        if (mc == null || !mc.has("access_token")) return new LoginData();
        String mcToken = mc.get("access_token").getAsString();

        JsonObject ownership = AutismHttp.getJson("https://api.minecraftservices.com/entitlements/mcstore", mcToken);
        if (ownership == null || !hasGameOwnership(ownership)) return new LoginData();

        JsonObject profile = AutismHttp.getJson("https://api.minecraftservices.com/minecraft/profile", mcToken);
        if (profile == null || !profile.has("id") || !profile.has("name")) return new LoginData();
        return new LoginData(mcToken, refreshToken, profile.get("id").getAsString(), profile.get("name").getAsString());
    }

    private static boolean hasGameOwnership(JsonObject ownership) {
        if (!ownership.has("items") || !ownership.get("items").isJsonArray()) return false;
        boolean hasProduct = false;
        boolean hasGame = false;
        JsonArray items = ownership.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            if (!item.has("name")) continue;
            String name = item.get("name").getAsString();
            if ("product_minecraft".equals(name)) hasProduct = true;
            if ("game_minecraft".equals(name)) hasGame = true;
        }
        return hasProduct && hasGame;
    }

    private static void startServer() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/", AutismMicrosoftLogin::handleRequest);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        } catch (IOException e) {
            stopServer();
            AutismClientMessaging.sendPrefixed("Failed to start Microsoft login server.");
        }
    }

    public static void stopServer() {
        if (server == null) return;
        server.stop(0);
        server = null;
        callback = null;
    }

    private static void handleRequest(HttpExchange request) throws IOException {
        if ("GET".equals(request.getRequestMethod())) {
            List<Tuple<String, String>> query = parseURL(request.getRequestURI().getRawQuery());
            boolean ok = false;
            for (Tuple<String, String> pair : query) {
                if ("code".equals(pair.getA())) {
                    handleCode(pair.getB());
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                writeText(request, "Cannot authenticate.");
                Consumer<String> current = callback;
                if (current != null) current.accept(null);
            } else {
                writeText(request, "You may now close this page.");
            }
        }
        stopServer();
    }

    private static void handleCode(String code) {
        JsonObject response = AutismHttp.postForm("https://login.live.com/oauth20_token.srf", "client_id=" + CLIENT_ID + "&code=" + code + "&grant_type=authorization_code&redirect_uri=http://127.0.0.1:" + PORT);
        Consumer<String> current = callback;
        if (current != null) current.accept(response == null || !response.has("refresh_token") ? null : response.get("refresh_token").getAsString());
    }

    private static void writeText(HttpExchange request, String text) throws IOException {
        byte[] responseBody = text.getBytes(StandardCharsets.UTF_8);
        request.sendResponseHeaders(200, responseBody.length);
        try (var output = request.getResponseBody()) {
            output.write(responseBody);
        }
    }

    private static List<Tuple<String, String>> parseURL(String string) {
        List<Tuple<String, String>> query = new ArrayList<>();
        if (string == null) return query;
        char[] buf = string.toCharArray();
        int i = 0;
        while (i < buf.length) {
            StringBuilder name = new StringBuilder();
            StringBuilder value = new StringBuilder();
            for (; i < buf.length; i++) {
                if (buf[i] == '&' || buf[i] == ';' || buf[i] == '=') break;
                name.append(buf[i]);
            }
            if (i < buf.length) {
                char ch = buf[i++];
                if (ch == '=') {
                    for (; i < buf.length; i++) {
                        if (buf[i] == '&' || buf[i] == ';') {
                            i++;
                            break;
                        }
                        value.append(buf[i]);
                    }
                }
            }
            if (!name.isEmpty()) query.add(new Tuple<>(urlDecode(name.toString()), urlDecode(value.toString())));
        }
        return query;
    }

    private static String urlDecode(String s) {
        if (s == null) return null;
        ByteBuffer bb = ByteBuffer.allocate(s.length());
        CharBuffer cb = CharBuffer.wrap(s);
        while (cb.hasRemaining()) {
            char c = cb.get();
            if (c == '%' && cb.remaining() >= 2) {
                char uc = cb.get();
                char lc = cb.get();
                int u = Character.digit(uc, 16);
                int l = Character.digit(lc, 16);
                if (u != -1 && l != -1) bb.put((byte) ((u << 4) + l));
                else {
                    bb.put((byte) '%');
                    bb.put((byte) uc);
                    bb.put((byte) lc);
                }
            } else if (c == '+') bb.put((byte) ' ');
            else bb.put((byte) c);
        }
        bb.flip();
        return StandardCharsets.UTF_8.decode(bb).toString();
    }

    public static final class LoginData {
        public final String mcToken;
        public final String newRefreshToken;
        public final String uuid;
        public final String username;

        public LoginData() {
            this(null, null, null, null);
        }

        public LoginData(String mcToken, String newRefreshToken, String uuid, String username) {
            this.mcToken = mcToken;
            this.newRefreshToken = newRefreshToken;
            this.uuid = uuid;
            this.username = username;
        }

        public boolean isGood() {
            return mcToken != null;
        }
    }
}
