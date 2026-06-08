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
import autismclient.util.AutismAccount;
import autismclient.util.AutismAccountManager;
import autismclient.util.AutismAccountSessionSwitcher;
import autismclient.util.AutismAccountType;
import autismclient.util.AutismHttp;
import autismclient.util.AutismMicrosoftLogin;
import autismclient.util.AutismUiScale;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.blaze3d.Blaze3D;
import com.mojang.util.UndashedUuid;
import net.minecraft.client.User;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

public class AutismAccountsScreen extends Screen {
    private static final CompactTheme THEME = new CompactTheme();
    private static final int BG = 0xFF0E0E10;
    private static final int PANEL_BG = 0xE818181B;
    private static final int PANEL_BG_SOFT = 0xB8141417;
    private static final int BORDER = 0xFF332428;
    private static final int BORDER_ACTIVE = 0xFF35D873;
    private static final int BORDER_DEFAULT = 0xFF5F7CFF;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFF9A9A9A;
    private static final int SUCCESS = 0xFF35D873;
    private static final int DEFAULT_COLOR = 0xFF8EA0FF;
    private static final int ERROR = 0xFFFF5B5B;
    private static final int WARN = 0xFFFFC857;
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_MARGIN = 12;
    private static final int ROW_HEIGHT = 24;
    private static final int FORM_WIDTH = 292;
    private static final int PREVIEW_WIDTH = 200;
    private static final int PANEL_GAP = 8;
    private static final int PROVIDER_BUTTON_WIDTH = 66;
    private static final int TOP_PANEL_Y = 20;
    private static final int TOP_PANEL_HEIGHT = 178;
    private static final int LIST_TOP = 204;
    private static final int LIST_HEADER_HEIGHT = 22;
    private static final int LIST_BOTTOM_MARGIN = 12;
    private static final int FIELD_Y = 88;
    private static final int ACTION_Y = 114;
    private static final int SEARCH_Y = 140;
    private static final int FILTER_Y = 174;
    private static final int MAX_SKIN_LOOKUPS = 10;
    private static final int LIST_SCROLLBAR_WIDTH = 4;
    private static final int LIST_SCROLLBAR_GUTTER = 12;
    private static final long CANCEL_BUTTON_DELAY_MS = 5000L;
    private static final String TEXTURES_PROPERTY = "textures";

    private final Screen parent;
    private final List<CompactOverlayButton> buttons = new ArrayList<>();
    private final List<AccountRow> accountRows = new ArrayList<>();
    private final Map<String, SkinLookup> skinLookups = new LinkedHashMap<>(16, 0.75F, true);
    private final ScrollState savedListScroll = new ScrollState();
    private final EnumSet<AutismAccountType> accountTypeFilters = EnumSet.allOf(AutismAccountType.class);
    private EditBox labelField;
    private EditBox tokenField;
    private EditBox searchField;
    private PlayerModel widePlayerModel;
    private PlayerModel slimPlayerModel;
    private AutismAccountType type = AutismAccountType.Cracked;
    private AutismAccount selectedAccount;
    private String searchQuery = "";
    private Operation operation = Operation.NONE;
    private int operationId;
    private long operationStartedAtMs;
    private Thread operationThread;
    private CompactOverlayButton cancelOperationButton;
    private int savedListScrollOffset;
    private boolean accountScrollbarDragging;
    private int accountScrollbarGrabOffset;
    private boolean previewDragging;
    private float previewRotationX = -5.0F;
    private float previewRotationY = 30.0F;
    private double previewAutoRotationStartTime = Blaze3D.getTime();
    private double lastPreviewMouseX;
    private double lastPreviewMouseY;

    public AutismAccountsScreen(Screen parent) {
        super(Component.literal("Accounts"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelX = panelX();
        int fieldY = FIELD_Y;
        this.labelField = new EditBox(this.font, panelX + 18, fieldY, FORM_WIDTH - 36, 18, Component.literal("Username"));
        this.labelField.setHint(Component.literal("Username"));
        this.labelField.setMaxLength(256);
        this.addRenderableWidget(labelField);
        this.tokenField = new EditBox(this.font, panelX + 18, fieldY, FORM_WIDTH - 36, 18, Component.literal("Token"));
        this.tokenField.setHint(Component.literal("Token"));
        this.tokenField.setMaxLength(4096);
        this.addRenderableWidget(tokenField);
        this.searchField = new EditBox(this.font, panelX + 18, SEARCH_Y, FORM_WIDTH - 36, 18, Component.literal("Search accounts"));
        this.searchField.setHint(Component.literal("Search nickname..."));
        this.searchField.setMaxLength(64);
        this.searchField.setResponder(value -> {
            searchQuery = safeTrim(value);
            savedListScrollOffset = 0;
            savedListScroll.jumpTo(0, 0);
            rebuildButtons();
        });
        this.addRenderableWidget(searchField);
        this.widePlayerModel = new PlayerModel(this.minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        this.slimPlayerModel = new PlayerModel(this.minecraft.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
        updateInputVisibility();
        rebuildButtons();
    }

    private void rebuildButtons() {
        buttons.clear();
        accountRows.clear();
        int panelX = panelX();
        int formX = panelX + 10;
        buttons.add(CompactOverlayButton.create(10, 10, 76, 18, Component.literal("Back"), b -> this.minecraft.setScreen(parent)).setVariant(CompactOverlayButton.Variant.SECONDARY));
        if (!narrowLayout()) {
        int y = 58;
        addProviderButton(formX + 8, y, AutismAccountType.Cracked, "Cracked");
        addProviderButton(formX + 77, y, AutismAccountType.TheAltening, "Altening");
        addProviderButton(formX + 146, y, AutismAccountType.Session, "Session");
        addProviderButton(formX + 215, y, AutismAccountType.Microsoft, "Microsoft");

        int actionY = type == AutismAccountType.Microsoft ? 84 : ACTION_Y;
        int addWidth = type == AutismAccountType.Microsoft ? FORM_WIDTH - 16 : 116;
        int addHeight = type == AutismAccountType.Microsoft ? 20 : 18;
        CompactOverlayButton add = CompactOverlayButton.create(formX + 8, actionY, addWidth, addHeight, Component.literal(addButtonLabel()), b -> addAccount()).setVariant(CompactOverlayButton.Variant.PRIMARY);
        add.active = !isBusy();
        buttons.add(add);
        if (type != AutismAccountType.Microsoft) {
            CompactOverlayButton clear = CompactOverlayButton.create(formX + 128, ACTION_Y, 64, 18, Component.literal("Clear"), b -> clearFields()).setVariant(CompactOverlayButton.Variant.SECONDARY);
            clear.active = !isBusy();
            buttons.add(clear);
        }
        int cancelX = type == AutismAccountType.Microsoft ? formX + 8 : formX + 196;
        int cancelY = type == AutismAccountType.Microsoft ? 114 : ACTION_Y;
        int cancelWidth = type == AutismAccountType.Microsoft ? FORM_WIDTH - 16 : 88;
        CompactOverlayButton cancel = CompactOverlayButton.create(cancelX, cancelY, cancelWidth, 18, Component.literal("Cancel"), b -> cancelOperation()).setVariant(CompactOverlayButton.Variant.DANGER);
        cancel.visible = shouldShowCancelOperationButton();
        cancelOperationButton = cancel;
        buttons.add(cancel);
        addFilterButton(formX + 8, FILTER_Y, AutismAccountType.Cracked, "Cracked");
        addFilterButton(formX + 77, FILTER_Y, AutismAccountType.Microsoft, "Microsoft");
        addFilterButton(formX + 146, FILTER_Y, AutismAccountType.Session, "Session");
        addFilterButton(formX + 215, FILTER_Y, AutismAccountType.TheAltening, "Altening");
        }

        List<DisplayAccountRow> displayAccounts = displayAccounts();
        if (compactListLayout()) return;
        int maxScroll = savedMaxScroll(displayAccounts.size());
        savedListScrollOffset = quantizeScrollOffset(savedListScrollOffset, ROW_HEIGHT, maxScroll);
        savedListScroll.jumpTo(savedListScrollOffset, maxScroll);
        int firstVisible = savedListScrollOffset / ROW_HEIGHT;
        int rowY = savedRowsTop() - (savedListScrollOffset % ROW_HEIGHT);
        for (int i = firstVisible; i < displayAccounts.size() && rowY + ROW_HEIGHT - 3 <= savedRowsBottom(); i++) {
            DisplayAccountRow displayRow = displayAccounts.get(i);
            AutismAccount account = displayRow.account();
            if (rowY + ROW_HEIGHT - 3 <= savedRowsTop()) {
                rowY += ROW_HEIGHT;
                continue;
            }
            boolean current = displayRow.defaultAccount() ? isCurrentDefaultAccount() : isCurrentAccount(account);
            CompactOverlayButton login = CompactOverlayButton.create(rowRight() - (displayRow.defaultAccount() ? 62 : 122), rowButtonY(rowY, 16), 54, 16, Component.literal(current ? "Active" : "Login"), b -> {
                if (displayRow.defaultAccount()) loginDefaultAccount();
                else loginAccount(account);
            });
            login.setVariant(current ? CompactOverlayButton.Variant.SUCCESS : CompactOverlayButton.Variant.PRIMARY);
            login.active = !isBusy() && !current;
            CompactOverlayButton delete = null;
            if (!displayRow.defaultAccount()) {
                delete = CompactOverlayButton.create(rowRight() - 62, rowButtonY(rowY, 16), 54, 16, Component.literal("Delete"), b -> deleteAccount(account));
                delete.active = !isBusy();
            }
            boolean loadRowSkin = accountRows.size() < MAX_SKIN_LOOKUPS;
            accountRows.add(new AccountRow(account, rowY, login, delete, displayRow.defaultAccount(), loadRowSkin));
            rowY += ROW_HEIGHT;
        }
    }

    private void addProviderButton(int x, int y, AutismAccountType provider, String label) {
        CompactOverlayButton button = CompactOverlayButton.create(x, y, PROVIDER_BUTTON_WIDTH, 18, Component.literal(label), b -> {
            if (isBusy()) return;
            type = provider;
            clearInputFocus();
            updateInputVisibility();
            rebuildButtons();
        });
        button.active = !isBusy();
        button.setVariant(type == provider ? CompactOverlayButton.Variant.SUCCESS : CompactOverlayButton.Variant.SECONDARY);
        buttons.add(button);
    }

    private void addFilterButton(int x, int y, AutismAccountType provider, String label) {
        CompactOverlayButton button = CompactOverlayButton.create(x, y, PROVIDER_BUTTON_WIDTH, 16, Component.literal(label), b -> toggleFilter(provider));
        button.setVariant(accountTypeFilters.contains(provider) ? CompactOverlayButton.Variant.FILTER_ON : CompactOverlayButton.Variant.FILTER_OFF);
        buttons.add(button);
    }

    private void addAccount() {
        if (isBusy()) return;
        AutismAccount account = new AutismAccount();
        account.type = type;
        if (type == AutismAccountType.Cracked) {
            account.label = safeTrim(labelField.getValue());
            if (account.label.isBlank()) {
                showAccountToast("Enter a cracked username first.", WARN);
                return;
            }
            runAdd(account);
        } else if (type == AutismAccountType.Session) {
            account.token = safeTrim(tokenField.getValue());
            if (account.token.isBlank()) {
                showAccountToast("Paste a Minecraft access token first.", WARN);
                return;
            }
            runAdd(account);
        } else if (type == AutismAccountType.TheAltening) {
            account.token = safeTrim(tokenField.getValue());
            if (account.token.isBlank()) {
                showAccountToast("Paste a TheAltening token first.", WARN);
                return;
            }
            runAdd(account);
        } else if (type == AutismAccountType.Microsoft) {
            runMicrosoftAdd();
        }
    }

    private void runAdd(AutismAccount account) {
        int id = beginOperation(Operation.ADD);
        operationThread = new Thread(() -> {
            boolean fetched = account.fetchInfo();
            if (isCancelled(id)) return;
            if (!fetched) {
                finishOperation(id, false, "Failed to fetch account info.", false);
                return;
            }
            if (AutismAccountManager.get().contains(account)) {
                finishOperation(id, false, "Account already exists: " + account.displayName(), false);
                return;
            }
            boolean loggedIn = account.login();
            if (isCancelled(id)) return;
            if (!loggedIn) {
                finishOperation(id, false, "Failed to login account: " + account.displayName(), false);
                return;
            }
            AutismAccountManager.get().add(account);
            selectedAccount = account;
            finishOperation(id, true, "Logged in as " + account.displayName() + ".", true);
        }, "Autism-Account-Add");
        operationThread.setDaemon(true);
        operationThread.start();
    }

    private void runMicrosoftAdd() {
        int id = beginOperation(Operation.MICROSOFT);
        AutismMicrosoftLogin.getRefreshToken(refreshToken -> {
            if (isCancelled(id)) return;
            if (refreshToken == null || refreshToken.isBlank()) {
                finishOperation(id, false, "Microsoft login cancelled or failed.", false);
                return;
            }
            AutismAccount account = new AutismAccount();
            account.type = AutismAccountType.Microsoft;
            account.label = refreshToken;
            operationThread = new Thread(() -> {
                boolean fetched = account.fetchInfo();
                if (isCancelled(id)) return;
                if (!fetched) {
                    finishOperation(id, false, "Failed to fetch Microsoft profile.", false);
                    return;
                }
                if (AutismAccountManager.get().contains(account)) {
                    finishOperation(id, false, "Microsoft account already exists: " + account.displayName(), false);
                    return;
                }
                boolean loggedIn = account.login();
                if (isCancelled(id)) return;
                if (!loggedIn) {
                    finishOperation(id, false, "Failed to login Microsoft account.", false);
                    return;
                }
                AutismAccountManager.get().add(account);
                selectedAccount = account;
                finishOperation(id, true, "Logged in as " + account.displayName() + ".", true);
            }, "Autism-Microsoft-Add");
            operationThread.setDaemon(true);
            operationThread.start();
        });
    }

    private void loginAccount(AutismAccount account) {
        if (isBusy() || account == null || isCurrentAccount(account)) return;
        selectedAccount = account;
        int id = beginOperation(Operation.LOGIN);
        operationThread = new Thread(() -> {
            boolean fetched = account.fetchInfo();
            if (isCancelled(id)) return;
            if (!fetched) {
                finishOperation(id, false, "Failed to refresh account: " + account.displayName(), false);
                return;
            }
            boolean loggedIn = account.login();
            if (isCancelled(id)) return;
            if (loggedIn) {
                AutismAccountManager.get().save();
                finishOperation(id, true, "Logged in as " + account.displayName() + ".", false);
            } else {
                finishOperation(id, false, "Failed to login account: " + account.displayName(), false);
            }
        }, "Autism-Account-Login");
        operationThread.setDaemon(true);
        operationThread.start();
    }

    private void loginDefaultAccount() {
        if (isBusy() || isCurrentDefaultAccount()) return;
        AutismAccount account = defaultMinecraftAccount();
        if (account == null) return;
        selectedAccount = account;
        int id = beginOperation(Operation.LOGIN);
        operationThread = new Thread(() -> {
            boolean loggedIn = AutismAccountSessionSwitcher.setSession(AutismAccountSessionSwitcher.getOriginalUser());
            if (isCancelled(id)) return;
            if (loggedIn) {
                finishOperation(id, true, "Logged in as " + account.displayName() + ".", false);
            } else {
                finishOperation(id, false, "Failed to restore default Minecraft session.", false);
            }
        }, "Autism-Default-Account-Login");
        operationThread.setDaemon(true);
        operationThread.start();
    }

    private void deleteAccount(AutismAccount account) {
        if (isBusy() || account == null) return;
        AutismAccountManager.get().remove(account);
        if (account.equals(selectedAccount)) selectedAccount = null;
        showAccountToast("Deleted " + account.displayName() + ".", MUTED);
        rebuildButtons();
    }

    private int beginOperation(Operation next) {
        operationId++;
        operation = next;
        operationStartedAtMs = System.currentTimeMillis();
        clearInputFocus();
        updateInputVisibility();
        rebuildButtons();
        return operationId;
    }

    private void finishOperation(int id, boolean success, String message, boolean clearOnSuccess) {
        this.minecraft.execute(() -> {
            if (id != operationId) return;
            operation = Operation.NONE;
            operationStartedAtMs = 0L;
            operationThread = null;
            showAccountToast(message, success ? SUCCESS : ERROR);
            if (success && clearOnSuccess) clearFields();
            updateInputVisibility();
            clearInputFocus();
            rebuildButtons();
        });
    }

    private void showAccountToast(String message, int accentColor) {
        if (message == null || message.isBlank() || this.minecraft == null) return;
        this.minecraft.execute(() -> AutismNotifications.show(message, accentColor));
    }

    private void cancelOperation() {
        if (!isBusy()) return;
        operationId++;
        Thread thread = operationThread;
        if (thread != null) thread.interrupt();
        if (operation == Operation.MICROSOFT) AutismMicrosoftLogin.stopServer();
        operation = Operation.NONE;
        operationStartedAtMs = 0L;
        operationThread = null;
        clearInputFocus();
        showAccountToast("Cancelled.", WARN);
        updateInputVisibility();
        rebuildButtons();
    }

    private boolean isCancelled(int id) {
        return id != operationId || Thread.currentThread().isInterrupted();
    }

    private boolean isBusy() {
        return operation != Operation.NONE;
    }

    private boolean shouldShowCancelOperationButton() {
        return isBusy() && operationStartedAtMs > 0L && System.currentTimeMillis() - operationStartedAtMs >= CANCEL_BUTTON_DELAY_MS;
    }

    private void refreshOperationControls() {
        if (cancelOperationButton != null) {
            cancelOperationButton.visible = shouldShowCancelOperationButton();
        }
    }

    private void clearFields() {
        if (labelField != null) labelField.setValue("");
        if (tokenField != null) tokenField.setValue("");
    }

    private void updateInputVisibility() {
        if (labelField == null || tokenField == null) return;
        boolean showForm = !narrowLayout();
        boolean labelVisible = showForm && type == AutismAccountType.Cracked;
        boolean tokenVisible = showForm && (type == AutismAccountType.Session || type == AutismAccountType.TheAltening);
        labelField.setVisible(labelVisible);
        tokenField.setVisible(tokenVisible);
        labelField.active = labelVisible && !isBusy();
        tokenField.active = tokenVisible && !isBusy();
        if (!labelVisible || isBusy()) labelField.setFocused(false);
        if (!tokenVisible || isBusy()) tokenField.setFocused(false);
        if (searchField != null) {
            searchField.visible = showForm;
            searchField.active = showForm;
        }
        labelField.setHint(Component.literal("Username"));
        tokenField.setHint(Component.literal(type == AutismAccountType.TheAltening ? "TheAltening token" : "Minecraft access token"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int virtualMouseX = AutismUiScale.toVirtualInt(mouseX);
        int virtualMouseY = AutismUiScale.toVirtualInt(mouseY);
        refreshOperationControls();
        AutismUiScale.pushOverlayScale(graphics);
        try {
        UiRenderer.rect(graphics, UiBounds.of(0, 0, screenWidth(), screenHeight()), BG);
        int panelX = panelX();
        int formX = panelX + 10;
        int previewX = previewX();
        if (!narrowLayout()) {
            drawPanel(graphics, formX, TOP_PANEL_Y, FORM_WIDTH, TOP_PANEL_HEIGHT, PANEL_BG);
            drawPanel(graphics, previewX, TOP_PANEL_Y, PREVIEW_WIDTH, TOP_PANEL_HEIGHT, PANEL_BG_SOFT);
        }
        int listHeight = listPanelHeight();
        drawPanel(graphics, listX(), listTop(), listWidth(), listHeight, PANEL_BG);

        try {
            if (!narrowLayout()) {
                drawText(graphics, "Accounts", formX + 12, 31, TEXT, false);
                renderPreview(graphics, previewX, virtualMouseX, virtualMouseY, delta);
            }
            List<DisplayAccountRow> displayAccounts = displayAccounts();
            if (compactListLayout()) {
                drawText(graphics, "Window too small.", listX() + 12, listTop() + 12, MUTED, false, Math.max(0, listWidth() - 24));
            }
            int firstVisibleRow = savedListScrollOffset / ROW_HEIGHT;
            String listTitle = displayAccounts.size() <= savedViewportRows()
                ? "Accounts"
                : "Accounts  showing " + (firstVisibleRow + 1) + "-" + Math.min(displayAccounts.size(), firstVisibleRow + savedViewportRows()) + " / " + displayAccounts.size();
            drawText(graphics, listTitle, listX() + 12, listTop() + 10, TEXT, false, listWidth() - 24);
            if (!compactListLayout()) for (AccountRow row : accountRows) renderRow(graphics, row, virtualMouseX, virtualMouseY);
            if (AutismAccountManager.get().all().isEmpty()) {
                drawText(graphics, "No extra accounts saved yet.", listX() + 12, listRowTop() + ROW_HEIGHT + 8, MUTED, false, listWidth() - 24);
            } else if (filteredAccounts().isEmpty()) {
                drawText(graphics, "No accounts match the current search or filters.", listX() + 12, listRowTop() + ROW_HEIGHT + 8, MUTED, false, listWidth() - 24);
            }
            for (CompactOverlayButton button : buttons) {
                CompactOverlayButton.renderStyled(graphics, this.font, button, virtualMouseX, virtualMouseY);
            }
            if (!compactListLayout()) {
                CompactScrollbar.Metrics scrollbar = accountScrollbarMetrics(displayAccounts.size());
                CompactScrollbar.draw(graphics, scrollbar, scrollbar.contains(virtualMouseX, virtualMouseY), accountScrollbarDragging);
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

    private void renderRow(GuiGraphicsExtractor graphics, AccountRow row, int mouseX, int mouseY) {
        AutismAccount account = row.account;
        boolean active = row.defaultAccount ? isCurrentDefaultAccount() : isCurrentAccount(account);
        boolean selected = account.equals(selectedAccount);
        int x = rowX();
        int y = row.y;
        int w = rowWidth();
        int fill = active ? 0x3324D86A : row.defaultAccount ? 0x302B326B : selected ? 0x242B1A1D : 0x18111113;
        int border = active ? BORDER_ACTIVE : row.defaultAccount ? BORDER_DEFAULT : selected ? 0xFF5A3038 : BORDER;
        UiRenderer.frame(graphics, UiBounds.of(x, y, w, rowVisualHeight()), fill, border);
        PlayerSkin rowSkin = row.loadSkin || active || selected ? skinLookup(account).skin() : fallbackSkin(account);
        drawHead(graphics, rowSkin, x + 7, y + 3, 16);
        String name = account.displayName().isBlank() ? "(unknown)" : account.displayName();
        String meta = row.defaultAccount ? "Default Minecraft" : account.type.name();
        int nameX = x + 31;
        if (narrowLayout()) {
            int textRight = Math.max(nameX + 1, row.loginButton.getX() - 8);
            drawText(graphics, name, nameX, y + 7, active ? SUCCESS : row.defaultAccount ? DEFAULT_COLOR : TEXT, false, Math.max(1, textRight - nameX));
            CompactOverlayButton.renderStyled(graphics, this.font, row.loginButton, mouseX, mouseY);
            if (row.deleteButton != null) CompactOverlayButton.renderStyled(graphics, this.font, row.deleteButton, mouseX, mouseY);
            return;
        }
        int metaX = x + 228;
        int badgeX = row.defaultAccount ? row.loginButton.getX() - 70 : row.loginButton.getX() - 58;
        int nameMaxWidth = Math.max(20, metaX - nameX - 12);
        int metaMaxWidth = Math.max(20, badgeX - metaX - 10);
        drawText(graphics, name, nameX, y + 7, active ? SUCCESS : row.defaultAccount ? DEFAULT_COLOR : TEXT, false, nameMaxWidth);
        drawText(graphics, meta, metaX, y + 7, row.defaultAccount ? DEFAULT_COLOR : MUTED, false, metaMaxWidth);
        if (active) drawText(graphics, "CURRENT", badgeX, y + 8, SUCCESS, false, Math.max(1, row.loginButton.getX() - badgeX - 4));
        else if (row.defaultAccount) drawText(graphics, "DEFAULT", badgeX, y + 8, DEFAULT_COLOR, false, Math.max(1, row.loginButton.getX() - badgeX - 4));
        CompactOverlayButton.renderStyled(graphics, this.font, row.loginButton, mouseX, mouseY);
        if (row.deleteButton != null) CompactOverlayButton.renderStyled(graphics, this.font, row.deleteButton, mouseX, mouseY);
    }

    private void renderPreview(GuiGraphicsExtractor graphics, int x, int mouseX, int mouseY, float delta) {
        AutismAccount account = previewAccount();
        drawText(graphics, "Skin preview", x + 12, 31, TEXT, false);
        if (account == null) {
            drawText(graphics, "No account selected", x + 16, 78, MUTED, false, PREVIEW_WIDTH - 32);
            drawText(graphics, "Click a row to preview it.", x + 16, 94, MUTED, false, PREVIEW_WIDTH - 32);
            return;
        }
        SkinLookup lookup = skinLookup(account);
        PlayerSkin skin = lookup.skin();
        int modelCenterX = x + PREVIEW_WIDTH / 2;
        int modelX0 = modelCenterX - 111;
        int modelY0 = TOP_PANEL_Y + 34;
        int modelX1 = modelCenterX + 111;
        int modelY1 = TOP_PANEL_Y + TOP_PANEL_HEIGHT - 8;
        render3dSkin(graphics, skin, modelX0, modelY0, modelX1, modelY1);
        if (lookup.loading()) drawText(graphics, "Loading skin...", x + 14, TOP_PANEL_Y + TOP_PANEL_HEIGHT - 20, WARN, false, PREVIEW_WIDTH - 28);
        String display = account.displayName().isBlank() ? "(unknown)" : account.displayName();
        boolean defaultAccount = isDefaultAccount(account);
        boolean active = defaultAccount ? isCurrentDefaultAccount() : isCurrentAccount(account);
        drawText(graphics, display, x + 12, TOP_PANEL_Y + TOP_PANEL_HEIGHT - 34, active ? SUCCESS : TEXT, false, PREVIEW_WIDTH - 24);
        drawText(graphics, defaultAccount ? "Default Minecraft" : account.type.name(), x + 12, TOP_PANEL_Y + TOP_PANEL_HEIGHT - 48, defaultAccount ? DEFAULT_COLOR : MUTED, false, PREVIEW_WIDTH - 24);
        if (active) drawText(graphics, "CURRENT", x + PREVIEW_WIDTH - 66, 31, SUCCESS, false, 54);
    }

    private void render3dSkin(GuiGraphicsExtractor graphics, PlayerSkin skin, int x0, int y0, int x1, int y1) {
        if (widePlayerModel == null || slimPlayerModel == null) return;
        PlayerModel model = skin.model().name().equalsIgnoreCase("slim") ? slimPlayerModel : widePlayerModel;
        float drawScale = AutismUiScale.getOverlayDrawScale();
        int scaledX0 = Math.round(x0 * drawScale);
        int scaledY0 = Math.round(y0 * drawScale);
        int scaledX1 = Math.round(x1 * drawScale);
        int scaledY1 = Math.round(y1 * drawScale);
        float scale = 0.97F * Math.max(1, scaledY1 - scaledY0) / 2.125F;
        graphics.skin(model, skin.body().texturePath(), scale, previewRotationX, currentPreviewRotationY(), -1.0625F, scaledX0, scaledY0, scaledX1, scaledY1);
    }

    private float currentPreviewRotationY() {
        if (previewDragging) return previewRotationY;
        return previewRotationY + (float) ((Blaze3D.getTime() - previewAutoRotationStartTime) * 18.0D);
    }

    private void drawHead(GuiGraphicsExtractor graphics, PlayerSkin skin, int x, int y, int size) {
        Identifier texture = skin.body().texturePath();
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 8, 8, size, size, 8, 8, 64, 64);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 40, 8, size, size, 8, 8, 64, 64);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MouseButtonEvent virtualEvent = virtualEvent(event);
        if (virtualEvent.button() != 0) return super.mouseClicked(virtualEvent, doubleClick);
        if (compactListLayout()) return super.mouseClicked(virtualEvent, doubleClick);
        CompactScrollbar.Metrics scrollbar = accountScrollbarMetrics(displayAccounts().size());
        if (scrollbar.hasScroll() && scrollbar.contains(virtualEvent.x(), virtualEvent.y())) {
            accountScrollbarDragging = true;
            accountScrollbarGrabOffset = scrollbar.overThumb(virtualEvent.x(), virtualEvent.y()) ? Math.max(0, (int) Math.round(virtualEvent.y()) - scrollbar.thumbY()) : scrollbar.thumbHeight() / 2;
            savedListScrollOffset = quantizeScrollOffset(CompactScrollbar.scrollFromThumb(scrollbar, virtualEvent.y(), accountScrollbarGrabOffset), ROW_HEIGHT, scrollbar.maxScroll());
            savedListScroll.jumpTo(savedListScrollOffset, scrollbar.maxScroll());
            rebuildButtons();
            clearInputFocus();
            return true;
        }
        if (isInPreview(virtualEvent.x(), virtualEvent.y())) {
            previewRotationY = currentPreviewRotationY();
            previewAutoRotationStartTime = Blaze3D.getTime();
            previewDragging = true;
            lastPreviewMouseX = virtualEvent.x();
            lastPreviewMouseY = virtualEvent.y();
            clearInputFocus();
            return true;
        }
        for (CompactOverlayButton button : buttons) {
            if (CompactOverlayButton.fireIfHit(button, virtualEvent.x(), virtualEvent.y(), virtualEvent.button())) return true;
        }
        for (AccountRow row : accountRows) {
            if (CompactOverlayButton.fireIfHit(row.deleteButton, virtualEvent.x(), virtualEvent.y(), virtualEvent.button())) return true;
            if (CompactOverlayButton.fireIfHit(row.loginButton, virtualEvent.x(), virtualEvent.y(), virtualEvent.button())) return true;
            if (virtualEvent.x() >= rowX() && virtualEvent.x() < rowRight() && virtualEvent.y() >= row.y && virtualEvent.y() < row.y + ROW_HEIGHT - 3) {
                selectedAccount = row.account();
                return true;
            }
        }
        clearInputFocus();
        return super.mouseClicked(virtualEvent, doubleClick);
    }

    private void toggleFilter(AutismAccountType filterType) {
        if (accountTypeFilters.contains(filterType)) accountTypeFilters.remove(filterType);
        else accountTypeFilters.add(filterType);
        savedListScrollOffset = 0;
        savedListScroll.jumpTo(0, 0);
        rebuildButtons();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (accountScrollbarDragging) {
            accountScrollbarDragging = false;
            return true;
        }
        if (previewDragging) {
            previewAutoRotationStartTime = Blaze3D.getTime();
            previewDragging = false;
            return true;
        }
        return super.mouseReleased(virtualEvent(event));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        MouseButtonEvent virtualEvent = virtualEvent(event);
        if (accountScrollbarDragging) {
            CompactScrollbar.Metrics scrollbar = accountScrollbarMetrics(displayAccounts().size());
            savedListScrollOffset = quantizeScrollOffset(CompactScrollbar.scrollFromThumb(scrollbar, virtualEvent.y(), accountScrollbarGrabOffset), ROW_HEIGHT, scrollbar.maxScroll());
            savedListScroll.jumpTo(savedListScrollOffset, scrollbar.maxScroll());
            rebuildButtons();
            return true;
        }
        if (previewDragging) {
            previewRotationX = Math.max(-50.0F, Math.min(50.0F, previewRotationX - (float) AutismUiScale.toVirtual(dy) * 2.5F));
            previewRotationY += (float) AutismUiScale.toVirtual(dx) * 2.5F;
            lastPreviewMouseX = virtualEvent.x();
            lastPreviewMouseY = virtualEvent.y();
            return true;
        }
        return super.mouseDragged(virtualEvent, AutismUiScale.toVirtual(dx), AutismUiScale.toVirtual(dy));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        x = AutismUiScale.toVirtual(x);
        y = AutismUiScale.toVirtual(y);
        if (compactListLayout()) return super.mouseScrolled(x, y, scrollX, scrollY);
        if (x < listX() || x >= listX() + listWidth() || y < listTop() || y >= listTop() + listPanelHeight()) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        int maxScroll = savedMaxScroll(displayAccounts().size());
        if (maxScroll <= 0) return true;
        int next = savedListScrollOffset - (int) Math.signum(scrollY) * ROW_HEIGHT;
        savedListScrollOffset = quantizeScrollOffset(next, ROW_HEIGHT, maxScroll);
        savedListScroll.setTarget(savedListScrollOffset, maxScroll);
        rebuildButtons();
        return true;
    }

    @Override
    public void onClose() {
        cancelOperation();
        accountScrollbarDragging = false;
        pruneSkinLookups();
        this.minecraft.setScreen(parent);
    }

    private SkinLookup skinLookup(AutismAccount account) {
        String key = skinKey(account);
        synchronized (skinLookups) {
            SkinLookup lookup = skinLookups.computeIfAbsent(key, ignored -> createSkinLookup(account));
            pruneSkinLookups();
            return lookup;
        }
    }

    private SkinLookup createSkinLookup(AutismAccount account) {
        UUID id = accountUuid(account);
        String name = account == null || account.displayName().isBlank() ? "Autism" : account.displayName();
        if (account != null && account.type == AutismAccountType.Cracked && !name.isBlank() && this.minecraft != null) {
            UUID offlineId = UUIDUtil.createOfflinePlayerUUID(name);
            PlayerSkin defaultSkin = DefaultPlayerSkin.get(offlineId);
            try {
                CompletableFuture<Optional<PlayerSkin>> future = CompletableFuture.supplyAsync(
                    () -> resolveCrackedSkinProfile(name),
                    Util.nonCriticalIoPool()
                ).thenCompose(profile -> profile.map(this.minecraft.getSkinManager()::get).orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
                return new SkinLookup(() -> {
                    try {
                        return future.getNow(Optional.empty()).orElse(defaultSkin);
                    } catch (Exception ignored) {
                        return defaultSkin;
                    }
                }, future, defaultSkin);
            } catch (Exception ignored) {
                return new SkinLookup(() -> defaultSkin, CompletableFuture.completedFuture(Optional.empty()), defaultSkin);
            }
        }

        if (account != null && !name.isBlank() && this.minecraft != null && isDefaultAccount(account)) {
            UUID offlineId = UUIDUtil.createOfflinePlayerUUID(name);
            PlayerSkin defaultSkin = DefaultPlayerSkin.get(id == null ? offlineId : id);
            ResolvableProfile profile = id == null ? ResolvableProfile.createUnresolved(name) : ResolvableProfile.createUnresolved(id);
            try {
                PlayerSkinRenderCache.RenderInfo defaultInfo = this.minecraft.playerSkinRenderCache().getOrDefault(profile);
                Supplier<PlayerSkinRenderCache.RenderInfo> lookup = this.minecraft.playerSkinRenderCache().createLookup(profile);
                CompletableFuture<Optional<PlayerSkinRenderCache.RenderInfo>> future = this.minecraft.playerSkinRenderCache().lookup(profile);
                return new SkinLookup(() -> {
                    try {
                        PlayerSkinRenderCache.RenderInfo info = lookup.get();
                        return info == null ? defaultSkin : info.playerSkin();
                    } catch (Exception ignored) {
                        return defaultSkin;
                    }
                }, future, defaultInfo.playerSkin());
            } catch (Exception ignored) {
                return new SkinLookup(() -> defaultSkin, CompletableFuture.completedFuture(Optional.empty()), defaultSkin);
            }
        }

        if (id == null) {
            UUID fallbackId = UUIDUtil.createOfflinePlayerUUID(name);
            PlayerSkin fallback = DefaultPlayerSkin.get(fallbackId);
            return new SkinLookup(() -> fallback, CompletableFuture.completedFuture(Optional.empty()), fallback);
        }

        try {
            PlayerSkin fallback = DefaultPlayerSkin.get(id);
            CompletableFuture<Optional<PlayerSkin>> future = CompletableFuture.supplyAsync(
                () -> this.minecraft.services().profileResolver().fetchById(id).orElse(new GameProfile(id, name)),
                Util.nonCriticalIoPool()
            ).thenCompose(profile -> this.minecraft.getSkinManager().get(profile));
            return new SkinLookup(() -> {
                try {
                    return future.getNow(Optional.empty()).orElse(fallback);
                } catch (Exception ignored) {
                    return fallback;
                }
            }, future, fallback);
        } catch (Exception ignored) {
            PlayerSkin fallback = DefaultPlayerSkin.get(id);
            return new SkinLookup(() -> fallback, CompletableFuture.completedFuture(Optional.empty()), fallback);
        }
    }

    private PlayerSkin fallbackSkin(AutismAccount account) {
        String name = account == null || account.displayName().isBlank() ? "Autism" : account.displayName();
        UUID id = accountUuid(account);
        if (id == null || account != null && account.type == AutismAccountType.Cracked) id = UUIDUtil.createOfflinePlayerUUID(name);
        return DefaultPlayerSkin.get(id);
    }

    private Optional<GameProfile> resolveCrackedSkinProfile(String name) {
        String username = safeTrim(name);
        if (!isValidMinecraftUsername(username) || this.minecraft == null) return Optional.empty();
        try {
            Optional<GameProfile> resolved = this.minecraft.services().profileResolver().fetchByName(username);
            if (resolved.isPresent() && resolved.get().properties().containsKey(TEXTURES_PROPERTY)) {
                return resolved;
            }
            Optional<GameProfile> withTextures = resolved.flatMap(this::withMojangTextures);
            if (withTextures.isPresent()) return withTextures;
        } catch (Exception ignored) {
        }
        return fetchMojangProfileByName(username);
    }

    private Optional<GameProfile> fetchMojangProfileByName(String username) {
        try {
            String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
            JsonObject profile = AutismHttp.getJson("https://api.mojang.com/users/profiles/minecraft/" + encoded, null);
            if (profile == null || !profile.has("id") || !profile.has("name")) return Optional.empty();
            UUID id = UndashedUuid.fromStringLenient(profile.get("id").getAsString());
            String resolvedName = profile.get("name").getAsString();
            return withMojangTextures(new GameProfile(id, resolvedName));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<GameProfile> withMojangTextures(GameProfile profile) {
        if (profile == null || profile.id() == null) return Optional.empty();
        if (profile.properties().containsKey(TEXTURES_PROPERTY)) return Optional.of(profile);
        try {
            String id = UndashedUuid.toString(profile.id());
            JsonObject textureProfile = AutismHttp.getJson("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false", null);
            if (textureProfile == null || !textureProfile.has("properties") || !textureProfile.get("properties").isJsonArray()) return Optional.empty();
            JsonArray properties = textureProfile.getAsJsonArray("properties");
            for (JsonElement element : properties) {
                if (!element.isJsonObject()) continue;
                JsonObject property = element.getAsJsonObject();
                String propertyName = jsonString(property, "name");
                String value = jsonString(property, "value");
                String signature = jsonString(property, "signature");
                if (!TEXTURES_PROPERTY.equals(propertyName) || value.isBlank() || signature.isBlank()) continue;
                String resolvedName = jsonString(textureProfile, "name");
                GameProfile texturedProfile = new GameProfile(profile.id(), resolvedName.isBlank() ? profile.name() : resolvedName);
                texturedProfile.properties().put(TEXTURES_PROPERTY, new Property(TEXTURES_PROPERTY, value, signature));
                return Optional.of(texturedProfile);
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static boolean isValidMinecraftUsername(String username) {
        if (username == null || username.length() < 3 || username.length() > 16) return false;
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            if ((c < 'A' || c > 'Z') && (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '_') return false;
        }
        return true;
    }

    private static String jsonString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) return "";
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private String skinKey(AutismAccount account) {
        if (account == null) return "empty";
        return (isDefaultAccount(account) ? "default" : account.type.name()) + ":" + safeTrim(account.username) + ":" + safeTrim(account.uuid) + ":" + safeTrim(account.label);
    }

    private void pruneSkinLookups() {
        synchronized (skinLookups) {
            if (skinLookups.size() <= MAX_SKIN_LOOKUPS) return;
            List<String> protectedKeys = protectedSkinKeys();
            Iterator<Map.Entry<String, SkinLookup>> iterator = skinLookups.entrySet().iterator();
            while (skinLookups.size() > MAX_SKIN_LOOKUPS && iterator.hasNext()) {
                Map.Entry<String, SkinLookup> entry = iterator.next();
                if (!protectedKeys.contains(entry.getKey())) iterator.remove();
            }
        }
    }

    private List<String> protectedSkinKeys() {
        List<String> keys = new ArrayList<>();
        AutismAccount preview = previewAccount();
        addProtectedSkinKey(keys, preview);
        for (AccountRow row : accountRows) addProtectedSkinKey(keys, row.account);
        AutismAccount defaultAccount = defaultMinecraftAccount();
        addProtectedSkinKey(keys, defaultAccount);
        return keys;
    }

    private void addProtectedSkinKey(List<String> keys, AutismAccount account) {
        if (keys == null || account == null || keys.size() >= MAX_SKIN_LOOKUPS) return;
        String key = skinKey(account);
        if (!keys.contains(key)) keys.add(key);
    }

    private List<AutismAccount> filteredAccounts() {
        List<AutismAccount> accounts = AutismAccountManager.get().all();
        String query = normalizeSearch(searchQuery);
        if (query.isEmpty() && accountTypeFilters.size() == AutismAccountType.values().length) return accounts;
        List<AutismAccount> filtered = new ArrayList<>();
        for (AutismAccount account : accounts) {
            if (account == null || !accountTypeFilters.contains(account.type)) continue;
            if (!query.isEmpty() && !matchesNickname(account, query)) continue;
            filtered.add(account);
        }
        return filtered;
    }

    private List<DisplayAccountRow> displayAccounts() {
        List<DisplayAccountRow> rows = new ArrayList<>();
        AutismAccount defaultAccount = defaultMinecraftAccount();
        if (defaultAccount != null) rows.add(new DisplayAccountRow(defaultAccount, true));
        for (AutismAccount account : filteredAccounts()) rows.add(new DisplayAccountRow(account, false));
        return rows;
    }

    private boolean matchesNickname(AutismAccount account, String query) {
        String name = normalizeSearch(account == null ? "" : account.displayName());
        if (query.isEmpty()) return true;
        if (name.contains(query)) return true;
        return fuzzyContains(name, query);
    }

    private static boolean fuzzyContains(String value, String query) {
        if (value == null || query == null || query.isEmpty()) return true;
        int valueIndex = 0;
        int queryIndex = 0;
        int misses = 0;
        int maxMisses = Math.max(1, query.length() / 3);
        while (valueIndex < value.length() && queryIndex < query.length()) {
            if (value.charAt(valueIndex) == query.charAt(queryIndex)) {
                queryIndex++;
            } else if (queryIndex > 0) {
                misses++;
            }
            if (misses > maxMisses) return false;
            valueIndex++;
        }
        return queryIndex == query.length();
    }

    private static String normalizeSearch(String value) {
        return safeTrim(value).toLowerCase(Locale.ROOT);
    }

    private UUID accountUuid(AutismAccount account) {
        if (account == null || account.uuid == null || account.uuid.isBlank()) return null;
        try {
            return UndashedUuid.fromStringLenient(account.uuid);
        } catch (Exception ignored) {
            try {
                return UUID.fromString(account.uuid);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private AutismAccount previewAccount() {
        if (selectedAccount != null) return selectedAccount;
        AutismAccount defaultAccount = defaultMinecraftAccount();
        if (defaultAccount != null && isCurrentDefaultAccount()) return defaultAccount;
        for (AutismAccount account : AutismAccountManager.get().all()) {
            if (isCurrentAccount(account)) return account;
        }
        List<AutismAccount> accounts = AutismAccountManager.get().all();
        if (defaultAccount != null) return defaultAccount;
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private boolean isCurrentAccount(AutismAccount account) {
        if (account == null || this.minecraft == null || this.minecraft.getUser() == null) return false;
        String currentName = this.minecraft.getUser().getName();
        if (currentName != null && currentName.equalsIgnoreCase(account.username)) return true;
        UUID accountId = accountUuid(account);
        return accountId != null && accountId.equals(this.minecraft.getUser().getProfileId());
    }

    private boolean isCurrentDefaultAccount() {
        User original = AutismAccountSessionSwitcher.getOriginalUser();
        User current = this.minecraft == null ? null : this.minecraft.getUser();
        return original != null && current != null && original.getProfileId().equals(current.getProfileId()) && original.getName().equalsIgnoreCase(current.getName());
    }

    private boolean isDefaultAccount(AutismAccount account) {
        AutismAccount defaultAccount = defaultMinecraftAccount();
        return defaultAccount != null && account != null && defaultAccount.uuid.equals(account.uuid) && defaultAccount.username.equalsIgnoreCase(account.username) && account.label != null && account.label.startsWith("Default Minecraft:");
    }

    private AutismAccount defaultMinecraftAccount() {
        User user = AutismAccountSessionSwitcher.getOriginalUser();
        if (user == null) return null;
        AutismAccount account = new AutismAccount();
        account.type = safeTrim(user.getAccessToken()).isBlank() ? AutismAccountType.Cracked : AutismAccountType.Session;
        account.label = "Default Minecraft: " + user.getName();
        account.username = user.getName();
        account.uuid = user.getProfileId().toString();
        account.token = user.getAccessToken();
        return account;
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

    private int previewX() {
        return panelX() + 10 + FORM_WIDTH + PANEL_GAP;
    }

    private boolean isInPreview(double x, double y) {
        if (narrowLayout()) return false;
        int previewX = previewX();
        return x >= previewX && x < previewX + PREVIEW_WIDTH && y >= TOP_PANEL_Y && y < TOP_PANEL_Y + TOP_PANEL_HEIGHT;
    }

    private int listPanelHeight() {
        return Math.max(1, screenHeight() - listTop() - LIST_BOTTOM_MARGIN);
    }

    private int listTop() {
        return narrowLayout() ? 48 : LIST_TOP;
    }

    private int listRowTop() {
        return listTop() + LIST_HEADER_HEIGHT;
    }

    private int savedRowsTop() {
        return listRowTop();
    }

    private int savedRowsBottom() {
        return listTop() + listPanelHeight() - 6;
    }

    private int savedViewportHeight() {
        return Math.max(ROW_HEIGHT, alignViewportHeight(Math.max(1, savedRowsBottom() - savedRowsTop()), ROW_HEIGHT));
    }

    private int savedViewportRows() {
        return Math.max(1, savedViewportHeight() / ROW_HEIGHT);
    }

    private int savedMaxScroll(int savedRows) {
        return Math.max(0, savedRows * ROW_HEIGHT - savedViewportHeight());
    }

    private CompactScrollbar.Metrics accountScrollbarMetrics(int savedRows) {
        int contentPixels = Math.max(0, savedRows) * ROW_HEIGHT;
        int viewPixels = savedViewportHeight();
        int trackX = listX() + listWidth() - 8;
        int trackY = savedRowsTop();
        int trackHeight = savedViewportHeight();
        return CompactScrollbar.compute(contentPixels, viewPixels, trackX, trackY, 4, trackHeight, savedListScroll.tick(0.0f, savedMaxScroll(savedRows)));
    }

    private int rowX() {
        return listX() + 8;
    }

    private int rowRight() {
        return listX() + listWidth() - 8 - LIST_SCROLLBAR_GUTTER;
    }

    private int rowWidth() {
        return Math.max(1, rowRight() - rowX());
    }

    private int rowVisualHeight() {
        return ROW_HEIGHT - 2;
    }

    private int rowButtonY(int rowY, int buttonHeight) {
        return rowY + Math.max(1, (rowVisualHeight() - buttonHeight) / 2);
    }

    private boolean narrowLayout() {
        return panelWidth() < PANEL_WIDTH || screenHeight() < LIST_TOP + 44;
    }

    private boolean compactListLayout() {
        return listWidth() < 190 || listPanelHeight() < LIST_HEADER_HEIGHT + ROW_HEIGHT;
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

    private void clearInputFocus() {
        if (labelField != null) labelField.setFocused(false);
        if (tokenField != null) tokenField.setFocused(false);
        if (searchField != null) searchField.setFocused(false);
        this.setFocused(null);
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

    private String inputLabel() {
        return switch (type) {
            case Cracked -> "Cracked username";
            case TheAltening -> "TheAltening token";
            case Session -> "Session access token";
            case Microsoft -> "";
        };
    }

    private String addButtonLabel() {
        return switch (type) {
            case Cracked -> "Add Cracked";
            case TheAltening -> "Add Altening";
            case Session -> "Add Session";
            case Microsoft -> "Login with Microsoft";
        };
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private enum Operation {
        NONE,
        ADD,
        LOGIN,
        MICROSOFT
    }

    private record AccountRow(AutismAccount account, int y, CompactOverlayButton loginButton, CompactOverlayButton deleteButton, boolean defaultAccount, boolean loadSkin) {
    }

    private record DisplayAccountRow(AutismAccount account, boolean defaultAccount) {
    }

    private record SkinLookup(Supplier<PlayerSkin> supplier, CompletableFuture<?> future, PlayerSkin fallback) {
        private PlayerSkin skin() {
            try {
                PlayerSkin skin = supplier.get();
                return skin == null ? fallback : skin;
            } catch (Exception ignored) {
                return fallback;
            }
        }

        private boolean loading() {
            return future != null && !future.isDone();
        }
    }
}
