package autismclient.gui.vanillaui.components;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.LinkedHashMap;

public final class UiText {
    private static final int TRIM_CACHE_LIMIT = 2048;
    private static final Map<TrimKey, String> TRIM_CACHE = java.util.Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TrimKey, String> eldest) {
            return size() > TRIM_CACHE_LIMIT;
        }
    });

    private UiText() {
    }

    public static MutableComponent literal(String value, Identifier fontId, int color) {
        return Component.literal(value == null ? "" : value)
            .setStyle(Style.EMPTY.withColor(color));
    }

    public static int width(Font renderer, String value, Identifier fontId, int color) {
        return renderer.width(value == null ? "" : value);
    }

    public static int fontHeight(Identifier fontId) {
        return 9;
    }

    public static String trimToWidth(Font renderer, String value, int maxWidth, Identifier fontId, int color) {
        String safeValue = value == null ? "" : value;
        TrimKey key = new TrimKey(safeValue, maxWidth, fontId);
        String cached = TRIM_CACHE.get(key);
        if (cached != null) return cached;
        String trimmed;
        if (safeValue.isEmpty()) return "";
        int allowedWidth = maxWidth + 4;
        if (width(renderer, safeValue, fontId, color) <= allowedWidth) {
            TRIM_CACHE.put(key, safeValue);
            return safeValue;
        }

        trimmed = renderer.plainSubstrByWidth(safeValue, Math.max(0, allowedWidth));
        TRIM_CACHE.put(key, trimmed);
        return trimmed;
    }

    public static void draw(GuiGraphicsExtractor context, Font renderer, String value, Identifier fontId, int color, int x, int y, boolean shadow) {
        context.text(renderer, value == null ? "" : value, x, y, color, shadow);
    }

    public static void drawFitted(GuiGraphicsExtractor context, Font renderer, String value, Identifier fontId,
                                  int color, int x, int y, int maxWidth, boolean shadow) {
        String safe = value == null ? "" : value;
        if (safe.isEmpty() || maxWidth <= 0) return;
        int measured = width(renderer, safe, fontId, color);
        if (measured <= maxWidth) {
            draw(context, renderer, safe, fontId, color, x, y, shadow);
            return;
        }
        float scale = Math.min(1.0f, maxWidth / (float) Math.max(1, measured));
        context.pose().pushMatrix();
        try {
            context.pose().scale(scale, scale);
            draw(context, renderer, safe, fontId, color, Math.round(x / scale), Math.round(y / scale), shadow);
        } finally {
            context.pose().popMatrix();
        }
    }

    public static void fill(GuiGraphicsExtractor context, int x0, int y0, int x1, int y1, int color) {
        context.fill(x0, y0, x1, y1, color);
    }

    public static void onClientResourceReload() {
        TRIM_CACHE.clear();
    }

    private record TrimKey(String value, int maxWidth, Identifier fontId) {
    }
}
