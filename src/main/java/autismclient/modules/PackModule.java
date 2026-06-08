package autismclient.modules;

import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public abstract class PackModule {
    protected static final Minecraft MC = Minecraft.getInstance();

    private final String id;
    private final String name;
    private final PackModuleCategory category;
    private final String description;
    private final List<PackModuleOption> options = new ArrayList<>();
    private String replacementToggleMessage;

    protected PackModule(String id, String name, PackModuleCategory category, String description) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
    }

    public final String id() {
        return id;
    }

    public final String name() {
        return name;
    }

    public final PackModuleCategory category() {
        return category;
    }

    public final String description() {
        return description;
    }

    public final boolean isEnabled() {
        return state().enabled;
    }

    public final void setEnabled(boolean enabled) {
        if (enabled && PackHideState.blocksEnable(this)) return;
        boolean wasEnabled = isEnabled();
        if (wasEnabled == enabled) {
            replacementToggleMessage = null;
            return;
        }
        state().enabled = enabled;
        if (enabled) onEnable();
        else onDisable();
        PackModuleRegistry.markModuleEnabledChanged();
        save();
        String message = replacementToggleMessage;
        replacementToggleMessage = null;
        if (emitsToggleMessage() && !PackHideState.isSilenced()) {
            AutismClientMessaging.sendPrefixed(message == null || message.isBlank()
                ? name + ": " + (isEnabled() ? "enabled" : "disabled")
                : message);
        }
    }

    protected final void setEnabledSilently(boolean enabled) {
        if (enabled && PackHideState.blocksEnable(this)) return;
        boolean wasEnabled = isEnabled();
        if (wasEnabled == enabled) return;
        state().enabled = enabled;
        if (enabled) onEnable();
        else onDisable();
        PackModuleRegistry.markModuleEnabledChanged();
        save();
    }

    protected final void replaceNextToggleMessage(String message) {
        replacementToggleMessage = message;
    }

    protected final void disableSilentlyWithToggleMessage(String message) {
        replaceNextToggleMessage(message);
        setEnabledSilently(false);
    }

    protected final void disableWithToggleMessage(String message) {
        replaceNextToggleMessage(message);
        setEnabled(false);
    }

    public final void toggle() {
        setEnabled(!isEnabled());
    }

    public final int keybind() {
        return state().keybind;
    }

    public final void setKeybind(int keybind) {
        state().keybind = keybind;
        PackModuleRegistry.markModuleSettingsChanged();
        save();
    }

    public final List<PackModuleOption> options() {
        return Collections.unmodifiableList(options);
    }

    public final List<PackModuleOption> visibleOptions() {
        List<PackModuleOption> visible = new ArrayList<>();
        for (PackModuleOption option : options) {
            if (option.isVisible(this)) visible.add(option);
        }
        return visible;
    }

    protected final void option(PackModuleOption option) {
        options.add(option);
        state().settings.putIfAbsent(option.id(), option.defaultValue());
        PackModuleRegistry.markModuleSettingsChanged();
    }

    public String info() {
        return "";
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    public void tick() {
    }

    public boolean ticksWhenDisabled() {
        return false;
    }

    public boolean opensSettingsOnClick() {
        return false;
    }

    public boolean hasActivationToggle() {
        return true;
    }

    public boolean showInModuleMenu() {
        return true;
    }

    public boolean emitsToggleMessage() {
        return true;
    }

    public void preMovementTick() {
    }

    public void onRenderLevel(float partialTick) {
    }

    public void onMouseRotation(double deltaYaw, double deltaPitch) {
    }

    public Vec3 onPlayerMove(MoverType type, Vec3 movement) {
        return movement;
    }

    public boolean shouldApplySpeedTimer() {
        return false;
    }

    public void onGameJoin() {
    }

    public void onGameLeft() {
    }

    public boolean onPacketSend(Packet<?> packet) {
        return false;
    }

    public boolean onPacketReceive(Packet<?> packet) {
        return false;
    }

    public void onSoundPacket(ClientboundSoundPacket packet) {
    }

    public void appendTooltip(ItemStack stack, List<?> lines) {
    }

    public boolean shouldCancelAttack(HitResult hitResult) {
        return false;
    }

    public boolean shouldCancelUse(HitResult hitResult, InteractionHand hand) {
        return false;
    }

    public void onStartBreakingBlock(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction direction) {
    }

    public void onBlockBreakingProgress(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction direction) {
    }

    public boolean shouldCancelStartBreakingBlock(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction direction) {
        return false;
    }

    public boolean shouldTraceEntity(Entity entity) {
        return false;
    }

    public int traceColor(Entity entity) {
        return 0x80FFFFFF;
    }

    protected final boolean bool(String optionId) {
        return Boolean.parseBoolean(value(optionId));
    }

    protected final int integer(String optionId) {
        try {
            return Integer.parseInt(value(optionId));
        } catch (NumberFormatException ignored) {
            PackModuleOption option = option(optionId);
            return option == null ? 0 : Integer.parseInt(option.defaultValue());
        }
    }

    protected final double decimal(String optionId) {
        try {
            return Double.parseDouble(value(optionId));
        } catch (NumberFormatException ignored) {
            PackModuleOption option = option(optionId);
            return option == null ? 0.0 : Double.parseDouble(option.defaultValue());
        }
    }

    protected final String text(String optionId) {
        return value(optionId);
    }

    protected final String choice(String optionId) {
        return value(optionId);
    }

    protected final List<String> list(String optionId) {
        String value = value(optionId);
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : value.split("\\|")) {
            String item = raw.trim();
            if (!item.isEmpty()) out.add(item);
        }
        return out;
    }

    public final String value(String optionId) {
        PackModuleOption option = option(optionId);
        String fallback = option == null ? "" : option.defaultValue();
        return state().settings.getOrDefault(optionId, fallback);
    }

    public final void setValue(String optionId, String value) {
        PackModuleOption option = option(optionId);
        state().settings.put(optionId, sanitizeValue(option, value));
        onOptionValueChanged(optionId);
        PackModuleRegistry.markModuleSettingsChanged();
        save();
    }

    public final void resetValue(String optionId) {
        PackModuleOption option = option(optionId);
        if (option == null) return;
        state().settings.put(optionId, option.defaultValue());
        PackModuleRegistry.markModuleSettingsChanged();
        save();
    }

    public final void resetSettings() {
        for (PackModuleOption option : options) {
            state().settings.put(option.id(), option.defaultValue());
        }
        onSettingsReset();
        PackModuleRegistry.markModuleSettingsChanged();
        save();
    }

    protected void onSettingsReset() {
    }

    protected void onOptionValueChanged(String optionId) {
    }

    public final void adjustOption(PackModuleOption option, int direction) {
        if (option == null) return;
        switch (option.type()) {
            case BOOLEAN -> setValue(option.id(), Boolean.toString(!bool(option.id())));
            case INTEGER -> {
                int value = integer(option.id());
                int adjusted = (int) clamp(value + (int) option.step() * direction, option.min(), option.max());
                setValue(option.id(), Integer.toString(adjusted));
            }
            case DOUBLE -> {
                double value = decimal(option.id());
                double adjusted = clamp(value + option.step() * direction, option.min(), option.max());
                setValue(option.id(), String.format(Locale.ROOT, "%.2f", adjusted));
            }
            case ENUM -> {
                List<String> choices = option.choices();
                if (!choices.isEmpty()) {
                    int index = choices.indexOf(value(option.id()));
                    if (index < 0) index = 0;
                    int next = Math.floorMod(index + direction, choices.size());
                    setValue(option.id(), choices.get(next));
                }
            }
            case ACTION -> {
                if (option.action() != null) option.action().run();
            }
            case STRING, STRING_LIST, PACKET_LIST, ITEM_LIST, BLOCK_LIST, ENTITY_TYPE_LIST, SOUND_EVENT_LIST, STORAGE_LIST, COLOR, KEYBIND -> {
            }
        }
    }

    public final String displayValue(PackModuleOption option) {
        if (option == null) return "";
        String override = displayValueOverride(option);
        if (override != null) return override;
        return switch (option.type()) {
            case BOOLEAN -> bool(option.id()) ? "ON" : "OFF";
            case INTEGER, DOUBLE, ENUM, STRING, STRING_LIST, PACKET_LIST, ITEM_LIST, BLOCK_LIST, ENTITY_TYPE_LIST, SOUND_EVENT_LIST, STORAGE_LIST, COLOR, KEYBIND -> option.format(value(option.id()));
            case ACTION -> "RUN";
        };
    }

    protected String displayValueOverride(PackModuleOption option) {
        return null;
    }

    private String sanitizeValue(PackModuleOption option, String value) {
        if (option == null) return value == null ? "" : value;
        String safe = value == null ? option.defaultValue() : value;
        try {
            return switch (option.type()) {
                case BOOLEAN -> Boolean.toString(Boolean.parseBoolean(safe));
                case INTEGER -> Integer.toString((int) clamp(Integer.parseInt(safe), option.min(), option.max()));
                case DOUBLE -> Double.toString(clamp(Double.parseDouble(safe), option.min(), option.max()));
                case ENUM -> option.choices().isEmpty() || option.choices().contains(safe) ? safe : option.defaultValue();
                case STRING, STRING_LIST, PACKET_LIST, ITEM_LIST, BLOCK_LIST, ENTITY_TYPE_LIST, SOUND_EVENT_LIST, STORAGE_LIST, COLOR, KEYBIND -> safe;
                case ACTION -> option.defaultValue();
            };
        } catch (Exception ignored) {
            return option.defaultValue();
        }
    }

    public final PackModuleOption option(String optionId) {
        for (PackModuleOption option : options) {
            if (option.id().equals(optionId)) return option;
        }
        return null;
    }

    protected final boolean isAdminContext() {
        return MC != null && MC.player != null && MC.player.canUseGameMasterBlocks();
    }

    protected final boolean isCreativeContext() {
        return MC != null && MC.player != null && MC.player.hasInfiniteMaterials();
    }

    protected final void sendCommand(String command) {
        if (MC == null || MC.getConnection() == null || command == null || command.isBlank()) return;
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        MC.getConnection().sendCommand(normalized);
    }

    private AutismConfig.ModuleState state() {
        AutismConfig config = AutismConfig.getGlobal();
        return config.modules.computeIfAbsent(id, ignored -> new AutismConfig.ModuleState());
    }

    private void save() {
        AutismConfig.getGlobal().save();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
