package autismclient.util;

import autismclient.gui.vanillaui.direct.DirectLayout;
import autismclient.gui.vanillaui.components.CompactListRenderer;
import autismclient.gui.vanillaui.components.CompactOverlayButton;
import autismclient.gui.vanillaui.components.CompactOverlayControls;
import autismclient.gui.vanillaui.direct.DirectScrollViewport;
import autismclient.gui.vanillaui.components.UiSizing;
import autismclient.gui.vanillaui.components.UiText;
import autismclient.gui.vanillaui.components.CompactTheme;
import autismclient.gui.vanillaui.components.InspectorLayout;
import autismclient.gui.vanillaui.components.UiTone;
import autismclient.gui.vanillaui.TextWrapLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AutismPacketInspectOverlay extends AutismOverlayBase {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int CONTENT_RESERVE = 0;
    private static final int AUTO_WIDTH_STEP = 8;

    private final Font textRenderer;
    private final CompactTheme theme = new CompactTheme();

    private boolean visible;
    private boolean collapsed;
    private boolean dragging;
    private double dragOffsetX;
    private double dragOffsetY;

    private int panelX = 120;
    private int panelY = 50;
    private int panelWidth = 220;
    private int panelHeight = 173;
    private AutismPacketInspector.PacketInspection inspection;
    private AutismPacketLoggerOverlay.LogEntry sourceEntry;
    private List<WrappedInspectionLine> wrappedLines = Collections.emptyList();
    private boolean wrapDirty = true;
    private int wrappedWidth = -1;
    private DirectScrollViewport listViewport = null;
    private String currentScrollKey = "";
    private int pendingScrollOffset = 0;
    private static final int INSPECT_SCROLLBAR_WIDTH = InspectorLayout.SCROLLBAR_WIDTH;
    private static final int MAX_REMEMBERED_SCROLLS = 128;
    private static final Map<String, Integer> REMEMBERED_SCROLL_OFFSETS = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_REMEMBERED_SCROLLS;
        }
    };

    public AutismPacketInspectOverlay(Font textRenderer) {
        this.textRenderer = textRenderer;
        this.panelWidth = defaultPanelWidth();
        this.panelHeight = defaultPanelHeight();
    }

    public void open(AutismPacketInspector.PacketInspection inspection, AutismPacketLoggerOverlay.LogEntry sourceEntry, int anchorX, int anchorY) {
        if (inspection == null) return;
        AutismOverlayManager manager = AutismOverlayManager.get();
        manager.register(this);
        rememberScrollOffset();
        this.inspection = inspection;
        this.sourceEntry = sourceEntry;
        this.visible = true;
        this.collapsed = false;
        this.currentScrollKey = scrollKey(inspection, sourceEntry);
        this.pendingScrollOffset = REMEMBERED_SCROLL_OFFSETS.getOrDefault(currentScrollKey, 0);
        this.listViewport = null;
        this.wrapDirty = true;
        fitWindowToInspection(anchorX, anchorY);
        manager.bringToFront(this);
    }

    public void close() {
        rememberScrollOffset();
        visible = false;
        dragging = false;
        sourceEntry = null;
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (!visible) return;

        AutismWindowLayout bounds = getBounds();
        String title = inspection == null ? "Packet Inspect" : inspection.getTitle();
        renderWindowFrame(context, mouseX, mouseY, bounds, title, collapsed, dragging);
        boolean clipBody = beginWindowBodyClip(context, bounds, collapsed);
        if (!clipBody) {
            renderWindowInactiveOverlay(context, bounds, collapsed, dragging);
            return;
        }

        try {
            int listX = panelX + outerPad();
            int listY = panelY + HEADER_HEIGHT + bodyTopGap();
            int listWidth = panelWidth - (outerPad() * 2);
            int listHeight = Math.max(1, panelHeight - HEADER_HEIGHT - footerHeight() - bodyVerticalGap());
            int innerX = listX + innerPad();
            int innerY = listY + innerPad();
            int innerWidth = InspectorLayout.contentWidth(listWidth, innerPad());
            int innerHeight = listHeight - (innerPad() * 2);
            int lineH = lineHeight();

            boolean focused = AutismOverlayManager.get().isFocusedOverlay(this) || AutismOverlayManager.get().isTopOverlay(this);
            ensureWrappedLines(innerWidth);

            if (wrappedLines.isEmpty()) {
                CompactListRenderer.drawEmptyState(context, textRenderer, "No inspection data.", listX, listY, listWidth);
            } else {
                int contentHeight = wrappedLines.size() * lineH;

                if (listViewport == null || listViewport.getX() != listX || listViewport.getY() != listY
                    || listViewport.getWidth() != listWidth || listViewport.getHeight() != listHeight) {
                    int previousScrollOffset = listViewport != null ? listViewport.getScrollOffset() : pendingScrollOffset;
                    listViewport = new DirectScrollViewport(listX, listY, listWidth, listHeight, lineH, INSPECT_SCROLLBAR_WIDTH);
                    listViewport.setContentHeight(contentHeight);
                    listViewport.jumpTo(previousScrollOffset);
                    pendingScrollOffset = listViewport.getScrollOffset();
                }
                listViewport.setContentHeight(contentHeight);
                if (pendingScrollOffset > 0 && listViewport.getScrollOffset() == 0) {
                    listViewport.jumpTo(pendingScrollOffset);
                }

                listViewport.beginRender(context, theme.borderSoft(), theme.listFill());
                try {
                    listViewport.renderSimple(context, wrappedLines.size(), (idx, bnd) -> {
                        WrappedInspectionLine ln = wrappedLines.get(idx);
                        drawInspectionLine(context, ln, innerX, UiSizing.alignTextY(bnd.y, lineH, theme.fontHeight(UiTone.BODY), theme.bodyTextNudge()));
                    });
                } finally {
                    listViewport.endRender(context);
                }

                listViewport.renderScrollbar(context, mouseX, mouseY);
            }

            int footerY = panelY + panelHeight - footerHeight() + footerTopInset();
            UiText.draw(context, textRenderer, wrappedLines.size() + " lines", theme.fontFor(UiTone.MUTED), theme.color(UiTone.MUTED), panelX + outerPad() + 5, footerY + footerLabelInset(), false);
            for (FooterButton button : buildFooterButtons(footerY)) {
                CompactOverlayButton.Variant variant = switch (button.action()) {
                    case COPY -> CompactOverlayButton.Variant.GHOST;
                    case SEND -> CompactOverlayButton.Variant.SUCCESS;
                    case EDIT_PAYLOAD -> CompactOverlayButton.Variant.PRIMARY;
                    case ADD_PAYLOAD, WAIT, QUEUE -> CompactOverlayButton.Variant.SECONDARY;
                };
                CompactOverlayControls.action(context, textRenderer, button.x(), button.y(), button.width(), buttonHeight(), button.label(),
                    variant, true, mouseX, mouseY);
            }
        } finally {
            endWindowBodyClip(context, clipBody);
            renderWindowInactiveOverlay(context, bounds, collapsed, dragging);
        }
    }

    private void ensureWrappedLines(int innerWidth) {
        if (!wrapDirty && wrappedWidth == innerWidth) return;
        if (inspection == null) {
            wrappedLines = Collections.emptyList();
            wrapDirty = false;
            wrappedWidth = innerWidth;
            return;
        }

        List<WrappedInspectionLine> wrapped = new ArrayList<>();
        for (AutismPacketInspector.InspectionLine line : inspection.getLines()) {
            wrapLine(line, innerWidth, wrapped);
        }
        wrappedLines = wrapped;
        wrapDirty = false;
        wrappedWidth = innerWidth;
    }

    private void wrapLine(AutismPacketInspector.InspectionLine line, int maxWidth, List<WrappedInspectionLine> target) {
        String text = line.getText();
        if (text == null || text.isEmpty()) {
            target.add(WrappedInspectionLine.plain("", 0, line.getColor()));
            return;
        }

        if (isSectionLine(text)) {
            wrapPlainText(text, 0, line.getColor(), maxWidth, target);
            return;
        }

        int leadingSpaces = countLeadingSpaces(text);
        String indentComponent = " ".repeat(leadingSpaces);
        int indentWidth = styledWidth(indentComponent);
        int colonIndex = text.indexOf(':', leadingSpaces);
        if (colonIndex > leadingSpaces && colonIndex < text.length() - 1) {
            String label = text.substring(leadingSpaces, colonIndex + 1).trim();
            String value = text.substring(colonIndex + 1).stripLeading();
            String prefix = indentComponent + label + " ";
            int prefixWidth = styledWidth(prefix);
            int continuationOffset = Math.min(prefixWidth, indentWidth + continuationIndent());
            wrapKeyValue(prefix, value, line.getColor(), prefixWidth, continuationOffset, maxWidth, target);
            return;
        }

        wrapPlainText(text.substring(leadingSpaces), indentWidth, line.getColor(), maxWidth, target);
    }

    private void wrapKeyValue(String prefix, String value, int valueColor, int prefixWidth, int continuationOffset, int maxWidth,
                              List<WrappedInspectionLine> target) {
        int safeContinuationOffset = InspectorLayout.clampTextOffset(maxWidth, continuationOffset);
        if (prefixWidth >= maxWidth) {
            wrapPlainText(prefix.stripTrailing(), 0, AutismColors.packetGray(), maxWidth, target);
            wrapPlainText(value, safeContinuationOffset, valueColor, maxWidth, target);
            return;
        }
        if (value == null || value.isEmpty()) {
            target.add(new WrappedInspectionLine(prefix, AutismColors.packetGray(), "", valueColor, prefixWidth));
            return;
        }

        String remaining = value;
        boolean firstLine = true;
        while (!remaining.isEmpty()) {
            int offset = firstLine ? prefixWidth : safeContinuationOffset;
            int availableWidth = InspectorLayout.remainingTextWidth(maxWidth, offset);
            String current = remaining;
            int split = TextWrapLayout.nextLineEnd(current, 0, current.length(), availableWidth,
                (start, end) -> styledWidth(current.substring(start, end)));
            String part = remaining.substring(0, split).stripTrailing();
            target.add(new WrappedInspectionLine(firstLine ? prefix : null, AutismColors.packetGray(), part, valueColor, offset));
            remaining = remaining.substring(split).stripLeading();
            firstLine = false;
        }
    }

    private void wrapPlainText(String text, int offset, int color, int maxWidth, List<WrappedInspectionLine> target) {
        if (text == null) return;
        if (text.isEmpty()) {
            target.add(WrappedInspectionLine.plain("", offset, color));
            return;
        }

        int safeOffset = InspectorLayout.clampTextOffset(maxWidth, offset);
        String remaining = text;
        while (!remaining.isEmpty()) {
            int availableWidth = InspectorLayout.remainingTextWidth(maxWidth, safeOffset);
            String current = remaining;
            int split = TextWrapLayout.nextLineEnd(current, 0, current.length(), availableWidth,
                (start, end) -> styledWidth(current.substring(start, end)));
            String part = remaining.substring(0, split).stripTrailing();
            target.add(WrappedInspectionLine.plain(part, safeOffset, color));
            remaining = remaining.substring(split).stripLeading();
        }
    }

    private void drawInspectionLine(GuiGraphicsExtractor context, WrappedInspectionLine line, int x, int y) {
        if (line == null || line.valueText() == null) return;
        if (line.prefixText() != null && !line.prefixText().isEmpty()) {
            UiText.draw(context, textRenderer, line.prefixText(), theme.fontFor(UiTone.BODY), line.prefixColor(), x, y, false);
        }
        int valueX = x + line.valueOffset();
        UiText.draw(context, textRenderer, line.valueText(), theme.fontFor(UiTone.BODY), line.valueColor(), valueX, y, false);
    }

    private boolean isSectionLine(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    private int countLeadingSpaces(String text) {
        int count = 0;
        while (count < text.length() && text.charAt(count) == ' ') count++;
        return count;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;
        AutismWindowLayout bounds = getBounds();
        if (button == 0 && mouseY >= panelY && mouseY <= panelY + HEADER_HEIGHT && mouseX >= panelX && mouseX <= panelX + panelWidth) {
            if (isOverCloseButton(mouseX, mouseY, bounds)) {
                close();
                return true;
            }
            if (isOverCollapseButton(mouseX, mouseY, bounds)) {
                setCollapsed(!collapsed);
                return true;
            }
            dragging = true;
            dragOffsetX = mouseX - panelX;
            dragOffsetY = mouseY - panelY;
            return true;
        }
        if (collapsed) return true;

        int listY = panelY + HEADER_HEIGHT + bodyTopGap();
        int listHeight = Math.max(1, panelHeight - HEADER_HEIGHT - footerHeight() - bodyVerticalGap());
        int listX = panelX + outerPad();
        int listWidth = panelWidth - (outerPad() * 2);

        if (button == 0 && listViewport != null && listViewport.contains(mouseX, mouseY)) {
            if (listViewport.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        int footerY = panelY + panelHeight - footerHeight() + footerTopInset();
        if (button == 0) {
            for (FooterButton footerButton : buildFooterButtons(footerY)) {
                if (mouseX >= footerButton.x() && mouseX < footerButton.x() + footerButton.width()
                    && mouseY >= footerButton.y() && mouseY < footerButton.y() + buttonHeight()) {
                    handleFooterAction(footerButton.action());
                    return true;
                }
            }
        }

        return isMouseOver(mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            rememberScrollOffset();
            saveLayout();
            return true;
        }
        if (button == 0 && listViewport != null) {
            listViewport.mouseReleased();
            rememberScrollOffset();
            return true;
        }
        dragging = false;
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (listViewport != null) {
            listViewport.mouseDragged(mouseX, mouseY);
            rememberScrollOffset();
        }
        if (!visible || button != 0 || !dragging) return false;
        setBounds(new AutismWindowLayout((int) Math.round(mouseX - dragOffsetX), (int) Math.round(mouseY - dragOffsetY),
            panelWidth, panelHeight, visible, collapsed));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!visible || collapsed) return false;
        int listY = panelY + HEADER_HEIGHT + bodyTopGap();
        int listHeight = Math.max(1, panelHeight - HEADER_HEIGHT - footerHeight() - bodyVerticalGap());
        int listX = panelX + outerPad();
        int listWidth = panelWidth - (outerPad() * 2);
        if (mouseX < listX || mouseX > listX + listWidth || mouseY < listY || mouseY > listY + listHeight) return false;

        if (listViewport != null) {
            boolean handled = listViewport.mouseScrolled(mouseX, mouseY, amount);
            rememberScrollOffset();
            return handled;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return false;
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) close();
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (!visible) return false;
        int height = collapsed ? HEADER_HEIGHT : panelHeight;
        return mouseX >= panelX && mouseX <= panelX + panelWidth && mouseY >= panelY && mouseY <= panelY + height;
    }

    @Override
    public boolean isOverDragBar(double mouseX, double mouseY) {
        if (!visible) return false;
        return mouseX >= panelX && mouseX <= panelX + panelWidth
            && mouseY >= panelY && mouseY <= panelY + HEADER_HEIGHT
            && !isOverWindowControl(mouseX, mouseY, getBounds());
    }

    @Override
    public int getZLevel() {
        return 12;
    }

    @Override
    public boolean isCollapsed() {
        return collapsed;
    }

    @Override
    public void setCollapsed(boolean collapsed) {
        if (this.collapsed == collapsed) return;
        this.collapsed = collapsed;
        dragging = false;
        if (collapsed) clearHiddenInteractionState();
        saveLayout();
    }

    @Override
    public boolean usesSharedHeaderClickCollapse() {
        return true;
    }

    @Override
    public AutismWindowLayout getBounds() {
        return new AutismWindowLayout(panelX, panelY, panelWidth, panelHeight, visible, collapsed);
    }

    @Override
    public void setBounds(AutismWindowLayout bounds) {
        if (bounds == null) return;
        AutismWindowLayout clamped = clampToScreen(this, bounds);
        boolean sizeChanged = panelWidth != clamped.width || panelHeight != clamped.height;
        panelX = clamped.x;
        panelY = clamped.y;
        panelWidth = clamped.width;
        panelHeight = clamped.height;
        visible = clamped.visible;
        collapsed = clamped.collapsed;
        if (sizeChanged) wrapDirty = true;
    }

    @Override
    public int getMinWidth() {
        return minimumWidth();
    }

    @Override
    public int getMinHeight() {
        return minimumHeight();
    }

    private void handleFooterAction(FooterAction action) {
        if (action == null) return;
        switch (action) {
            case COPY -> {
                if (inspection != null) {
                    MC.keyboardHandler.setClipboard(inspection.getCopyText());
                    AutismNotifications.copied("Copied packet inspection.");
                }
            }
            case QUEUE -> AutismPacketEntryActions.queue(sourceEntry);
            case WAIT -> AutismPacketEntryActions.addWaitActionToVisibleMacro(sourceEntry);
            case SEND -> directSendEntry(sourceEntry);
            case EDIT_PAYLOAD -> AutismPacketEntryActions.openPayloadEditor(sourceEntry);
            case ADD_PAYLOAD -> AutismPacketEntryActions.addPayloadActionToVisibleMacro(sourceEntry);
        }
    }

    private void directSendEntry(AutismPacketLoggerOverlay.LogEntry entry) {
        if (entry == null || entry.packetRef == null || !"C2S".equalsIgnoreCase(entry.direction)) {
            AutismClientMessaging.sendPrefixed("\u00a7cOnly C2S packets can be sent.");
            return;
        }
        AutismPacketEntryActions.directSend(entry);
    }

    private void fitWindowToInspection(int anchorX, int anchorY) {
        int screenWidth = MC.getWindow() != null ? AutismUiScale.getVirtualScreenWidth() : 600;
        int screenHeight = MC.getWindow() != null ? AutismUiScale.getVirtualScreenHeight() : 400;
        int lineHeight = lineHeight();
        int lineCount = inspection == null ? 0 : inspection.getLines().size();
        int footerInfoWidth = styledWidth(lineCount + " lines") + outerPad() + 15;
        int footerButtonsWidth = computeFooterButtonsWidth();
        int titleWidth = styledWidth(inspection == null ? "Packet Inspect" : inspection.getTitle()) + titleWidthPadding();

        int minWidth = Math.max(minimumWidth(), Math.max(titleWidth, footerInfoWidth + footerButtonsWidth + footerContentPadding()));
        int maxWidth = Math.max(minWidth, Math.min(maxAutoWidth(), screenWidth - 12));
        int maxHeight = Math.max(minimumHeight(), Math.min(maxAutoHeight(), screenHeight - 12));

        int desiredWidth = chooseBestAutoWidth(minWidth, maxWidth);
        FitMetrics fit = measureFitForPanelWidth(desiredWidth);
        int desiredVisibleLines = Math.max(3, Math.min(fit.lineCount(), maxVisibleLines()));
        int desiredListHeight = Math.max(listMinimumHeight(), desiredVisibleLines * lineHeight + (innerPad() * 2) + 2);
        int desiredHeight = HEADER_HEIGHT + bodyTopGap() + desiredListHeight + footerHeight() + footerBottomGap();
        desiredHeight = Math.min(maxHeight, Math.max(minimumHeight(), desiredHeight));

        setBounds(new AutismWindowLayout(anchorX, anchorY, desiredWidth, desiredHeight, true, false));
    }

    private void rememberScrollOffset() {
        if (currentScrollKey == null || currentScrollKey.isEmpty()) return;
        int offset = listViewport != null ? listViewport.getScrollOffset() : pendingScrollOffset;
        pendingScrollOffset = Math.max(0, offset);
        REMEMBERED_SCROLL_OFFSETS.put(currentScrollKey, pendingScrollOffset);
    }

    private String scrollKey(AutismPacketInspector.PacketInspection inspection, AutismPacketLoggerOverlay.LogEntry entry) {
        if (entry != null) {
            String className = entry.packetClass == null ? "" : entry.packetClass.getName();
            return entry.direction + "|" + entry.shortName + "|" + className + "|" + entry.timestampMs + "|" + entry.gameTick;
        }
        return inspection == null ? "" : inspection.getTitle();
    }

    private int chooseBestAutoWidth(int minPanelWidth, int maxPanelWidth) {
        int rawLineCount = inspection == null ? 0 : inspection.getLines().size();
        int bestWidth = minPanelWidth;
        long bestScore = Long.MAX_VALUE;

        for (int width = minPanelWidth; width <= maxPanelWidth; width += AUTO_WIDTH_STEP) {
            long score = scoreAutoWidth(width, rawLineCount);
            if (score < bestScore || (score == bestScore && width < bestWidth)) {
                bestScore = score;
                bestWidth = width;
            }
        }

        if (bestWidth != maxPanelWidth) {
            long score = scoreAutoWidth(maxPanelWidth, rawLineCount);
            if (score < bestScore || (score == bestScore && maxPanelWidth < bestWidth)) {
                bestWidth = maxPanelWidth;
            }
        }
        return bestWidth;
    }

    private long scoreAutoWidth(int panelWidthCandidate, int rawLineCount) {
        FitMetrics fit = measureFitForPanelWidth(panelWidthCandidate);
        int extraLines = Math.max(0, fit.lineCount() - rawLineCount);
        int wastedPixels = Math.max(0, fit.contentWidth() - fit.longestWrappedLineWidth());
        return panelWidthCandidate / 6L + (long) extraLines * 52L + (long) wastedPixels * 4L;
    }

    private FitMetrics measureFitForPanelWidth(int panelWidthCandidate) {
        int listWidth = Math.max(32, panelWidthCandidate - (outerPad() * 2));
        int contentWidth = InspectorLayout.contentWidth(listWidth, innerPad());
        List<WrappedInspectionLine> measured = new ArrayList<>();
        if (inspection != null) {
            for (AutismPacketInspector.InspectionLine line : inspection.getLines()) {
                wrapLine(line, contentWidth, measured);
            }
        }

        int longestWrappedLineWidth = 0;
        for (WrappedInspectionLine line : measured) {
            longestWrappedLineWidth = Math.max(longestWrappedLineWidth, line.renderedWidth(textRenderer));
        }
        return new FitMetrics(contentWidth, measured.size(), longestWrappedLineWidth);
    }

    private List<FooterButton> buildFooterButtons(int footerY) {
        List<FooterButton> buttons = new ArrayList<>();
        int cursorX = panelX + panelWidth - outerPad();

        for (FooterAction action : getVisibleFooterActions()) {
            cursorX = addFooterButton(buttons, cursorX, footerY, action);
        }
        return buttons;
    }

    private List<FooterAction> getVisibleFooterActions() {
        List<FooterAction> actions = new ArrayList<>();
        actions.add(FooterAction.COPY);
        if (AutismPacketEntryActions.canQueue(sourceEntry)) {
            actions.add(FooterAction.QUEUE);
        }
        if (AutismPacketEntryActions.canEditPayload(sourceEntry)) {
            actions.add(FooterAction.EDIT_PAYLOAD);
        }
        if (AutismPacketEntryActions.canAddPayloadAction(sourceEntry)) {
            actions.add(FooterAction.ADD_PAYLOAD);
        }
        if (AutismPacketEntryActions.canAddSendAction(sourceEntry)) {
            actions.add(FooterAction.SEND);
        }
        if (AutismPacketEntryActions.canAddWaitAction(sourceEntry)) {
            actions.add(FooterAction.WAIT);
        }
        return actions;
    }

    private int computeFooterButtonsWidth() {
        int width = 0;
        for (FooterAction action : getVisibleFooterActions()) {
            if (width > 0) width += buttonGap();
            width += footerButtonWidth(action);
        }
        return width;
    }

    private int addFooterButton(List<FooterButton> buttons, int cursorX, int footerY, FooterAction action) {
        int width = footerButtonWidth(action);
        int x = cursorX - width;
        buttons.add(new FooterButton(action, x, footerY, width));
        return x - buttonGap();
    }

    private int footerButtonWidth(FooterAction action) {
        return DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, action.label, 5, 36, 68);
    }

    private int styledWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        return UiText.width(textRenderer, text, theme.fontFor(UiTone.BODY), AutismColors.packetWhite());
    }

        private int minimumWidth() {
        return 176;
    }

    private int minimumHeight() {
        return 132;
    }

    private int defaultPanelWidth() {
        return 220;
    }

    private int defaultPanelHeight() {
        return 173;
    }

    private int footerHeight() {
        return 26;
    }

    private int outerPad() {
        return 5;
    }

    private int innerPad() {
        return 3;
    }

    private int buttonGap() {
        return 4;
    }

    private int buttonHeight() {
        return 16;
    }

    private int continuationIndent() {
        return 32;
    }

    private int maxAutoWidth() {
        return 392;
    }

    private int maxAutoHeight() {
        return 300;
    }

    private int maxVisibleLines() {
        return 13;
    }

    private int lineHeight() {
        return theme.lineHeight(UiTone.BODY, 2);
    }

    private int bodyTopGap() {
        return 4;
    }

    private int bodyVerticalGap() {
        return bodyTopGap() + footerBottomGap();
    }

    private int footerTopInset() {
        return 3;
    }

    private int footerBottomGap() {
        return 3;
    }

    private int footerLabelInset() {
        return 5;
    }

    private int titleWidthPadding() {
        return 54;
    }

    private int footerContentPadding() {
        return 16;
    }

    private int listMinimumHeight() {
        return 32;
    }

    private enum FooterAction {
        WAIT("Wait"),
        SEND("Send"),
        QUEUE("Queue"),
        EDIT_PAYLOAD("Edit Payload"),
        ADD_PAYLOAD("+Payload"),
        COPY("Copy");

        private final String label;

        FooterAction(String label) {
            this.label = label;
        }
    }

    private record FooterButton(FooterAction action, int x, int y, int width) {
        private String label() {
            return action.label;
        }
    }

    private record FitMetrics(int contentWidth, int lineCount, int longestWrappedLineWidth) {
    }

    private record WrappedInspectionLine(String prefixText, int prefixColor, String valueText, int valueColor, int valueOffset) {
        static WrappedInspectionLine plain(String text, int offset, int color) {
            return new WrappedInspectionLine(null, color, text, color, offset);
        }

        int renderedWidth(Font textRenderer) {
            int width = valueOffset + UiText.width(textRenderer, valueText, new CompactTheme().fontFor(UiTone.BODY), AutismColors.packetWhite());
            if (prefixText != null && !prefixText.isEmpty()) {
                width = Math.max(width, UiText.width(textRenderer, prefixText, new CompactTheme().fontFor(UiTone.BODY), AutismColors.packetWhite()));
            }
            return width;
        }
    }
}
