package autismclient.util.macro;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

public class WaitForPositionDeltaAction implements MacroAction {
    public double distance = 5.0;
    public boolean horizontalOnly = false;
    public boolean listenDuringPreviousAction = false;
    private boolean enabled = true;

    @Override
    public void execute(Minecraft mc) {
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", getType().name());
        tag.putDouble("distance", distance);
        tag.putBoolean("horizontalOnly", horizontalOnly);
        tag.putBoolean("enabled", enabled);
        MacroWaitOptions.write(tag, this);
        return tag;
    }

    @Override
    public void fromTag(CompoundTag tag) {
        distance = Math.max(0.0, tag.getDoubleOr("distance", 5.0));
        horizontalOnly = tag.getBooleanOr("horizontalOnly", false);
        if (tag.contains("enabled")) enabled = tag.getBooleanOr("enabled", true);
        MacroWaitOptions.read(tag, this);
    }

    @Override
    public MacroActionType getType() {
        return MacroActionType.WAIT_POSITION_DELTA;
    }

    @Override
    public String getDisplayName() {
        return "Wait Position Delta: " + String.format(java.util.Locale.ROOT, "%.1f", distance);
    }

    @Override
    public String getIcon() {
        return "DPos";
    }

    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean e) { enabled = e; }
}
