package autismclient.util.macro;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

public class WaitForTeleportAction implements MacroAction {
    public double minDistance = 0.0;
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
        tag.putDouble("minDistance", minDistance);
        tag.putBoolean("horizontalOnly", horizontalOnly);
        tag.putBoolean("enabled", enabled);
        MacroWaitOptions.write(tag, this);
        return tag;
    }

    @Override
    public void fromTag(CompoundTag tag) {
        minDistance = Math.max(0.0, tag.getDoubleOr("minDistance", 0.0));
        horizontalOnly = tag.getBooleanOr("horizontalOnly", false);
        if (tag.contains("enabled")) enabled = tag.getBooleanOr("enabled", true);
        MacroWaitOptions.read(tag, this);
    }

    @Override
    public MacroActionType getType() {
        return MacroActionType.WAIT_TELEPORT;
    }

    @Override
    public String getDisplayName() {
        return minDistance <= 0.0
            ? "Wait Teleport"
            : "Wait Teleport: " + String.format(java.util.Locale.ROOT, "%.1f", minDistance);
    }

    @Override
    public String getIcon() {
        return "TP";
    }

    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean e) { enabled = e; }
}
