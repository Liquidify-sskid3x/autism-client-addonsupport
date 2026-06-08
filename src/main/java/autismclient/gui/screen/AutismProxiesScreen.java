package autismclient.gui.screen;

import autismclient.gui.vanillaui.components.CompactOverlayButton;
import autismclient.gui.vanillaui.direct.DirectLayout;
import autismclient.gui.vanillaui.components.CompactScrollbar;
import autismclient.gui.vanillaui.components.SectionPanel;
import autismclient.gui.vanillaui.components.ScrollState;
import autismclient.gui.vanillaui.components.UiText;
import autismclient.gui.vanillaui.components.CompactTheme;
import autismclient.gui.vanillaui.components.UiTone;
import autismclient.gui.vanillaui.UiContexts;
import autismclient.gui.vanillaui.UiBounds;
import autismclient.gui.vanillaui.UiRenderer;
import autismclient.util.AutismNotifications;
import autismclient.util.AutismProxy;
import autismclient.util.AutismProxyManager;
import autismclient.util.AutismProxyType;
import autismclient.util.AutismUiScale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AutismProxiesScreen extends Screen {
    private static final CompactTheme THEME = new CompactTheme();
    private static final int BG = 0xFF0E0E10;
    private static final int PANEL_BG = 0xE818181B;
    private static final int PANEL_BG_SOFT = 0xB8141417;
    private static final int BORDER = 0xFF332428;
    private static final int BORDER_ACTIVE = 0xFF35D873;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFF9A9A9A;
    private static final int SUCCESS = 0xFF35D873;
    private static final int ERROR = 0xFFFF5B5B;
    private static final int WARN = 0xFFFFC857;
    private static final int DEFAULT_COLOR = 0xFF8EA0FF;
    private static final int PANEL_WIDTH = 530;
    private static final int PANEL_MARGIN = 12;
    private static final int ROW_HEIGHT = 24;
    private static final int TOP_PANEL_Y = 20;
    private static final int TOP_PANEL_HEIGHT = 156;
    private static final int LIST_TOP = 178;
    private static final int LIST_HEADER_HEIGHT = 22;
    private static final int LIST_BOTTOM_MARGIN = 12;
    private static final int LIST_SCROLLBAR_WIDTH = 4;
    private static final int LIST_SCROLLBAR_GUTTER = 12;

    private final Screen parent;
    private final List<CompactOverlayButton> buttons = new ArrayList<>();
    private final List<ProxyRow> proxyRows = new ArrayList<>();
    private final ScrollState proxyListScroll = new ScrollState();
    private EditBox nameField;
    private EditBox addressField;
    private EditBox portField;
    private EditBox usernameField;
    private EditBox passwordField;
    private EditBox searchField;
    private AutismProxyType type = AutismProxyType.Socks5;
    private ProxyFilter filter = ProxyFilter.ALL;
    private AutismProxy selectedProxy;
    private String searchQuery = "";
    private int proxyListScrollOffset;
    private boolean proxyScrollbarDragging;
    private int proxyScrollbarGrabOffset;

    public AutismProxiesScreen(Screen parent) {
        super(Component.literal("Proxies"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int formX = panelX() + 10;
        int inputX = formX + 8;
        int inputW = Math.max(1, panelWidth() - 36);
        int gap = 8;
        int portW = Math.max(42, Math.min(72, inputW / 5));
        int remaining = Math.max(2, inputW - portW - gap * 2);
        int nameW = Math.max(1, remaining * 2 / 5);
        int addressW = Math.max(1, remaining - nameW);
        int halfW = Math.max(1, (inputW - gap) / 2);
        this.nameField = addField(inputX, 48, nameW, "Name");
        this.addressField = addField(inputX + nameW + gap, 48, addressW, "Address");
        this.portField = addField(inputX + nameW + gap + addressW + gap, 48, portW, "Port");
        this.usernameField = addField(inputX, 72, halfW, "Username");
        this.passwordField = addField(inputX + halfW + gap, 72, Math.max(1, inputW - halfW - gap), "Password");
        this.searchField = addField(inputX, 122, inputW, "Search proxies");
        this.searchField.setHint(Component.literal("Search name/address..."));
        this.searchField.setResponder(value -> {
            searchQuery = safeTrim(value);
            proxyListScrollOffset = 0;
            proxyListScroll.jumpTo(0, 0);
            rebuildButtons();
        });
        setFormFieldsVisible(!compactLayout());
        rebuildButtons();
    }

    private EditBox addField(int x, int y, int w, String hint) {
        EditBox field = new EditBox(this.font, x, y, w, 18, Component.literal(hint));
        field.setHint(Component.literal(hint));
        field.setMaxLength(256);
        this.addRenderableWidget(field);
        return field;
    }

    private void rebuildButtons() {
        buttons.clear();
        proxyRows.clear();
        int formX = panelX() + 10;

        buttons.add(CompactOverlayButton.create(10, 10, 76, 18, Component.literal("Back"), b -> this.minecraft.setScreen(parent)).setVariant(CompactOverlayButton.Variant.SECONDARY));
        if (compactLayout()) return;
        int actionX = formX + 8;
        int actionGap = 6;
        int usableW = Math.max(1, panelWidth() - 36);
        int[] actionWidths = scaledWidths(usableW, actionGap, 112, 64, 64, 72, 72, 64, 64);
        buttons.add(CompactOverlayButton.create(actionX, 96, actionWidths[0], 18, Component.literal("Type: " + type), b -> {
            type = type == AutismProxyType.Socks5 ? AutismProxyType.Socks4 : AutismProxyType.Socks5;
            rebuildButtons();
        }).setVariant(CompactOverlayButton.Variant.SECONDARY));
        actionX += actionWidths[0] + actionGap;
        buttons.add(CompactOverlayButton.create(actionX, 96, actionWidths[1], 18, Component.literal(selectedProxy == null ? "Add" : "Update"), b -> {
            if (selectedProxy == null) addProxy();
            else updateProxy();
        }).setVariant(CompactOverlayButton.Variant.PRIMARY));
        actionX += actionWidths[1] + actionGap;
        buttons.add(CompactOverlayButton.create(actionX, 96, actionWidths[2], 18, Component.literal(selectedProxy == null ? "Clear" : "Cancel"), b -> clearProxySelection()).setVariant(selectedProxy == null ? CompactOverlayButton.Variant.SECONDARY : CompactOverlayButton.Variant.DANGER));
        actionX += actionWidths[2] + actionGap;
        buttons.add(CompactOverlayButton.create(actionX, 96, actionWidths[3], 18, Component.literal(AutismProxyManager.get().isRefreshing() ? "Refreshing" : "Refresh"), b -> refreshProxies()).setVariant(CompactOverlayButton.Variant.SECONDARY));
        actionX += actionWidths[3] + actionGap;
        buttons.add(CompactOverlayButton.create(actionX, 96, actionWidths[4], 18, Component.literal("Cleanup"), b -> cleanupProxies()).setVariant(CompactOverlayButton.Variant.SECONDARY));
        actionX += actionWidths[4] + actionGap;
        buttons.add(CompactOverlayButton.create(actionX, 96, actionWidths[5], 18, Component.literal("Import"), b -> importProxies()).setVariant(CompactOverlayButton.Variant.SECONDARY));
        actionX += actionWidths[5] + actionGap;
        buttons.add(CompactOverlayButton.create(actionX, 96, actionWidths[6], 18, Component.literal("Config"), b -> this.minecraft.setScreen(new AutismProxyConfigScreen(this))).setVariant(CompactOverlayButton.Variant.SECONDARY));

        int filterX = formX + 8;
        int filterGap = 6;
        int[] filterWidths = scaledWidths(usableW, filterGap, 78, 78, 78, 94, 86);
        addFilterButton(filterX, 148, filterWidths[0], ProxyFilter.ALL, "All");
        filterX += filterWidths[0] + filterGap;
        addFilterButton(filterX, 148, filterWidths[1], ProxyFilter.ALIVE, "Alive");
        filterX += filterWidths[1] + filterGap;
        addFilterButton(filterX, 148, filterWidths[2], ProxyFilter.DEAD, "Dead");
        filterX += filterWidths[2] + filterGap;
        addFilterButton(filterX, 148, filterWidths[3], ProxyFilter.UNCHECKED, "Unchecked");
        filterX += filterWidths[3] + filterGap;
        addFilterButton(filterX, 148, filterWidths[4], ProxyFilter.ENABLED, "Enabled");

        List<AutismProxy> proxies = filteredProxies();
        int maxScroll = proxyMaxScroll(proxies.size());
        proxyListScrollOffset = quantizeScrollOffset(proxyListScrollOffset, ROW_HEIGHT, maxScroll);
        proxyListScroll.jumpTo(proxyListScrollOffset, maxScroll);
        int firstVisible = proxyListScrollOffset / ROW_HEIGHT;
        int rowY = proxyRowsTop() - (proxyListScrollOffset % ROW_HEIGHT);
        for (int i = firstVisible; i < proxies.size() && rowY + ROW_HEIGHT - 3 <= proxyRowsBottom(); i++) {
            AutismProxy proxy = proxies.get(i);
            if (rowY + ROW_HEIGHT - 3 <= proxyRowsTop()) {
                rowY += ROW_HEIGHT;
                continue;
            }
            CompactOverlayButton toggle = CompactOverlayButton.create(proxyRowX() + 8, rowY + 4, 44, 16, Component.literal(proxy.enabled ? "ON" : "OFF"), b -> toggleProxy(proxy));
            toggle.setVariant(proxy.enabled ? CompactOverlayButton.Variant.SUCCESS : CompactOverlayButton.Variant.DANGER)
                .setToggleState(proxy.enabled)
                .setAnimationKey("proxy-enabled:" + proxy.address + ':' + proxy.port);
            CompactOverlayButton check = CompactOverlayButton.create(proxyRowRight() - 122, rowY + 4, 54, 16, Component.literal(proxy.status == AutismProxy.Status.CHECKING ? "..." : "Check"), b -> checkProxy(proxy));
            check.setVariant(CompactOverlayButton.Variant.PRIMARY);
            check.active = proxy.status != AutismProxy.Status.CHECKING;
            CompactOverlayButton delete = CompactOverlayButton.create(proxyRowRight() - 62, rowY + 4, 54, 16, Component.literal("Delete"), b -> deleteProxy(proxy));
            delete.setVariant(CompactOverlayButton.Variant.DANGER);
            proxyRows.add(new ProxyRow(proxy, rowY, toggle, check, delete));
            rowY += ROW_HEIGHT;
        }
    }

    private void addFilterButton(int x, int y, int width, ProxyFilter value, String label) {
        CompactOverlayButton button = CompactOverlayButton.create(x, y, width, 18, Component.literal(label), b -> {
            filter = value;
            proxyListScrollOffset = 0;
            proxyListScroll.jumpTo(0, 0);
            rebuildButtons();
        });
        button.setVariant(filter == value ? CompactOverlayButton.Variant.FILTER_ON : CompactOverlayButton.Variant.FILTER_OFF);
        buttons.add(button);
    }

    @Override
    public void tick() {
        super.tick();
        proxyListScroll.tick(1.0F, 1);
    }

    private void addProxy() {
        AutismProxy proxy = proxyFromFields();
        if (proxy == null) return;
        if (AutismProxyManager.get().add(proxy)) {
            selectedProxy = null;
            clearProxyFields();
            showProxyToast("Added proxy " + proxy.address + ":" + proxy.port + ".", SUCCESS);
            checkProxy(proxy);
        } else {
            showProxyToast("Proxy already exists or is invalid.", ERROR);
        }
        rebuildButtons();
    }

    private void updateProxy() {
        if (selectedProxy == null) return;
        AutismProxy updated = proxyFromFields();
        if (updated == null) return;
        if (AutismProxyManager.get().update(selectedProxy, updated)) {
            showProxyToast("Updated " + selectedProxy.displayName() + ".", SUCCESS);
            clearProxySelection();
        } else {
            showProxyToast("Proxy already exists or is invalid.", ERROR);
            rebuildButtons();
        }
    }

    private void toggleProxy(AutismProxy proxy) {
        if (proxy == null) return;
        AutismProxyManager.get().setEnabled(proxy, !proxy.enabled);
        showProxyToast(proxy.enabled ? "Enabled " + proxy.displayName() + "." : "Disabled proxy.", proxy.enabled ? SUCCESS : MUTED);
        rebuildButtons();
    }

    private void deleteProxy(AutismProxy proxy) {
        if (proxy == null) return;
        AutismProxyManager.get().remove(proxy);
        if (proxy.equals(selectedProxy)) selectedProxy = null;
        showProxyToast("Deleted " + proxy.displayName() + ".", MUTED);
        rebuildButtons();
    }

    private void checkProxy(AutismProxy proxy) {
        if (proxy == null || proxy.status == AutismProxy.Status.CHECKING) return;
        rebuildButtons();
        Thread thread = new Thread(() -> {
            proxy.checkStatus(AutismProxyManager.get().getTimeoutMs());
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    showProxyToast(proxy.displayName() + " is " + statusText(proxy) + ".", proxy.status.color());
                    rebuildButtons();
                });
            }
        }, "Autism-Proxy-Check");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshProxies() {
        AutismProxyManager manager = AutismProxyManager.get();
        if (manager.all().isEmpty()) {
            showProxyToast("No proxies to refresh.", WARN);
            return;
        }
        if (manager.isRefreshing()) {
            showProxyToast("Proxy refresh is already running.", WARN);
            return;
        }
        manager.checkProxies(true);
        showProxyToast("Refreshing proxies...", DEFAULT_COLOR);
        rebuildButtons();
        Thread watcher = new Thread(() -> {
            while (manager.isRefreshing()) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    showProxyToast("Proxy refresh finished.", SUCCESS);
                    rebuildButtons();
                });
            }
        }, "Autism-Proxy-Refresh-Watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void cleanupProxies() {
        AutismProxyManager manager = AutismProxyManager.get();
        int before = manager.all().size();
        manager.clean();
        int removed = Math.max(0, before - manager.all().size());
        showProxyToast(removed == 0 ? "Cleanup finished. Nothing removed." : "Cleanup removed " + removed + " proxies.", removed == 0 ? MUTED : SUCCESS);
        rebuildButtons();
    }

    private void importProxies() {
        PointerBuffer filters = BufferUtils.createPointerBuffer(1);
        ByteBuffer txtFilter = MemoryUtil.memASCII("*.txt");
        filters.put(txtFilter);
        filters.rewind();
        String selectedFile = TinyFileDialogs.tinyfd_openFileDialog("Import Proxies", null, filters, null, false);
        if (selectedFile != null) {
            File file = new File(selectedFile);
            int added = AutismProxyManager.get().importFromFile(file);
            showProxyToast("Imported " + added + " proxies.", added > 0 ? SUCCESS : WARN);
            proxyListScrollOffset = 0;
            proxyListScroll.jumpTo(0, 0);
            rebuildButtons();
        }
        MemoryUtil.memFree(txtFilter);
    }

    private void clearProxyFields() {
        nameField.setValue("");
        addressField.setValue("");
        portField.setValue("");
        usernameField.setValue("");
        passwordField.setValue("");
    }

    private void clearProxySelection() {
        selectedProxy = null;
        clearProxyFields();
        clearInputFocus();
        rebuildButtons();
    }

    private AutismProxy proxyFromFields() {
        AutismProxy proxy = new AutismProxy();
        proxy.name = safeTrim(nameField.getValue());
        proxy.address = safeTrim(addressField.getValue());
        proxy.username = safeTrim(usernameField.getValue());
        proxy.password = passwordField.getValue();
        proxy.type = type;
        if (proxy.address.isBlank()) {
            showProxyToast("Enter a proxy address first.", WARN);
            return null;
        }
        try {
            proxy.port = Integer.parseInt(safeTrim(portField.getValue()));
        } catch (NumberFormatException e) {
            showProxyToast("Invalid proxy port.", WARN);
            return null;
        }
        if (!proxy.isValid()) {
            showProxyToast("Proxy must have a valid address and port.", WARN);
            return null;
        }
        return proxy;
    }

    private void showProxyToast(String message, int accentColor) {
        if (message == null || message.isBlank() || this.minecraft == null) return;
        this.minecraft.execute(() -> AutismNotifications.show(message, accentColor));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int virtualMouseX = AutismUiScale.toVirtualInt(mouseX);
        int virtualMouseY = AutismUiScale.toVirtualInt(mouseY);
        AutismUiScale.pushOverlayScale(graphics);
        try {
        UiRenderer.rect(graphics, UiBounds.of(0, 0, screenWidth(), screenHeight()), BG);
        int panelX = panelX();
        drawPanel(graphics, panelX + 10, TOP_PANEL_Y, panelWidth() - 20, TOP_PANEL_HEIGHT, PANEL_BG);
        drawPanel(graphics, listX(), LIST_TOP, listWidth(), listPanelHeight(), PANEL_BG_SOFT);

        try {
            drawText(graphics, "Proxies", panelX + 22, TOP_PANEL_Y + 10, TEXT, false);
            if (compactLayout()) {
                drawText(graphics, "Window too small.", panelX + 22, TOP_PANEL_Y + 34, MUTED, false, Math.max(0, panelWidth() - 44));
            }
            List<AutismProxy> proxies = filteredProxies();
            int total = AutismProxyManager.get().all().size();
            int enabled = AutismProxyManager.get().getEnabled() == null ? 0 : 1;
            String summary = proxies.size() == total
                ? total + " proxies  enabled " + enabled
                : proxies.size() + " / " + total + " proxies  enabled " + enabled;
            drawText(graphics, summary, listX() + 12, LIST_TOP + 10, TEXT, false, listWidth() - 24);
            String refreshing = AutismProxyManager.get().isRefreshing() ? "REFRESHING" : "";
            if (!refreshing.isBlank()) drawText(graphics, refreshing, listX() + listWidth() - 92, LIST_TOP + 10, WARN, false, 80);
            for (ProxyRow row : proxyRows) renderProxyRow(graphics, row, virtualMouseX, virtualMouseY);
            if (AutismProxyManager.get().all().isEmpty()) {
                drawText(graphics, "No proxies saved yet. Add one manually or import a .txt list.", listX() + 12, proxyRowsTop() + 12, MUTED, false, listWidth() - 24);
            } else if (proxies.isEmpty()) {
                drawText(graphics, "No proxies match the current search or filter.", listX() + 12, proxyRowsTop() + 12, MUTED, false, listWidth() - 24);
            }
            for (CompactOverlayButton button : buttons) {
                CompactOverlayButton.renderStyled(graphics, this.font, button, virtualMouseX, virtualMouseY);
            }
            if (!compactLayout()) {
                CompactScrollbar.Metrics scrollbar = proxyScrollbarMetrics(proxies.size());
                CompactScrollbar.draw(graphics, scrollbar, scrollbar.contains(virtualMouseX, virtualMouseY), proxyScrollbarDragging);
            }
        } finally {
        }

        super.extractRenderState(graphics, virtualMouseX, virtualMouseY, delta);
        } finally {
            AutismUiScale.popOverlayScale(graphics);
        }
    }

    @Override
    public void removed() {
        super.removed();
    }

    private void renderProxyRow(GuiGraphicsExtractor graphics, ProxyRow row, int mouseX, int mouseY) {
        AutismProxy proxy = row.proxy;
        boolean selected = proxy.equals(selectedProxy);
        int x = proxyRowX();
        int y = row.y;
        int w = proxyRowWidth();
        int fill = proxy.enabled ? 0x3324D86A : selected ? 0x242B1A1D : 0x18111113;
        int border = proxy.enabled ? BORDER_ACTIVE : selected ? 0xFF5A3038 : BORDER;
        UiRenderer.frame(graphics, UiBounds.of(x, y, w, ROW_HEIGHT - 3), fill, border);

        String name = proxy.displayName().isBlank() ? "(unnamed)" : proxy.displayName();
        String address = proxy.type + "  " + proxy.address + ":" + proxy.port;
        String auth = proxy.username == null || proxy.username.isBlank() ? "NoA" : "Auth";
        String status = statusText(proxy);
        int nameX = x + 64;
        int metaRight = row.checkButton.getX() - 8;
        int metaW = 72;
        int metaX = Math.max(nameX + 36, metaRight - metaW);
        int textRight = Math.max(nameX + 24, metaX - 8);
        int textW = Math.max(20, textRight - nameX);
        int metaTextW = Math.max(20, metaRight - metaX);
        drawText(graphics, name, nameX, y + 4, proxy.enabled ? SUCCESS : TEXT, false, textW);
        drawText(graphics, address, nameX, y + 14, MUTED, false, textW);
        drawText(graphics, auth, metaX, y + 4, proxy.username == null || proxy.username.isBlank() ? MUTED : DEFAULT_COLOR, false, metaTextW);
        drawText(graphics, status, metaX, y + 14, proxy.status.color(), false, metaTextW);
        CompactOverlayButton.renderStyled(graphics, this.font, row.toggleButton, mouseX, mouseY);
        CompactOverlayButton.renderStyled(graphics, this.font, row.checkButton, mouseX, mouseY);
        CompactOverlayButton.renderStyled(graphics, this.font, row.deleteButton, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MouseButtonEvent virtualEvent = virtualEvent(event);
        if (virtualEvent.button() != 0) return super.mouseClicked(virtualEvent, doubleClick);
        if (compactLayout()) return super.mouseClicked(virtualEvent, doubleClick);
        CompactScrollbar.Metrics scrollbar = proxyScrollbarMetrics(filteredProxies().size());
        if (scrollbar.hasScroll() && scrollbar.contains(virtualEvent.x(), virtualEvent.y())) {
            proxyScrollbarDragging = true;
            proxyScrollbarGrabOffset = scrollbar.overThumb(virtualEvent.x(), virtualEvent.y()) ? Math.max(0, (int) Math.round(virtualEvent.y()) - scrollbar.thumbY()) : scrollbar.thumbHeight() / 2;
            proxyListScrollOffset = quantizeScrollOffset(CompactScrollbar.scrollFromThumb(scrollbar, virtualEvent.y(), proxyScrollbarGrabOffset), ROW_HEIGHT, scrollbar.maxScroll());
            proxyListScroll.jumpTo(proxyListScrollOffset, scrollbar.maxScroll());
            rebuildButtons();
            clearInputFocus();
            return true;
        }
        for (CompactOverlayButton button : buttons) {
            if (CompactOverlayButton.fireIfHit(button, virtualEvent.x(), virtualEvent.y(), virtualEvent.button())) return true;
        }
        for (ProxyRow row : proxyRows) {
            if (overButton(row.toggleButton, virtualEvent.x(), virtualEvent.y())
                || overButton(row.checkButton, virtualEvent.x(), virtualEvent.y())
                || overButton(row.deleteButton, virtualEvent.x(), virtualEvent.y())) {
                CompactOverlayButton.fireIfHit(row.toggleButton, virtualEvent.x(), virtualEvent.y(), virtualEvent.button());
                CompactOverlayButton.fireIfHit(row.checkButton, virtualEvent.x(), virtualEvent.y(), virtualEvent.button());
                CompactOverlayButton.fireIfHit(row.deleteButton, virtualEvent.x(), virtualEvent.y(), virtualEvent.button());
                return true;
            }
            if (virtualEvent.x() >= proxyRowX() && virtualEvent.x() < proxyRowRight() && virtualEvent.y() >= row.y && virtualEvent.y() < row.y + ROW_HEIGHT - 3) {
                selectProxy(row.proxy);
                return true;
            }
        }
        clearInputFocus();
        return super.mouseClicked(virtualEvent, doubleClick);
    }

    private boolean overButton(CompactOverlayButton button, double mouseX, double mouseY) {
        return button != null
            && button.visible
            && mouseX >= button.getX()
            && mouseX < button.getX() + button.getWidth()
            && mouseY >= button.getY()
            && mouseY < button.getY() + button.getHeight();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (proxyScrollbarDragging) {
            proxyScrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(virtualEvent(event));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        MouseButtonEvent virtualEvent = virtualEvent(event);
        if (proxyScrollbarDragging) {
            CompactScrollbar.Metrics scrollbar = proxyScrollbarMetrics(filteredProxies().size());
            proxyListScrollOffset = quantizeScrollOffset(CompactScrollbar.scrollFromThumb(scrollbar, virtualEvent.y(), proxyScrollbarGrabOffset), ROW_HEIGHT, scrollbar.maxScroll());
            proxyListScroll.jumpTo(proxyListScrollOffset, scrollbar.maxScroll());
            rebuildButtons();
            return true;
        }
        return super.mouseDragged(virtualEvent, AutismUiScale.toVirtual(dx), AutismUiScale.toVirtual(dy));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        x = AutismUiScale.toVirtual(x);
        y = AutismUiScale.toVirtual(y);
        if (compactLayout()) return super.mouseScrolled(x, y, scrollX, scrollY);
        if (x < listX() || x >= listX() + listWidth() || y < LIST_TOP || y >= LIST_TOP + listPanelHeight()) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        int maxScroll = proxyMaxScroll(filteredProxies().size());
        if (maxScroll <= 0) return true;
        int next = proxyListScrollOffset - (int) Math.signum(scrollY) * ROW_HEIGHT;
        proxyListScrollOffset = quantizeScrollOffset(next, ROW_HEIGHT, maxScroll);
        proxyListScroll.setTarget(proxyListScrollOffset, maxScroll);
        rebuildButtons();
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private void selectProxy(AutismProxy proxy) {
        selectedProxy = proxy;
        if (proxy != null) {
            nameField.setValue(safeTrim(proxy.name));
            addressField.setValue(safeTrim(proxy.address));
            portField.setValue(proxy.port <= 0 ? "" : Integer.toString(proxy.port));
            usernameField.setValue(safeTrim(proxy.username));
            passwordField.setValue(proxy.password == null ? "" : proxy.password);
            type = proxy.type == null ? AutismProxyType.Socks5 : proxy.type;
        }
        rebuildButtons();
    }

    private List<AutismProxy> filteredProxies() {
        List<AutismProxy> proxies = AutismProxyManager.get().all();
        String query = normalize(searchQuery);
        if (query.isEmpty() && filter == ProxyFilter.ALL) return proxies;
        List<AutismProxy> filtered = new ArrayList<>();
        for (AutismProxy proxy : proxies) {
            if (proxy == null || !matchesFilter(proxy)) continue;
            if (!query.isEmpty() && !matchesSearch(proxy, query)) continue;
            filtered.add(proxy);
        }
        return filtered;
    }

    private boolean matchesFilter(AutismProxy proxy) {
        return switch (filter) {
            case ALL -> true;
            case ALIVE -> proxy.status == AutismProxy.Status.ALIVE;
            case DEAD -> proxy.status == AutismProxy.Status.DEAD;
            case UNCHECKED -> proxy.status == AutismProxy.Status.UNCHECKED || proxy.status == AutismProxy.Status.CHECKING;
            case ENABLED -> proxy.enabled;
        };
    }

    private boolean matchesSearch(AutismProxy proxy, String query) {
        String haystack = normalize(proxy.displayName() + " " + proxy.address + " " + proxy.port + " " + proxy.type + " " + proxy.status + " " + safeTrim(proxy.username));
        return haystack.contains(query);
    }

    private String statusText(AutismProxy proxy) {
        if (proxy == null) return "";
        return switch (proxy.status) {
            case ALIVE -> proxy.latency + "ms";
            case DEAD -> "Dead";
            case CHECKING -> "Checking";
            case UNCHECKED -> "Unchecked";
        };
    }

    private int panelX() {
        return DirectLayout.centerPanel(screenWidth(), panelWidth(), PANEL_MARGIN);
    }

    private int panelWidth() {
        return DirectLayout.fitPanelDimension(screenWidth(), PANEL_MARGIN, PANEL_WIDTH);
    }

    private int listX() {
        return panelX() + 10;
    }

    private int listWidth() {
        return Math.max(1, panelWidth() - 20);
    }

    private int listPanelHeight() {
        return Math.max(1, screenHeight() - LIST_TOP - LIST_BOTTOM_MARGIN);
    }

    private int proxyRowsTop() {
        return LIST_TOP + LIST_HEADER_HEIGHT;
    }

    private int proxyRowsBottom() {
        return LIST_TOP + listPanelHeight() - 6;
    }

    private int proxyViewportHeight() {
        return Math.max(ROW_HEIGHT, alignViewportHeight(Math.max(1, proxyRowsBottom() - proxyRowsTop()), ROW_HEIGHT));
    }

    private int proxyMaxScroll(int rows) {
        return Math.max(0, rows * ROW_HEIGHT - proxyViewportHeight());
    }

    private CompactScrollbar.Metrics proxyScrollbarMetrics(int rows) {
        int contentPixels = Math.max(0, rows) * ROW_HEIGHT;
        int trackX = listX() + listWidth() - 8;
        int trackY = proxyRowsTop();
        int trackHeight = proxyViewportHeight();
        return CompactScrollbar.compute(contentPixels, proxyViewportHeight(), trackX, trackY, LIST_SCROLLBAR_WIDTH, trackHeight, proxyListScroll.tick(0.0f, proxyMaxScroll(rows)));
    }

    private int proxyRowX() {
        return listX() + 8;
    }

    private int proxyRowRight() {
        return listX() + listWidth() - 8 - LIST_SCROLLBAR_GUTTER;
    }

    private int proxyRowWidth() {
        return Math.max(1, proxyRowRight() - proxyRowX());
    }

    private boolean compactLayout() {
        return panelWidth() < 360 || screenHeight() < LIST_TOP + 44;
    }

    private void setFormFieldsVisible(boolean visible) {
        if (nameField != null) nameField.visible = visible;
        if (addressField != null) addressField.visible = visible;
        if (portField != null) portField.visible = visible;
        if (usernameField != null) usernameField.visible = visible;
        if (passwordField != null) passwordField.visible = visible;
        if (searchField != null) searchField.visible = visible;
    }

    private int[] scaledWidths(int availableWidth, int gap, int... preferredWidths) {
        int[] widths = new int[preferredWidths.length];
        int gaps = Math.max(0, preferredWidths.length - 1) * Math.max(0, gap);
        int preferredTotal = 0;
        for (int preferred : preferredWidths) preferredTotal += Math.max(1, preferred);
        int usable = Math.max(preferredWidths.length, availableWidth - gaps);
        int assigned = 0;
        for (int i = 0; i < preferredWidths.length; i++) {
            widths[i] = i == preferredWidths.length - 1
                ? Math.max(1, usable - assigned)
                : Math.max(1, Math.round(Math.max(1, preferredWidths[i]) * usable / (float) preferredTotal));
            assigned += widths[i];
        }
        return widths;
    }

    private void clearInputFocus() {
        if (nameField != null) nameField.setFocused(false);
        if (addressField != null) addressField.setFocused(false);
        if (portField != null) portField.setFocused(false);
        if (usernameField != null) usernameField.setFocused(false);
        if (passwordField != null) passwordField.setFocused(false);
        if (searchField != null) searchField.setFocused(false);
        this.setFocused(null);
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int fill) {
        SectionPanel.renderBody(UiContexts.overlay(graphics, font, -10000, -10000), UiBounds.of(x, y, w, h), fill);
    }

    private void drawText(GuiGraphicsExtractor graphics, String text, int x, int y, int color, boolean center) {
        drawText(graphics, text, x, y, color, center, Integer.MAX_VALUE);
    }

    private void drawText(GuiGraphicsExtractor graphics, String text, int x, int y, int color, boolean center, int maxWidth) {
        Font renderer = this.font;
        Identifier font = THEME.fontFor(UiTone.BODY);
        String value = text == null ? "" : text;
        if (maxWidth != Integer.MAX_VALUE && !center) {
            UiText.drawFitted(graphics, renderer, value, font, color, x, y, Math.max(1, maxWidth), false);
            return;
        }
        if (maxWidth != Integer.MAX_VALUE) value = UiText.trimToWidth(renderer, value, maxWidth, font, color);
        int w = UiText.width(renderer, value, font, color);
        int drawX = center ? x - w / 2 : x;
        UiText.draw(graphics, renderer, value, font, color, drawX, y, false);
    }

    private static int alignViewportHeight(int height, int step) {
        if (step <= 0) return Math.max(1, height);
        return Math.max(step, (Math.max(1, height) / step) * step);
    }

    private static int quantizeScrollOffset(int value, int step, int maxScroll) {
        int clamped = Math.max(0, Math.min(maxScroll, value));
        if (step <= 0) return clamped;
        int rounded = Math.round(clamped / (float) step) * step;
        return Math.max(0, Math.min(maxScroll, rounded));
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value) {
        return safeTrim(value).toLowerCase(Locale.ROOT);
    }

    private int screenWidth() {
        int width = AutismUiScale.getVirtualScreenWidth();
        return width <= 0 ? this.width : width;
    }

    private int screenHeight() {
        int height = AutismUiScale.getVirtualScreenHeight();
        return height <= 0 ? this.height : height;
    }

    private static MouseButtonEvent virtualEvent(MouseButtonEvent event) {
        return new MouseButtonEvent(AutismUiScale.toVirtual(event.x()), AutismUiScale.toVirtual(event.y()), new MouseButtonInfo(event.button(), 0));
    }

    private enum ProxyFilter {
        ALL,
        ALIVE,
        DEAD,
        UNCHECKED,
        ENABLED
    }

    private record ProxyRow(AutismProxy proxy, int y, CompactOverlayButton toggleButton, CompactOverlayButton checkButton, CompactOverlayButton deleteButton) {
    }
}
