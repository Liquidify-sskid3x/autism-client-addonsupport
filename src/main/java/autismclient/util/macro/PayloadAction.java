package autismclient.util.macro;

import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismPayloadJsonSupport;
import autismclient.util.AutismPayloadScriptExecutor;
import autismclient.util.AutismPayloadSupport;
import autismclient.util.AutismPayloadTemplate;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

public class PayloadAction implements MacroAction {
    public String channel = "minecraft:brand";
    public String payloadData = "";
    public String payloadJson = "";
    public String payloadClassName = "";
    public String javaSource = "";
    public boolean commandApiRecognized = false;
    public boolean commandApiOverride = false;
    public int commandApiValue = Integer.MAX_VALUE - 8;
    public String sourceDirection = "C2S";
    public String sourceProtocol = "";
    public String payloadDirection = "C2S";
    public String payloadPhase = "PLAY";
    public String payloadEncodingMode = "";
    public String payloadFields = "";
    public int payloadPacketId = -1;
    public String payloadProvenance = "userEdited";
    public boolean payloadScriptEnabled = false;
    private boolean enabled = true;

    @Override
    public void execute(Minecraft mc) {
        if (!enabled) return;
        if (mc == null || mc.getConnection() == null) {
            AutismClientMessaging.sendPrefixed("§cCannot send payload while disconnected.");
            return;
        }

        try {
            boolean hasRawPayload = payloadData != null && !payloadData.isBlank();
            boolean hasStructuredPayload = (payloadFields != null && !payloadFields.isBlank())
                || (payloadEncodingMode != null && !payloadEncodingMode.isBlank());
            boolean useJsonModel = !hasRawPayload
                && !hasStructuredPayload
                && payloadJson != null && !payloadJson.isBlank()
                && payloadClassName != null && !payloadClassName.isBlank();
            String targetChannel = channel;
            byte[] rawBytes;
            if (hasStructuredPayload) {
                AutismPayloadTemplate.Template template = AutismPayloadTemplate.fromAction(this);
                AutismPayloadTemplate.BuildResult built = template.build();
                if (!built.ok()) {
                    throw new IllegalArgumentException(String.join("; ", built.errors()));
                }
                targetChannel = template.channel();
                rawBytes = built.bytes();
            } else if (useJsonModel) {
                AutismPayloadJsonSupport.EncodedPayload encoded = AutismPayloadJsonSupport.encodeAction(this);
                targetChannel = encoded.channel();
                rawBytes = encoded.bytes();
            } else {
                rawBytes = AutismPayloadSupport.parsePayloadBytes(payloadData);
            }
            if (!useJsonModel && !hasStructuredPayload && !hasRawPayload && AutismPayloadSupport.isBrandChannel(targetChannel)) {
                rawBytes = AutismPayloadSupport.encodeMinecraftStringPayload(
                    AutismPayloadSupport.defaultBrandPayloadString());
            }
            if (!useJsonModel && !hasStructuredPayload && commandApiRecognized && commandApiOverride) {
                rawBytes = AutismPayloadSupport.withCommandApiValue(rawBytes, commandApiValue);
            }
            AutismPayloadScriptExecutor.Context context = new AutismPayloadScriptExecutor.Context(targetChannel, rawBytes,
                !useJsonModel && commandApiRecognized && commandApiOverride ? commandApiValue : null);
            AutismPayloadScriptExecutor.ScriptResult result = AutismPayloadScriptExecutor.execute(
                payloadScriptEnabled ? javaSource : "", context);
            if (AutismPayloadSupport.sendPayload(result.channel(), result.bytes(), sourceProtocol)) {
                AutismClientMessaging.sendPrefixed("Sent payload: " + result.channel());
            }
        } catch (Exception e) {
            AutismClientMessaging.sendPrefixed("§cPayload action failed: " + AutismPayloadSupport.safeMessage(e));
        }
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", getType().name());
        tag.putBoolean("enabled", enabled);
        tag.putString("channel", channel == null ? "" : channel);
        tag.putString("payloadData", payloadData == null ? "" : payloadData);
        tag.putString("payloadJson", payloadJson == null ? "" : payloadJson);
        tag.putString("payloadClassName", payloadClassName == null ? "" : payloadClassName);
        tag.putString("javaSource", javaSource == null ? "" : javaSource);
        tag.putBoolean("payloadScriptEnabled", payloadScriptEnabled);
        tag.putBoolean("commandApiRecognized", commandApiRecognized);
        tag.putBoolean("commandApiOverride", commandApiOverride);
        tag.putInt("commandApiValue", commandApiValue);
        tag.putString("sourceDirection", sourceDirection == null ? "" : sourceDirection);
        tag.putString("sourceProtocol", sourceProtocol == null ? "" : sourceProtocol);
        tag.putString("payloadDirection", payloadDirection == null ? "" : payloadDirection);
        tag.putString("payloadPhase", payloadPhase == null ? "" : payloadPhase);
        tag.putString("payloadEncodingMode", payloadEncodingMode == null ? "" : payloadEncodingMode);
        tag.putString("payloadFields", payloadFields == null ? "" : payloadFields);
        tag.putInt("payloadPacketId", payloadPacketId);
        tag.putString("payloadProvenance", payloadProvenance == null ? "" : payloadProvenance);
        return tag;
    }

    @Override
    public void fromTag(CompoundTag tag) {
        enabled = tag.getBooleanOr("enabled", true);
        channel = tag.getStringOr("channel", "minecraft:brand");
        payloadData = tag.getStringOr("payloadData", "");
        payloadJson = tag.getStringOr("payloadJson", "");
        payloadClassName = tag.getStringOr("payloadClassName", "");
        javaSource = tag.getStringOr("javaSource", "");
        payloadScriptEnabled = tag.getBooleanOr("payloadScriptEnabled", false);
        commandApiRecognized = tag.getBooleanOr("commandApiRecognized", false);
        commandApiOverride = tag.getBooleanOr("commandApiOverride", false);
        commandApiValue = tag.getIntOr("commandApiValue", Integer.MAX_VALUE - 8);
        sourceDirection = tag.getStringOr("sourceDirection", "C2S");
        sourceProtocol = tag.getStringOr("sourceProtocol", "");
        payloadDirection = tag.getStringOr("payloadDirection", sourceDirection == null || sourceDirection.isBlank() ? "C2S" : sourceDirection);
        payloadPhase = tag.getStringOr("payloadPhase", sourceProtocol != null && sourceProtocol.toLowerCase(java.util.Locale.ROOT).contains("configuration") ? "CONFIGURATION" : "PLAY");
        payloadEncodingMode = tag.getStringOr("payloadEncodingMode", "");
        payloadFields = tag.getStringOr("payloadFields", "");
        payloadPacketId = tag.getIntOr("payloadPacketId", -1);
        payloadProvenance = tag.getStringOr("payloadProvenance", "userEdited");
    }

    @Override
    public MacroActionType getType() {
        return MacroActionType.PAYLOAD;
    }

    @Override
    public String getDisplayName() {
        if (channel == null || channel.isBlank()) return "Payload";
        return "Payload - " + channel;
    }

    @Override
    public String getIcon() {
        return "network";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
