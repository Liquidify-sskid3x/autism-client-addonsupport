package autismclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class AutismUiScale {
    public static final int FIXED_GUI_SCALE = 2;
    private static final double EPSILON = 1.0E-6;
    private static int overlayScaleDepth = 0;

    private AutismUiScale() {}

    public static double toVirtual(double value) {
        return value / getOverlayDrawScale();
    }

    public static int toVirtualInt(double value) {
        double virtual = toVirtual(value);
        return virtual < 0 ? (int) Math.floor(virtual) : (int) Math.round(virtual);
    }

    public static int getVirtualScreenWidth() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return 0;
        int width = (int) (mc.getWindow().getWidth() / (double) FIXED_GUI_SCALE);
        return mc.getWindow().getWidth() / (double) FIXED_GUI_SCALE > width ? width + 1 : width;
    }

    public static int getVirtualScreenHeight() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return 0;
        int height = (int) (mc.getWindow().getHeight() / (double) FIXED_GUI_SCALE);
        return mc.getWindow().getHeight() / (double) FIXED_GUI_SCALE > height ? height + 1 : height;
    }

    public static float getOverlayDrawScale() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || mc.getWindow().getGuiScale() <= 0) return 1.0f;
        return (float) FIXED_GUI_SCALE / (float) mc.getWindow().getGuiScale();
    }

    public static boolean isOverlayScaleActive() {
        return overlayScaleDepth > 0;
    }

    public static boolean isFixedOverlayScaleActive() {
        return isOverlayScaleActive() && Math.abs(getOverlayDrawScale() - 1.0f) > 0.001f;
    }

    public static int virtualToFramebufferX(int x) {
        return (int) Math.floor(x * (double) FIXED_GUI_SCALE);
    }

    public static int virtualToFramebufferY(int y) {
        return (int) Math.floor(y * (double) FIXED_GUI_SCALE);
    }

    public static int virtualToFramebufferSize(int size) {
        return Math.max(0, (int) Math.ceil(size * (double) FIXED_GUI_SCALE));
    }

    public static void pushOverlayScale(GuiGraphicsExtractor context) {
        if (context == null) return;
        context.pose().pushMatrix();
        if (overlayScaleDepth == 0) {
            float scale = getOverlayDrawScale();
            context.pose().scale(scale, scale);
        }
        overlayScaleDepth++;
    }

    public static void popOverlayScale(GuiGraphicsExtractor context) {
        if (context == null) return;
        if (overlayScaleDepth > 0) overlayScaleDepth--;
        context.pose().popMatrix();
    }

    public static void enableOverlayScissor(GuiGraphicsExtractor context, int x1, int y1, int x2, int y2) {
        if (context == null) return;
        if (x2 <= x1 || y2 <= y1) {
            context.enableScissor(x1, y1, x1, y1);
            return;
        }

        int expandRight = overlayScissorExpansionForLength(x2 - x1);
        int expandBottom = overlayScissorExpansionForLength(y2 - y1);
        int screenW = Math.max(0, getVirtualScreenWidth());
        int screenH = Math.max(0, getVirtualScreenHeight());
        int left = clamp(x1, 0, Math.max(0, screenW));
        int top = clamp(y1, 0, Math.max(0, screenH));
        int right = clamp(x2 + expandRight, left, Math.max(left, screenW + expandRight));
        int bottom = clamp(y2 + expandBottom, top, Math.max(top, screenH + expandBottom));
        context.enableScissor(left, top, right, bottom);
    }

    private static int overlayScissorExpansionForLength(int virtualLength) {
        if (!isOverlayScaleActive() || virtualLength <= 0) return 0;
        double scale = getOverlayDrawScale();
        if (scale <= 0.0 || scale >= 1.0) return 0;
        double scaled = virtualLength * scale;
        double nearest = Math.rint(scaled);
        if (Math.abs(scaled - nearest) < EPSILON) return 0;
        double target = Math.ceil(scaled - EPSILON);
        double missing = Math.max(0.0, target - scaled);
        if (missing <= EPSILON) return 1;
        return Math.max(1, (int) Math.ceil((missing / scale) - EPSILON));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
