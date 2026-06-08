package autismclient.gui.vanillaui.components;

import autismclient.gui.vanillaui.UiBounds;
import autismclient.gui.vanillaui.UiContext;
import autismclient.gui.vanillaui.UiRenderer;

import java.util.ArrayList;
import java.util.List;

public final class ToastStack {
    private static final long DEFAULT_LIFETIME_MS = 1800L;
    private static final float DEFAULT_ENTER_MS = 140.0f;
    private static final float DEFAULT_EXIT_MS = 220.0f;
    private static final int DEFAULT_MAX_VISIBLE = 4;
    private static final int DEFAULT_GAP = 4;
    private static final int DEFAULT_HEIGHT = 18;

    private final List<ToastEntry> toasts = new ArrayList<>();
    private final long lifetimeMs;
    private final float enterMs;
    private final float exitMs;
    private final int maxVisible;
    private final int gap;
    private final int height;

    public ToastStack() {
        this(DEFAULT_LIFETIME_MS, DEFAULT_ENTER_MS, DEFAULT_EXIT_MS, DEFAULT_MAX_VISIBLE, DEFAULT_GAP, DEFAULT_HEIGHT);
    }

    public ToastStack(long lifetimeMs, float enterMs, float exitMs, int maxVisible, int gap, int height) {
        this.lifetimeMs = Math.max(1L, lifetimeMs);
        this.enterMs = Math.max(1.0f, enterMs);
        this.exitMs = Math.max(1.0f, exitMs);
        this.maxVisible = Math.max(1, maxVisible);
        this.gap = Math.max(0, gap);
        this.height = Math.max(1, height);
    }

    public void show(String message, int accentColor) {
        if (message == null || message.isBlank()) return;
        long nowNanos = System.nanoTime();
        prune(nowNanos);
        if (toasts.size() >= maxVisible) toasts.remove(0);
        toasts.add(new ToastEntry(message, nowNanos, accentColor));
    }

    public boolean hasVisibleToasts() {
        prune(System.nanoTime());
        return !toasts.isEmpty();
    }

    public void clear() {
        toasts.clear();
    }

    public void render(UiContext context, int anchorX, int anchorY, int anchorWidth) {
        if (context == null || anchorWidth <= 0) return;
        long nowNanos = System.nanoTime();
        prune(nowNanos);
        if (toasts.isEmpty()) return;

        int maxToastWidth = Math.max(1, Math.min(anchorWidth, 260));
        int y = anchorY;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            ToastEntry toast = toasts.get(i);
            float ageMs = Math.max(0.0f, (nowNanos - toast.shownAtNanos()) / 1_000_000.0f);
            float alpha = Math.min(clamp01(ageMs / enterMs), clamp01((lifetimeMs - ageMs) / exitMs));
            if (alpha <= 0.001f) continue;

            int textWidth = context.text().width(toast.message());
            int toastWidth = Math.min(maxToastWidth, Math.max(Math.min(48, maxToastWidth), textWidth + 18));
            int drawX = anchorX + Math.max(0, (anchorWidth - toastWidth) / 2);
            UiBounds bounds = UiBounds.of(drawX, y, toastWidth, height);
            UiRenderer.frame(context.graphics(), bounds, UiRenderer.applyAlpha(0xD6121014, alpha), UiRenderer.applyAlpha(toast.accentColor(), alpha));
            context.text().drawCentered(context.graphics(), toast.message(), bounds, UiRenderer.applyAlpha(0xFFF4F4F4, alpha));
            y += height + gap;
        }
    }

    private void prune(long nowNanos) {
        toasts.removeIf(toast -> (nowNanos - toast.shownAtNanos()) / 1_000_000L >= lifetimeMs);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private record ToastEntry(String message, long shownAtNanos, int accentColor) {
    }
}
