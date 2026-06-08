package autismclient.util;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class AutismPacketInspector {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Object INACCESSIBLE = new Object();
    private static final Pattern SIMPLE_CLASS_ALIAS_PATTERN = Pattern.compile("(?<![\\w$.])(class_\\d+)(?=[\\[{(:\\s]|$)");
    private static final Pattern QUALIFIED_CLASS_ALIAS_PATTERN = Pattern.compile("(net\\.minecraft(?:\\.[\\w$]+)*\\.class_\\d+)(?=[\\[{(:\\s]|$)");
    private static final int MAX_DEPTH = 7;
    private static final int MAX_LINES = 900;
    private static final int MAX_COLLECTION_ITEMS = 64;
    private static final int MAX_ARRAY_ITEMS = 64;
    private static final int MAX_STRING_LENGTH = 320;
    private static final int MAX_GENERIC_SUMMARY_LINES = 8;
    private static final Set<String> IGNORED_PROPERTY_METHODS = Set.of(
        "getClass",
        "getPacketType",
        "toString",
        "hashCode",
        "copy",
        "apply",
        "write",
        "read",
        "streamCodec",
        "codec",
        "isWritingErrorSkippable",
        "writingErrorSkippable",
        "WritingErrorSkippable"
    );
    private static final Set<String> IGNORED_PROPERTY_NORMALIZED = Set.of(
        "getclass",
        "getpackettype",
        "tostring",
        "hashcode",
        "copy",
        "apply",
        "write",
        "read",
        "streamcodec",
        "codec",
        "iswritingerrorskippable",
        "writingerrorskippable"
    );
    private static final Set<String> EXTRA_ACCESSOR_NAMES = Set.of(
        "id",
        "syncId",
        "revision",
        "slot",
        "contents",
        "cursorStack",
        "trackedValues",
        "values",
        "reason",
        "value",
        "hand",
        "sequence",
        "yaw",
        "pitch",
        "chunkX",
        "chunkZ",
        "chunkData",
        "lightData",
        "sound",
        "category",
        "x",
        "y",
        "z",
        "onGround",
        "name",
        "screenHandlerType",
        "type",
        "state",
        "mode",
        "seed"
    );
    private AutismPacketInspector() {
    }

    public static PacketInspection inspectSafe(AutismPacketLoggerOverlay.LogEntry entry) {
        try {
            return inspect(entry);
        } catch (Throwable t) {
            InspectionBuilder builder = new InspectionBuilder(entry.shortName + " [" + entry.direction + "]");
            builder.section("Meta", AutismColors.packetLightYellow());
            builder.line("Name: " + entry.shortName, AutismColors.packetWhite());
            builder.line("Direction: " + entry.direction, directionColor(entry.direction));
            builder.line("Class: " + entry.packetClass.getName(), AutismColors.textSecondary());
            builder.line("Tick: " + entry.gameTick, AutismColors.textSecondary());
            builder.line("Time: " + Instant.ofEpochMilli(entry.timestampMs), AutismColors.textSecondary());
            builder.blank();
            builder.section("Inspect Error", AutismColors.dangerText());
            builder.line("The detailed inspector failed on this packet, so this fallback view was opened instead.", AutismColors.dangerText());
            builder.line("Reason: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " - " + shorten(t.getMessage())),
                AutismColors.dangerText());
            if (entry.packetRef != null) {
                builder.blank();
                builder.section("Fallback", AutismColors.packetYellow());
                builder.line("Packet Type: " + safeLeafString(invokeNoArg(entry.packetRef, "getPacketType")), AutismColors.textSecondary());
                builder.line("toString(): " + shorten(String.valueOf(entry.packetRef)), AutismColors.textMuted());
            }
            return builder.build();
        }
    }

    public static PacketInspection inspect(AutismPacketLoggerOverlay.LogEntry entry) {
        String title = entry.shortName + " [" + entry.direction + "]";
        InspectionBuilder builder = new InspectionBuilder(title);

        builder.section("Meta", AutismColors.packetLightYellow());
        builder.line("Name: " + entry.shortName, AutismColors.packetWhite());
        builder.line("Direction: " + entry.direction, directionColor(entry.direction));
        builder.line("Class: " + entry.packetClass.getName(), AutismColors.textSecondary());
        Object packetType = invokeNoArg(entry.packetRef, "getPacketType");
        if (packetType != null) {
            builder.line("Packet Type: " + safeLeafString(packetType), AutismColors.textSecondary());
        }
        builder.line("Tick: " + entry.gameTick, AutismColors.textSecondary());
        builder.line("Time: " + Instant.ofEpochMilli(entry.timestampMs), AutismColors.textSecondary());
        builder.line("Inventory: " + entry.isInventory + " | Movement: " + entry.isMovement,
            AutismColors.textMuted());
        AutismPacketCapture.PacketSnapshot encodedSnapshot = AutismPacketCapture.snapshot(entry.packetRef);
        if (encodedSnapshot != null) {
            builder.line("Protocol: " + encodedSnapshot.protocolPhase(), AutismColors.textSecondary());
            if (encodedSnapshot.numericPacketId() >= 0) {
                builder.line("Packet ID: " + encodedSnapshot.numericPacketId(), AutismColors.textSecondary());
            }
            byte[] plaintext = encodedSnapshot.plaintextBytes();
            builder.line("Plaintext bytes: " + plaintext.length + "B "
                + AutismPacketCapture.compactHex(plaintext, 32), AutismColors.textMuted());
        }

        if (entry.packetRef == null) {
            builder.blank();
            builder.section("Summary", AutismColors.packetCyan());
            builder.line("Packet instance is missing, so only metadata is available.", AutismColors.dangerText());
            return builder.build();
        }

        InspectionBuilder summaryBuilder = new InspectionBuilder(title);
        SummaryResult summary = appendSummary(entry.packetRef, entry.shortName, entry, summaryBuilder);
        if (summary.wroteAny()) {
            builder.blank();
            builder.section("Summary", AutismColors.packetCyan());
            builder.appendFrom(summaryBuilder);
        }

        if (!summary.complete()) {
            builder.blank();
            builder.section("Details", AutismColors.packetBlue());
            dumpRootFields(entry.packetRef, builder, new IdentityHashMap<>());
        }
        return builder.build();
    }

    private static SummaryResult appendSummary(Packet<?> packet, String packetNameHint, AutismPacketLoggerOverlay.LogEntry entry,
                                               InspectionBuilder builder) {
        SummaryResult packetSpecific = appendPacketSpecificSummary(packet, packetNameHint, entry, builder);
        if (packetSpecific.wroteAny()) {
            return packetSpecific;
        }
        return appendGenericSummary(packet, builder);
    }

    private static SummaryResult appendPacketSpecificSummary(Packet<?> packet, String packetNameHint,
                                                             AutismPacketLoggerOverlay.LogEntry entry,
                                                             InspectionBuilder builder) {
        boolean wrote = false;
        boolean complete = false;

        if (packet instanceof ServerboundPlayerActionPacket playerAction) {
            wrote = true;
            complete = true;
            builder.line("Action: " + playerAction.getAction(), AutismColors.textPrimary());
            builder.line("Meaning: " + describePlayerAction(playerAction.getAction()), AutismColors.textPrimary());
            builder.line("Block Pos: " + formatBlockPos(playerAction.getPos()), AutismColors.textPrimary());
            builder.line("Face: " + playerAction.getDirection(), AutismColors.textPrimary());
            builder.line("Sequence: " + playerAction.getSequence(), AutismColors.textSecondary());
            String capturedBlockState = entry == null ? null : entry.capturedBlockState;
            if (capturedBlockState != null && !capturedBlockState.isBlank()) {
                builder.line("Block: " + capturedBlockState, AutismColors.successText());
            } else {
                BlockState state = getWorldBlockState(playerAction.getPos());
                if (state != null) {
                    builder.line("Block: " + formatBlockState(state), AutismColors.successText());
                }
            }
            if (MC.player != null && (playerAction.getAction() == ServerboundPlayerActionPacket.Action.DROP_ITEM
                || playerAction.getAction() == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
                || playerAction.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM)) {
                builder.line("Held Item: " + formatItemStack(MC.player.getMainHandItem()), AutismColors.successText());
            }
        }

        if (packet instanceof ServerboundUseItemOnPacket interactBlock) {
            BlockHitResult hit = interactBlock.getHitResult();
            wrote = true;
            complete = true;
            builder.line("Interaction: Use Block (right click targeted block)", AutismColors.textPrimary());
            builder.line("Hand: " + interactBlock.getHand(), AutismColors.textPrimary());
            builder.line("Block Pos: " + formatBlockPos(hit.getBlockPos()), AutismColors.textPrimary());
            builder.line("Side: " + hit.getDirection(), AutismColors.textPrimary());
            builder.line("Hit Pos: " + formatVec3(hit.getLocation()), AutismColors.textPrimary());
            builder.line("Inside Block: " + hit.isInside(), AutismColors.textPrimary());
            builder.line("Sequence: " + interactBlock.getSequence(), AutismColors.textSecondary());
            if (MC.player != null) {
                builder.line("Held Item: " + formatItemStack(MC.player.getItemInHand(interactBlock.getHand())), AutismColors.successText());
            }
            String capturedBlockState = entry == null ? null : entry.capturedBlockState;
            if (capturedBlockState != null && !capturedBlockState.isBlank()) {
                builder.line("Block: " + capturedBlockState, AutismColors.successText());
            } else {
                BlockState state = getWorldBlockState(hit.getBlockPos());
                if (state != null) {
                    builder.line("Block: " + formatBlockState(state), AutismColors.successText());
                }
            }
        }

        if (packet instanceof ServerboundUseItemPacket interactItem) {
            wrote = true;
            complete = true;
            builder.line("Interaction: Use Item (right click without a targeted block)", AutismColors.textPrimary());
            builder.line("Hand: " + interactItem.getHand(), AutismColors.textPrimary());
            builder.line("Sequence: " + interactItem.getSequence(), AutismColors.textSecondary());
            builder.line("Yaw: " + interactItem.getYRot(), AutismColors.textSecondary());
            builder.line("Pitch: " + interactItem.getXRot(), AutismColors.textSecondary());
            if (MC.player != null) {
                Object selectedSlot = invokeFirstNoArg(MC.player.getInventory(), "getSelectedSlot", "selectedSlot");
                if (selectedSlot instanceof Number number) {
                    builder.line("Selected Slot: " + formatHotbarSlot(number.intValue()), AutismColors.textPrimary());
                }
                builder.line("Held Item: " + formatItemStack(MC.player.getItemInHand(interactItem.getHand())), AutismColors.successText());
            }
        }

        if (packet instanceof ServerboundInteractPacket interactEntity) {
            wrote = true;
            complete = true;
            int entityId = interactEntity.entityId();
            appendEntityIdLine(builder, "Entity Id", entityId);
            builder.line("Player Sneaking: " + interactEntity.usingSecondaryAction(), AutismColors.textPrimary());
            InteractionCapture capture = captureInteraction(interactEntity);
            if (capture.kind != null) {
                builder.line("Interaction: " + capture.kind, AutismColors.textPrimary());
            }
            if (capture.hand != null) {
                builder.line("Hand: " + capture.hand, AutismColors.textPrimary());
                if (MC.player != null) {
                    builder.line("Held Item: " + formatItemStack(MC.player.getItemInHand(capture.hand)), AutismColors.successText());
                }
            }
            if (capture.hitPos != null) {
                builder.line("Hit Pos: " + formatVec3(capture.hitPos), AutismColors.textPrimary());
            }
        }

        if (packet instanceof ClientboundOpenScreenPacket openScreen) {
            wrote = true;
            complete = true;
            builder.line("Sync Id: " + openScreen.getContainerId(), AutismColors.textPrimary());
            builder.line("Screen Type: " + safeLeafString(openScreen.getType()), AutismColors.textPrimary());
            builder.line("Title: " + formatSimpleValue(openScreen.getTitle()), AutismColors.successText());
        }

        if (packet instanceof ClientboundContainerSetSlotPacket slotUpdate) {
            wrote = true;
            complete = true;
            builder.line("Sync Id: " + slotUpdate.getContainerId(), AutismColors.textPrimary());
            builder.line("Revision: " + slotUpdate.getStateId(), AutismColors.textSecondary());
            builder.line("Slot: " + slotUpdate.getSlot(), AutismColors.textPrimary());
            builder.line("Stack: " + formatItemStack(slotUpdate.getItem()), AutismColors.successText());
        }

        if (packet instanceof ClientboundContainerSetContentPacket inventory) {
            wrote = true;
            builder.line("Sync Id: " + inventory.containerId(), AutismColors.textPrimary());
            builder.line("Revision: " + inventory.stateId(), AutismColors.textSecondary());
            builder.line("Items: " + inventory.items().size() + " slots (" + countNonEmpty(inventory.items()) + " non-empty)",
                AutismColors.textPrimary());
            builder.line("Cursor: " + formatItemStack(inventory.carriedItem()), AutismColors.successText());
        }

        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkData) {
            wrote = true;
            builder.line("Chunk: " + chunkData.getX() + ", " + chunkData.getZ(), AutismColors.textPrimary());
            builder.line("Chunk Data: " + safeLeafString(chunkData.getChunkData()), AutismColors.textSecondary());
            builder.line("Light Data: " + safeLeafString(chunkData.getLightData()), AutismColors.textSecondary());
        }

        if (packet instanceof ClientboundEntityPositionSyncPacket positionSync) {
            wrote = true;
            appendEntityIdLine(builder, "Entity Id", positionSync.id());
            builder.line("Values: " + safeLeafString(positionSync.values()), AutismColors.textPrimary());
            builder.line("On Ground: " + positionSync.onGround(), AutismColors.textPrimary());
        }

        if (packet instanceof ClientboundSetEntityDataPacket trackerUpdate) {
            wrote = true;
            appendEntityIdLine(builder, "Entity Id", trackerUpdate.id());
            List<?> trackedValues = trackerUpdate.packedItems();
            builder.line("Tracked Values: " + (trackedValues == null ? 0 : trackedValues.size()), AutismColors.textPrimary());
        }

        if (packet instanceof ClientboundAddEntityPacket addEntity) {
            wrote = true;
            complete = true;
            appendEntityIdLine(builder, "Entity Id", addEntity.getId());
            builder.line("Entity Type: " + formatEntityType(addEntity.getType()), AutismColors.successText());
            builder.line("Uuid: " + addEntity.getUUID(), AutismColors.textSecondary());
            builder.line("Pos: " + formatVec3(new Vec3(addEntity.getX(), addEntity.getY(), addEntity.getZ())), AutismColors.textPrimary());
            builder.line("Movement: " + formatVec3(addEntity.getMovement()), AutismColors.textPrimary());
            builder.line("Rot: yaw=" + addEntity.getYRot() + ", pitch=" + addEntity.getXRot() + ", head=" + addEntity.getYHeadRot(),
                AutismColors.textPrimary());
            builder.line("Data: " + addEntity.getData(), AutismColors.textSecondary());
        }

        if (packet instanceof ClientboundSoundPacket soundPacket) {
            wrote = true;
            complete = true;
            builder.line("Sound: " + formatHolder(soundPacket.getSound()), AutismColors.successText());
            builder.line("Category: " + soundPacket.getSource(), AutismColors.textPrimary());
            builder.line("Pos: " + formatVec3(new Vec3(soundPacket.getX(), soundPacket.getY(), soundPacket.getZ())), AutismColors.textPrimary());
            builder.line("Volume: " + soundPacket.getVolume(), AutismColors.textSecondary());
            builder.line("Pitch: " + soundPacket.getPitch(), AutismColors.textSecondary());
            builder.line("Seed: " + soundPacket.getSeed(), AutismColors.textMuted());
        }

        if (packet instanceof ClientboundGameEventPacket gameState) {
            wrote = true;
            complete = true;
            builder.line("Reason: " + safeLeafString(gameState.getEvent()), AutismColors.textPrimary());
            builder.line("Value: " + gameState.getParam(), AutismColors.textPrimary());
        }

        SummaryResult broadKnown = appendBroadKnownPacketSummary(packet, builder);
        if (!wrote && broadKnown.wroteAny()) {
            return broadKnown;
        }
        if (broadKnown.wroteAny()) {
            wrote = true;
            complete = complete && broadKnown.complete();
        }

        AutismPayloadSupport.PayloadSnapshot payloadSnapshot = entry == null ? null : AutismPayloadSupport.snapshotFromEntry(entry);
        if (payloadSnapshot != null) {
            wrote = true;
            builder.line("Channel: " + payloadSnapshot.channel(), AutismColors.textPrimary());
            if (payloadSnapshot.protocolPhase() != null && !payloadSnapshot.protocolPhase().isBlank()) {
                builder.line("Payload Protocol: " + payloadSnapshot.protocolPhase(), AutismColors.textSecondary());
            }
            builder.line("Payload Class: " + payloadSnapshot.payloadClassName(), AutismColors.textSecondary());
            builder.line("Payload Size: " + payloadSnapshot.sizeBytes() + " bytes", AutismColors.textPrimary());
            if (payloadSnapshot.commandApiValue() != null) {
                builder.line("CommandApi Value: " + payloadSnapshot.commandApiValue(), AutismColors.successText());
            }
            AutismPayloadTemplate.Preview preview = AutismPayloadTemplate.preview(payloadSnapshot.rawBytes());
            if (preview.minecraftString() != null && !preview.minecraftString().startsWith("<")) {
                builder.line("Minecraft String: " + preview.minecraftString(), AutismColors.successText());
            }
            if (preview.javaWriteUtf() != null && !preview.javaWriteUtf().startsWith("<")) {
                builder.line("Java writeUTF: " + preview.javaWriteUtf(), AutismColors.successText());
            }
            if (preview.utf8() != null && !preview.utf8().isBlank() && !"<binary>".equals(preview.utf8())) {
                builder.line("UTF-8 Guess: " + preview.utf8(), AutismColors.successText());
            }
            builder.line("Payload Hex: " + preview.hex(), AutismColors.textMuted());
            builder.line("Payload Fields: raw bytes preserved; use Edit Payload for typed builder.", AutismColors.successText());
            complete = false;
        }

        SummaryResult reflective = appendReflectivePacketSummary(packet, packetNameHint, entry, builder);
        if (!wrote && reflective.wroteAny()) {
            return reflective;
        }
        return new SummaryResult(wrote, complete);
    }

    private static SummaryResult appendBroadKnownPacketSummary(Packet<?> packet, InspectionBuilder builder) {
        if (packet == null) return SummaryResult.NONE;
        String simpleName = packet.getClass().getSimpleName();
        String className = packet.getClass().getName().replace('$', '.');
        boolean wrote = false;
        boolean complete = true;

        switch (simpleName) {
            case "ClientboundBlockUpdatePacket" -> {
                wrote |= appendMeaning(builder, "Server changed one block");
                wrote |= appendValueLine(builder, packet, "Block Pos", "getPos", "pos");
                wrote |= appendValueLine(builder, packet, "Block", "getBlockState", "blockState", "state");
            }
            case "ClientboundBlockEntityDataPacket" -> {
                wrote |= appendMeaning(builder, "Server updated block entity data");
                wrote |= appendValueLine(builder, packet, "Block Pos", "getPos", "pos");
                wrote |= appendValueLine(builder, packet, "Block Entity Type", "getType", "type");
                wrote |= appendValueLine(builder, packet, "Nbt", "getTag", "tag");
            }
            case "ClientboundBlockEventPacket" -> {
                wrote |= appendMeaning(builder, "Server fired a block event");
                wrote |= appendValueLine(builder, packet, "Block Pos", "getPos", "pos");
                wrote |= appendValueLine(builder, packet, "Block", "getBlock", "block");
                wrote |= appendValueLine(builder, packet, "Event Type", "getB0", "b0", "eventType");
                wrote |= appendValueLine(builder, packet, "Event Data", "getB1", "b1", "eventData");
            }
            case "ClientboundSectionBlocksUpdatePacket" -> {
                wrote |= appendMeaning(builder, "Server changed multiple blocks in a chunk section");
                wrote |= appendValueLine(builder, packet, "Section", "sectionPos", "getSectionPos");
                Object positions = firstNonNull(invokeFirstNoArg(packet, "positions", "getPositions"), findInspectablePropertyValue(packet, "Positions"));
                Object states = firstNonNull(invokeFirstNoArg(packet, "states", "getStates"), findInspectablePropertyValue(packet, "States"));
                if (positions != null) wrote |= appendRawSummaryLine(builder, "Positions", positions);
                if (states != null) wrote |= appendRawSummaryLine(builder, "States", states);
                complete = false;
            }
            case "ClientboundLevelEventPacket" -> {
                wrote |= appendMeaning(builder, "World event such as particles, sound, or block effect");
                wrote |= appendValueLine(builder, packet, "Event", "getType", "type");
                wrote |= appendValueLine(builder, packet, "Block Pos", "getPos", "pos");
                wrote |= appendValueLine(builder, packet, "Data", "getData", "data");
                wrote |= appendValueLine(builder, packet, "Global", "isGlobalEvent", "globalEvent");
            }
            case "ClientboundLevelParticlesPacket" -> {
                wrote |= appendMeaning(builder, "Particle spawn");
                wrote |= appendValueLine(builder, packet, "Particle", "getParticle", "getParticleType", "particle");
                wrote |= appendVec3Components(builder, packet, "Pos", "getX", "x", "getY", "y", "getZ", "z");
                wrote |= appendVec3Components(builder, packet, "Offset", "getXDist", "xDist", "getYDist", "yDist", "getZDist", "zDist");
                wrote |= appendValueLine(builder, packet, "Speed", "getMaxSpeed", "maxSpeed");
                wrote |= appendValueLine(builder, packet, "Count", "getCount", "count");
                wrote |= appendValueLine(builder, packet, "Override Limiter", "isOverrideLimiter", "overrideLimiter");
            }
            case "ClientboundForgetLevelChunkPacket" -> {
                wrote |= appendMeaning(builder, "Client should unload a chunk");
                wrote |= appendValueLine(builder, packet, "Chunk", "pos", "getPos");
                wrote |= appendValueLine(builder, packet, "Chunk X", "getX", "x");
                wrote |= appendValueLine(builder, packet, "Chunk Z", "getZ", "z");
            }
            case "ClientboundSetChunkCacheCenterPacket" -> {
                wrote |= appendMeaning(builder, "Server changed chunk cache center");
                wrote |= appendValueLine(builder, packet, "Chunk X", "getX", "x");
                wrote |= appendValueLine(builder, packet, "Chunk Z", "getZ", "z");
            }
            case "ClientboundSetChunkCacheRadiusPacket", "ClientboundSetSimulationDistancePacket" -> {
                wrote |= appendMeaning(builder, simpleName.contains("Simulation") ? "Server changed simulation distance" : "Server changed chunk render distance");
                wrote |= appendValueLine(builder, packet, "Radius", "getRadius", "radius");
                wrote |= appendValueLine(builder, packet, "Distance", "simulationDistance", "distance");
            }
            case "ClientboundMoveEntityPacket.Pos", "ClientboundMoveEntityPacket.Rot", "ClientboundMoveEntityPacket.PosRot",
                 "ClientboundTeleportEntityPacket", "ClientboundRotateHeadPacket", "ClientboundHurtAnimationPacket" -> {
                wrote |= appendMeaning(builder, "Server moved or rotated an entity");
                wrote |= appendEntityLine(builder, packet, "Entity Id", "getEntityId", "entityId", "id");
                wrote |= appendValueLine(builder, packet, "Delta X", "getXa", "xa");
                wrote |= appendValueLine(builder, packet, "Delta Y", "getYa", "ya");
                wrote |= appendValueLine(builder, packet, "Delta Z", "getZa", "za");
                wrote |= appendValueLine(builder, packet, "Yaw", "getYRot", "yRot", "yaw");
                wrote |= appendValueLine(builder, packet, "Pitch", "getXRot", "xRot", "pitch");
                wrote |= appendValueLine(builder, packet, "Head Yaw", "getYHeadRot", "yHeadRot");
                wrote |= appendValueLine(builder, packet, "Change", "change");
                wrote |= appendValueLine(builder, packet, "Relatives", "relatives");
                wrote |= appendValueLine(builder, packet, "On Ground", "isOnGround", "onGround");
            }
            case "ClientboundMoveVehiclePacket", "ServerboundMoveVehiclePacket" -> {
                wrote |= appendMeaning(builder, simpleName.startsWith("Serverbound") ? "Client moved the ridden vehicle" : "Server moved the ridden vehicle");
                wrote |= appendVec3Components(builder, packet, "Pos", "getX", "x", "getY", "y", "getZ", "z");
                wrote |= appendValueLine(builder, packet, "Yaw", "getYRot", "yRot", "yaw");
                wrote |= appendValueLine(builder, packet, "Pitch", "getXRot", "xRot", "pitch");
                wrote |= appendValueLine(builder, packet, "On Ground", "isOnGround", "onGround");
            }
            case "ClientboundRemoveEntitiesPacket" -> {
                wrote |= appendMeaning(builder, "Server removed entities");
                Object ids = firstNonNull(invokeFirstNoArg(packet, "getEntityIds", "entityIds"), getRecordComponentValue(packet, 0));
                if (ids != null) {
                    builder.line("Entities: " + formatEntityIdList(ids), AutismColors.packetCyan());
                    wrote = true;
                }
            }
            case "ClientboundTakeItemEntityPacket" -> {
                wrote |= appendMeaning(builder, "Item/entity pickup animation");
                wrote |= appendEntityLine(builder, packet, "Item Entity", "getItemId", "itemId");
                wrote |= appendEntityLine(builder, packet, "Collector", "getPlayerId", "playerId");
                wrote |= appendValueLine(builder, packet, "Amount", "getAmount", "amount");
            }
            case "ClientboundSetPassengersPacket" -> {
                wrote |= appendMeaning(builder, "Server changed vehicle passengers");
                wrote |= appendEntityLine(builder, packet, "Vehicle", "getVehicle", "vehicle", "vehicleId");
                Object passengers = firstNonNull(invokeFirstNoArg(packet, "getPassengers", "passengers"), findInspectablePropertyValue(packet, "Passengers"));
                if (passengers != null) {
                    builder.line("Passengers: " + formatEntityIdList(passengers), AutismColors.packetCyan());
                    wrote = true;
                }
            }
            case "ClientboundSetEntityLinkPacket" -> {
                wrote |= appendMeaning(builder, "Server linked one entity to another");
                wrote |= appendEntityLine(builder, packet, "Source", "getSourceId", "sourceId", "source");
                wrote |= appendEntityLine(builder, packet, "Destination", "getDestId", "destId", "destination");
            }
            case "ClientboundSetHealthPacket" -> {
                wrote |= appendMeaning(builder, "Player health update");
                wrote |= appendValueLine(builder, packet, "Health", "getHealth", "health");
                wrote |= appendValueLine(builder, packet, "Food", "getFood", "food");
                wrote |= appendValueLine(builder, packet, "Saturation", "getSaturation", "saturation");
            }
            case "ClientboundSetExperiencePacket" -> {
                wrote |= appendMeaning(builder, "Player experience update");
                wrote |= appendValueLine(builder, packet, "Progress", "getExperienceProgress", "experienceProgress", "progress");
                wrote |= appendValueLine(builder, packet, "Level", "getExperienceLevel", "experienceLevel", "level");
                wrote |= appendValueLine(builder, packet, "Total", "getTotalExperience", "totalExperience", "total");
            }
            case "ClientboundSetHeldSlotPacket", "ServerboundSetCarriedItemPacket" -> {
                wrote |= appendMeaning(builder, simpleName.startsWith("Serverbound") ? "Client selected hotbar slot" : "Server selected held hotbar slot");
                Object slot = firstNonNull(invokeFirstNoArg(packet, "getSlot", "slot", "getSelectedSlot", "selectedSlot"), getRecordComponentValue(packet, 0));
                if (slot instanceof Number number) {
                    builder.line("Slot: " + formatHotbarSlot(number.intValue()), AutismColors.textPrimary());
                    wrote = true;
                } else if (slot != null) {
                    builder.line("Slot: " + safeLeafString(slot), AutismColors.textPrimary());
                    wrote = true;
                }
            }
            case "ClientboundSetPlayerInventoryPacket", "ClientboundSetCursorItemPacket" -> {
                wrote |= appendMeaning(builder, simpleName.contains("Cursor") ? "Server updated cursor item" : "Server updated player inventory slot");
                wrote |= appendValueLine(builder, packet, "Slot", "slot", "getSlot");
                wrote |= appendValueLine(builder, packet, "Stack", "contents", "getContents", "item", "getItem");
            }
            case "ClientboundContainerClosePacket" -> {
                wrote |= appendMeaning(builder, "Server closed a handled screen");
                wrote |= appendValueLine(builder, packet, "Sync Id", "getContainerId", "containerId");
            }
            case "ClientboundCooldownPacket" -> {
                wrote |= appendMeaning(builder, "Item cooldown update");
                wrote |= appendValueLine(builder, packet, "Item", "getItem", "item");
                wrote |= appendValueLine(builder, packet, "Ticks", "getDuration", "duration", "ticks");
            }
            case "ClientboundOpenBookPacket" -> {
                wrote |= appendMeaning(builder, "Open book animation/screen");
                wrote |= appendValueLine(builder, packet, "Hand", "getHand", "hand");
            }
            case "ClientboundOpenSignEditorPacket" -> {
                wrote |= appendMeaning(builder, "Open sign editor");
                wrote |= appendValueLine(builder, packet, "Block Pos", "getPos", "pos");
                wrote |= appendValueLine(builder, packet, "Front", "isFrontText", "frontText", "front");
            }
            case "ClientboundSetActionBarTextPacket", "ClientboundSetTitleTextPacket", "ClientboundSetSubtitleTextPacket" -> {
                wrote |= appendMeaning(builder, "Server displayed text on the HUD");
                wrote |= appendTextComponentLine(builder, packet, "Text", "getText", "text");
            }
            case "ClientboundSetTitlesAnimationPacket" -> {
                wrote |= appendMeaning(builder, "Title timing update");
                wrote |= appendValueLine(builder, packet, "Fade In", "getFadeIn", "fadeIn");
                wrote |= appendValueLine(builder, packet, "Stay", "getStay", "stay");
                wrote |= appendValueLine(builder, packet, "Fade Out", "getFadeOut", "fadeOut");
            }
            case "ClientboundClearTitlesPacket" -> {
                wrote |= appendMeaning(builder, "Clear title/subtitle text");
                wrote |= appendValueLine(builder, packet, "Reset Times", "shouldResetTimes", "resetTimes");
            }
            case "ClientboundTabListPacket" -> {
                wrote |= appendMeaning(builder, "Player list header/footer update");
                wrote |= appendTextComponentLine(builder, packet, "Header", "getHeader", "header");
                wrote |= appendTextComponentLine(builder, packet, "Footer", "getFooter", "footer");
            }
            case "ClientboundSetTimePacket" -> {
                wrote |= appendMeaning(builder, "World time update");
                wrote |= appendValueLine(builder, packet, "Game Time", "getGameTime", "gameTime");
                wrote |= appendValueLine(builder, packet, "Day Time", "getDayTime", "dayTime");
                wrote |= appendValueLine(builder, packet, "Tick Day Time", "isTickDayTime", "tickDayTime");
            }
            case "ClientboundPlayerAbilitiesPacket", "ServerboundPlayerAbilitiesPacket" -> {
                wrote |= appendMeaning(builder, "Player ability flags");
                wrote |= appendValueLine(builder, packet, "Invulnerable", "isInvulnerable", "invulnerable");
                wrote |= appendValueLine(builder, packet, "Flying", "isFlying", "flying");
                wrote |= appendValueLine(builder, packet, "Can Fly", "canFly", "mayfly", "mayFly");
                wrote |= appendValueLine(builder, packet, "Instabuild", "canInstabuild", "instabuild");
                wrote |= appendValueLine(builder, packet, "Fly Speed", "getFlyingSpeed", "flyingSpeed");
                wrote |= appendValueLine(builder, packet, "Walk Speed", "getWalkingSpeed", "walkingSpeed");
            }
            case "ClientboundChangeDifficultyPacket", "ServerboundChangeDifficultyPacket", "ServerboundLockDifficultyPacket" -> {
                wrote |= appendMeaning(builder, "Difficulty setting update");
                wrote |= appendValueLine(builder, packet, "Difficulty", "getDifficulty", "difficulty");
                wrote |= appendValueLine(builder, packet, "Locked", "isLocked", "locked");
            }
            case "ClientboundSoundEntityPacket" -> {
                wrote |= appendMeaning(builder, "Sound played from an entity");
                wrote |= appendValueLine(builder, packet, "Sound", "getSound", "sound");
                wrote |= appendValueLine(builder, packet, "Category", "getSource", "source", "category");
                wrote |= appendEntityLine(builder, packet, "Entity", "getId", "id", "entityId");
                wrote |= appendValueLine(builder, packet, "Volume", "getVolume", "volume");
                wrote |= appendValueLine(builder, packet, "Pitch", "getPitch", "pitch");
                wrote |= appendValueLine(builder, packet, "Seed", "getSeed", "seed");
            }
            case "ClientboundStopSoundPacket" -> {
                wrote |= appendMeaning(builder, "Stop one or more sounds");
                wrote |= appendValueLine(builder, packet, "Source", "getSource", "source");
                wrote |= appendValueLine(builder, packet, "Name", "getName", "name");
            }
            case "ClientboundBossEventPacket" -> {
                wrote |= appendMeaning(builder, "Boss bar update");
                wrote |= appendValueLine(builder, packet, "Boss Bar Id", "getId", "id");
                wrote |= appendValueLine(builder, packet, "Operation", "getOperation", "operation");
                complete = false;
            }
            case "ClientboundPlayerInfoUpdatePacket" -> {
                wrote |= appendMeaning(builder, "Player list entries updated");
                wrote |= appendValueLine(builder, packet, "Actions", "actions");
                wrote |= appendCollectionLine(builder, packet, "Entries", "entries");
                complete = false;
            }
            case "ClientboundPlayerInfoRemovePacket" -> {
                wrote |= appendMeaning(builder, "Player list entries removed");
                wrote |= appendCollectionLine(builder, packet, "Profiles", "profileIds", "getProfileIds");
            }
            case "ClientboundPlayerCombatEndPacket", "ClientboundPlayerCombatEnterPacket", "ClientboundPlayerCombatKillPacket" -> {
                wrote |= appendMeaning(builder, "Player combat state update");
                wrote |= appendEntityLine(builder, packet, "Player", "playerId", "getPlayerId");
                wrote |= appendEntityLine(builder, packet, "Killer", "killerId", "getKillerId");
                wrote |= appendValueLine(builder, packet, "Duration", "duration", "getDuration");
                wrote |= appendTextComponentLine(builder, packet, "Message", "message", "getMessage");
            }
            case "ClientboundSetScorePacket", "ClientboundResetScorePacket" -> {
                wrote |= appendMeaning(builder, "Scoreboard score update");
                wrote |= appendValueLine(builder, packet, "Owner", "owner", "getOwner");
                wrote |= appendValueLine(builder, packet, "Objective", "objectiveName", "getObjectiveName");
                wrote |= appendValueLine(builder, packet, "Score", "score", "getScore");
                wrote |= appendValueLine(builder, packet, "Display", "display", "numberFormat", "getDisplay");
            }
            case "ClientboundSetObjectivePacket", "ClientboundSetDisplayObjectivePacket" -> {
                wrote |= appendMeaning(builder, "Scoreboard objective update");
                wrote |= appendValueLine(builder, packet, "Objective", "getObjectiveName", "objectiveName");
                wrote |= appendTextComponentLine(builder, packet, "Display Name", "getDisplayName", "displayName");
                wrote |= appendValueLine(builder, packet, "Render Type", "getRenderType", "renderType");
                wrote |= appendValueLine(builder, packet, "Slot", "getSlot", "slot");
                wrote |= appendValueLine(builder, packet, "Method", "getMethod", "method");
            }
            case "ClientboundSetPlayerTeamPacket" -> {
                wrote |= appendMeaning(builder, "Scoreboard team update");
                wrote |= appendValueLine(builder, packet, "Team", "getName", "name");
                wrote |= appendValueLine(builder, packet, "Method", "getMethod", "method");
                wrote |= appendCollectionLine(builder, packet, "Players", "getPlayers", "players");
                complete = false;
            }
            case "ClientboundSetBorderCenterPacket", "ClientboundSetBorderSizePacket", "ClientboundSetBorderLerpSizePacket",
                 "ClientboundSetBorderWarningDelayPacket", "ClientboundSetBorderWarningDistancePacket",
                 "ClientboundInitializeBorderPacket" -> {
                wrote |= appendMeaning(builder, "World border update");
                wrote |= appendValueLine(builder, packet, "Center X", "getNewCenterX", "newCenterX", "centerX");
                wrote |= appendValueLine(builder, packet, "Center Z", "getNewCenterZ", "newCenterZ", "centerZ");
                wrote |= appendValueLine(builder, packet, "Size", "getSize", "size");
                wrote |= appendValueLine(builder, packet, "Old Size", "getOldSize", "oldSize");
                wrote |= appendValueLine(builder, packet, "New Size", "getNewSize", "newSize");
                wrote |= appendValueLine(builder, packet, "Lerp Time", "getLerpTime", "lerpTime");
                wrote |= appendValueLine(builder, packet, "Warning Blocks", "getWarningBlocks", "warningBlocks");
                wrote |= appendValueLine(builder, packet, "Warning Time", "getWarningTime", "warningTime");
            }
            case "ClientboundResourcePackPushPacket", "ClientboundResourcePackPopPacket" -> {
                wrote |= appendMeaning(builder, "Resource pack request/update");
                wrote |= appendValueLine(builder, packet, "Id", "id", "getId");
                wrote |= appendValueLine(builder, packet, "Url", "url", "getUrl");
                wrote |= appendValueLine(builder, packet, "Hash", "hash", "getHash");
                wrote |= appendValueLine(builder, packet, "Required", "required", "isRequired");
                wrote |= appendTextComponentLine(builder, packet, "Prompt", "prompt", "getPrompt");
            }
            case "ClientboundUpdateRecipesPacket", "ClientboundRecipeBookAddPacket", "ClientboundRecipeBookRemovePacket",
                 "ClientboundRecipeBookSettingsPacket" -> {
                wrote |= appendMeaning(builder, "Recipe book/recipe registry update");
                wrote |= appendCollectionLine(builder, packet, "Recipes", "recipes", "getRecipes", "entries");
                wrote |= appendValueLine(builder, packet, "Settings", "settings", "getSettings");
                complete = false;
            }
            case "ClientboundUpdateTagsPacket" -> {
                wrote |= appendMeaning(builder, "Registry tag update");
                wrote |= appendValueLine(builder, packet, "Tags", "tags", "getTags");
                complete = false;
            }
            case "ServerboundPlayerCommandPacket" -> {
                wrote |= appendMeaning(builder, "Client sent player/entity command");
                wrote |= appendEntityLine(builder, packet, "Entity", "getId", "id", "entityId");
                wrote |= appendValueLine(builder, packet, "Action", "getAction", "action");
                wrote |= appendValueLine(builder, "Data", firstNonNull(invokeFirstNoArg(packet, "getData", "data"), getRecordComponentValue(packet, 2)));
            }
            case "ServerboundPlayerInputPacket" -> {
                wrote |= appendMeaning(builder, "Client movement input state");
                wrote |= appendValueLine(builder, packet, "Input", "input");
                wrote |= appendValueLine(builder, packet, "Forward", "xxa", "forward");
                wrote |= appendValueLine(builder, packet, "Sideways", "zza", "sideways");
                wrote |= appendValueLine(builder, packet, "Jumping", "isJumping", "jumping");
                wrote |= appendValueLine(builder, packet, "Sneaking", "isShiftKeyDown", "shiftKeyDown", "sneaking");
            }
            case "ServerboundPaddleBoatPacket" -> {
                wrote |= appendMeaning(builder, "Client boat paddle state");
                wrote |= appendValueLine(builder, packet, "Left", "getLeft", "left");
                wrote |= appendValueLine(builder, packet, "Right", "getRight", "right");
            }
            case "ServerboundCommandSuggestionPacket" -> {
                wrote |= appendMeaning(builder, "Client requested command completions");
                wrote |= appendValueLine(builder, packet, "Transaction Id", "getId", "id");
                wrote |= appendValueLine(builder, packet, "Command", "getCommand", "command");
            }
            case "ServerboundChatCommandPacket", "ServerboundChatCommandSignedPacket" -> {
                wrote |= appendMeaning(builder, "Outgoing command");
                wrote |= appendValueLine(builder, packet, "Command", "command", "getCommand");
            }
            case "ServerboundPickItemFromBlockPacket" -> {
                wrote |= appendMeaning(builder, "Client pick-blocked a block");
                wrote |= appendValueLine(builder, packet, "Block Pos", "pos", "getPos");
                wrote |= appendValueLine(builder, packet, "Include Data", "includeData", "isIncludeData");
            }
            case "ServerboundPickItemFromEntityPacket" -> {
                wrote |= appendMeaning(builder, "Client pick-blocked an entity");
                wrote |= appendEntityLine(builder, packet, "Entity", "id", "getId", "entityId");
                wrote |= appendValueLine(builder, packet, "Include Data", "includeData", "isIncludeData");
            }
            default -> {
                if (className.contains("ServerboundMovePlayerPacket")) {
                    wrote |= appendMeaning(builder, "Client player movement");
                    wrote |= appendVec3Components(builder, packet, "Pos", "getX", "x", "getY", "y", "getZ", "z");
                    wrote |= appendValueLine(builder, packet, "Yaw", "getYRot", "yRot", "yaw");
                    wrote |= appendValueLine(builder, packet, "Pitch", "getXRot", "xRot", "pitch");
                    wrote |= appendValueLine(builder, packet, "On Ground", "isOnGround", "onGround");
                    wrote |= appendValueLine(builder, packet, "Horizontal Collision", "horizontalCollision");
                } else if (className.contains("ClientboundMoveEntityPacket")) {
                    wrote |= appendMeaning(builder, "Server moved or rotated an entity");
                    wrote |= appendEntityLine(builder, packet, "Entity Id", "getEntityId", "entityId", "id");
                    wrote |= appendValueLine(builder, packet, "Delta X", "getXa", "xa");
                    wrote |= appendValueLine(builder, packet, "Delta Y", "getYa", "ya");
                    wrote |= appendValueLine(builder, packet, "Delta Z", "getZa", "za");
                    wrote |= appendValueLine(builder, packet, "Yaw", "getYRot", "yRot", "yaw");
                    wrote |= appendValueLine(builder, packet, "Pitch", "getXRot", "xRot", "pitch");
                    wrote |= appendValueLine(builder, packet, "On Ground", "isOnGround", "onGround");
                }
            }
        }

        return new SummaryResult(wrote, complete);
    }

    private static boolean appendMeaning(InspectionBuilder builder, String meaning) {
        if (meaning == null || meaning.isBlank()) return false;
        builder.line("Meaning: " + meaning, AutismColors.textPrimary());
        return true;
    }

    private static boolean appendValueLine(InspectionBuilder builder, Packet<?> packet, String label, String... accessors) {
        Object value = firstNonNull(invokeFirstNoArg(packet, accessors), findInspectablePropertyValue(packet, label));
        return appendValueLine(builder, label, value);
    }

    private static boolean appendValueLine(InspectionBuilder builder, String label, Object value) {
        if (value == null || value == INACCESSIBLE) return false;
        builder.line(label + ": " + summarizeValue(label, value), colorForSummary(label, value));
        return true;
    }

    private static boolean appendRawSummaryLine(InspectionBuilder builder, String label, Object value) {
        if (value == null || value == INACCESSIBLE) return false;
        builder.line(label + ": " + summarizeValue(value), colorForSummary(label, value));
        return true;
    }

    private static boolean appendEntityLine(InspectionBuilder builder, Packet<?> packet, String label, String... accessors) {
        Object value = firstNonNull(invokeFirstNoArg(packet, accessors), findInspectablePropertyValue(packet, label));
        if (value instanceof Number number) {
            appendEntityIdLine(builder, label, number.intValue());
            return true;
        }
        if (value instanceof Entity entity) {
            builder.line(label + ": " + formatEntity(entity), AutismColors.packetCyan());
            return true;
        }
        return appendValueLine(builder, label, value);
    }

    private static boolean appendTextComponentLine(InspectionBuilder builder, Packet<?> packet, String label, String... accessors) {
        Object value = firstNonNull(invokeFirstNoArg(packet, accessors), findInspectablePropertyValue(packet, label));
        if (value == null || value == INACCESSIBLE) return false;
        String text = extractTextValue(value);
        builder.line(label + ": " + (text == null || text.isBlank() ? summarizeValue(label, value) : quote(shorten(text))),
            semanticFieldColor(label, value));
        return true;
    }

    private static boolean appendCollectionLine(InspectionBuilder builder, Packet<?> packet, String label, String... accessors) {
        Object value = firstNonNull(invokeFirstNoArg(packet, accessors), findInspectablePropertyValue(packet, label));
        if (value == null || value == INACCESSIBLE) return false;
        if (value instanceof Collection<?> collection) {
            builder.line(label + ": " + formatCollectionPreview(collection), AutismColors.textSecondary());
        } else {
            builder.line(label + ": " + summarizeValue(label, value), colorForSummary(label, value));
        }
        return true;
    }

    private static boolean appendVec3Components(InspectionBuilder builder, Packet<?> packet, String label,
                                                String xGetter, String xField, String yGetter, String yField, String zGetter, String zField) {
        Object x = firstNonNull(invokeFirstNoArg(packet, xGetter, xField), findInspectablePropertyValue(packet, xField));
        Object y = firstNonNull(invokeFirstNoArg(packet, yGetter, yField), findInspectablePropertyValue(packet, yField));
        Object z = firstNonNull(invokeFirstNoArg(packet, zGetter, zField), findInspectablePropertyValue(packet, zField));
        if (!(x instanceof Number xn) || !(y instanceof Number yn) || !(z instanceof Number zn)) return false;
        builder.line(label + ": " + formatVec3(new Vec3(xn.doubleValue(), yn.doubleValue(), zn.doubleValue())),
            AutismColors.packetYellow());
        return true;
    }

    private static SummaryResult appendGenericSummary(Packet<?> packet, InspectionBuilder builder) {
        List<PropertyValue> properties = getInspectableProperties(packet);
        if (properties.isEmpty()) {
            String rawFallback = rawFallbackString(packet);
            if (rawFallback != null) {
                builder.line("Raw: " + rawFallback, AutismColors.textMuted());
                return new SummaryResult(true, false);
            }
            return SummaryResult.NONE;
        }

        int lines = 0;
        for (PropertyValue property : properties) {
            if (lines >= MAX_GENERIC_SUMMARY_LINES) break;
            Object value = property.value();
            if (value == null || value == INACCESSIBLE) continue;
            String summary = summarizeValue(property.label(), value);
            if (summary == null || summary.isBlank()) continue;
            builder.line(property.label() + ": " + summary, colorForSummary(property.label(), value));
            lines++;
        }
        return new SummaryResult(lines > 0, false);
    }

    private static SummaryResult appendReflectivePacketSummary(Packet<?> packet, String packetNameHint,
                                                               AutismPacketLoggerOverlay.LogEntry entry,
                                                               InspectionBuilder builder) {
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundSwingPacket
            || packetMatchesHint(packet, packetNameHint, "HandSwingC2S", "HandSwingC2SPacket")) {
            Object hand = firstNonNull(
                invokeFirstNoArg(packet, "getHand", "hand"),
                getRecordComponentValue(packet, 0),
                findInspectablePropertyValue(packet, "Hand")
            );
            if (hand != null) {
                builder.line("Interaction: Swing InteractionHand (attack / use animation)", AutismColors.textPrimary());
                builder.line("Hand: " + safeLeafString(hand), AutismColors.textPrimary());
                if (MC.player != null && hand instanceof InteractionHand playerHand) {
                    builder.line("Held Item: " + formatItemStack(MC.player.getItemInHand(playerHand)), AutismColors.successText());
                }
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket updateSelectedSlotPacket) {
            Object slot = invokeFirstNoArg(updateSelectedSlotPacket, "getSelectedSlot", "getSlot", "selectedSlot", "slot");
            if (slot != null) {
                if (slot instanceof Number number) {
                    builder.line("Selected Slot: " + formatHotbarSlot(number.intValue()), AutismColors.textPrimary());
                } else {
                    builder.line("Selected Slot: " + safeLeafString(slot), AutismColors.textPrimary());
                }
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundChatPacket chatMessagePacket
            || packetMatchesHint(packet, packetNameHint, "ChatMessageC2S", "ChatMessageC2SPacket")) {
            Object message = firstNonNull(
                invokeFirstNoArg(packet, "chatMessage", "getChatMessage"),
                getRecordComponentValue(packet, 0)
            );
            Object timestamp = firstNonNull(
                invokeFirstNoArg(packet, "timestamp", "getTimestamp"),
                getRecordComponentValue(packet, 1)
            );
            Object salt = firstNonNull(
                invokeFirstNoArg(packet, "salt", "getSalt"),
                getRecordComponentValue(packet, 2)
            );
            Object signature = firstNonNull(
                invokeFirstNoArg(packet, "signature", "getSignature"),
                getRecordComponentValue(packet, 3)
            );
            Object acknowledgment = firstNonNull(
                invokeFirstNoArg(packet, "acknowledgment", "getAcknowledgment"),
                getRecordComponentValue(packet, 4)
            );
            if (message != null || timestamp != null || salt != null) {
                builder.line("Kind: Outgoing Signed Chat Message", AutismColors.textPrimary());
                if (MC.player != null) {
                    String senderName = MC.player.getName() == null ? null : MC.player.getName().getString();
                    if (senderName != null && !senderName.isBlank()) {
                        builder.line("Sender: " + senderName, AutismColors.textPrimary());
                    }
                }
                String text = extractTextValue(message);
                if (text != null && !text.isBlank()) {
                    builder.line("Message: " + quote(shorten(text)), AutismColors.successText());
                }
                if (timestamp != null) builder.line("Timestamp: " + safeLeafString(timestamp), AutismColors.textSecondary());
                if (salt != null) builder.line("Salt: " + safeLeafString(salt), AutismColors.textSecondary());
                if (signature != null) builder.line("Signature: Present", AutismColors.textSecondary());
                if (acknowledgment != null) builder.line("Acknowledgment: " + summarizeValue(acknowledgment), colorForSummary(acknowledgment));
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket
            || packetMatchesHint(packet, packetNameHint, "PlayerActionResponseS2C", "PlayerActionResponseS2CPacket")) {
            Object sequence = firstNonNull(
                invokeFirstNoArg(packet, "sequence", "getSequence"),
                getRecordComponentValue(packet, 0),
                getField(packet, "comp_633"),
                findInspectablePropertyValue(packet, "Sequence")
            );
            if (sequence != null) {
                builder.line("Meaning: Server acknowledged a sequence-based player action", AutismColors.textPrimary());
                builder.line("Sequence: " + safeLeafString(sequence), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClosePacket
            || packetMatchesHint(packet, packetNameHint, "CloseAbstractContainerScreenC2S", "ServerboundContainerClosePacket")) {
            Object syncId = firstNonNull(
                invokeFirstNoArg(packet, "getSyncId", "syncId"),
                getRecordComponentValue(packet, 0),
                getField(packet, "field_12827"),
                findInspectablePropertyValue(packet, "Sync Id", "SyncId")
            );
            if (syncId != null) {
                builder.line("Interaction: Close Handled Screen", AutismColors.textPrimary());
                builder.line("Sync Id: " + safeLeafString(syncId), AutismColors.textPrimary());
                if (entry != null && entry.capturedScreen != null && !entry.capturedScreen.isBlank()) {
                    builder.line("Closed Screen: " + entry.capturedScreen, AutismColors.successText());
                }
                if (syncId instanceof Number number && number.intValue() == 0) {
                    builder.line("Likely Screen: Player Inventory / Survival Crafting", AutismColors.successText());
                }
                builder.line("Note: This packet only sends the sync id, not the closed screen title/type", AutismColors.textSecondary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
            || packetMatchesHint(packet, packetNameHint, "EntityEquipmentUpdateS2C", "EntityEquipmentUpdateS2CPacket")) {
            Object entityIdValue = firstNonNull(
                invokeFirstNoArg(packet, "getEntityId", "entityId", "id"),
                getRecordComponentValue(packet, 0),
                getField(packet, "field_12565"),
                findInspectablePropertyValue(packet, "Entity Id", "EntityId")
            );
            Object equipmentListValue = firstNonNull(
                invokeFirstNoArg(packet, "getEquipmentList", "equipmentList"),
                getRecordComponentValue(packet, 1),
                getField(packet, "field_25721"),
                findInspectablePropertyValue(packet, "Equipment List", "EquipmentList")
            );
            if (entityIdValue != null || equipmentListValue != null) {
                builder.line("Meaning: Server updated an entity's visible equipment", AutismColors.textPrimary());
                if (entityIdValue instanceof Number entityIdNumber) {
                    appendEntityIdLine(builder, "Entity Id", entityIdNumber.intValue());
                } else if (entityIdValue != null) {
                    builder.line("Entity Id: " + safeLeafString(entityIdValue), AutismColors.textPrimary());
                }
                if (equipmentListValue instanceof Collection<?> equipmentEntries) {
                    builder.line("Changed Slots: " + equipmentEntries.size(), AutismColors.textPrimary());
                    String equipmentSummary = summarizeEquipmentEntries(equipmentEntries);
                    if (equipmentSummary != null && !equipmentSummary.isBlank()) {
                        builder.line("Equipment: " + equipmentSummary, AutismColors.successText());
                    }
                } else if (equipmentListValue != null) {
                    builder.line("Equipment: " + summarizeValue(equipmentListValue), colorForSummary(equipmentListValue));
                }
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket buttonClickPacket) {
            Object syncId = invokeFirstNoArg(buttonClickPacket, "getSyncId", "syncId");
            Object buttonId = invokeFirstNoArg(buttonClickPacket, "getButtonId", "buttonId");
            if (syncId != null || buttonId != null) {
                builder.line("Interaction: Click handled-screen button", AutismColors.textPrimary());
                if (syncId != null) builder.line("Sync Id: " + safeLeafString(syncId), AutismColors.textPrimary());
                if (buttonId != null) builder.line("Button Id: " + safeLeafString(buttonId), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket creativeInventoryActionPacket) {
            Object slot = invokeFirstNoArg(creativeInventoryActionPacket, "getSlot", "slot");
            Object stack = invokeFirstNoArg(creativeInventoryActionPacket, "getStack", "stack");
            if (slot != null || stack != null) {
                if (slot != null) builder.line("Slot: " + safeLeafString(slot), AutismColors.textPrimary());
                if (stack != null) builder.line("Stack: " + summarizeValue(stack), colorForSummary(stack));
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket clickSlotPacket) {
            Object syncId = invokeFirstNoArg(clickSlotPacket, "getSyncId", "syncId");
            Object revision = invokeFirstNoArg(clickSlotPacket, "getRevision", "revision");
            Object slot = invokeFirstNoArg(clickSlotPacket, "getSlot", "slot");
            Object button = invokeFirstNoArg(clickSlotPacket, "getButton", "button");
            Object actionType = invokeFirstNoArg(clickSlotPacket, "getActionType", "actionType");
            Object modifiedStacks = invokeFirstNoArg(clickSlotPacket, "getModifiedStacks", "modifiedStacks");
            Object cursorStack = invokeFirstNoArg(clickSlotPacket, "cursor", "getStack", "getCursorStack", "cursorStack");
            if (syncId != null || slot != null || actionType != null) {
                builder.line("Interaction: " + describeContainerInput(actionType), AutismColors.textPrimary());
                if (syncId != null) builder.line("Sync Id: " + safeLeafString(syncId), AutismColors.textPrimary());
                if (revision != null) builder.line("Revision: " + safeLeafString(revision), AutismColors.textSecondary());
                if (slot != null) builder.line("Slot: " + safeLeafString(slot), AutismColors.textPrimary());
                if (button != null) builder.line("Button: " + safeLeafString(button), AutismColors.textPrimary());
                if (actionType != null) builder.line("Action Type: " + safeLeafString(actionType), AutismColors.textPrimary());
                if (cursorStack != null) builder.line("Cursor: " + summarizeValue(cursorStack), colorForSummary(cursorStack));
                if (modifiedStacks instanceof Map<?, ?> map) {
                    builder.line("Changed Slots: " + map.size(), AutismColors.textSecondary());
                } else if (modifiedStacks != null) {
                    builder.line("Changed Slots: " + summarizeValue(modifiedStacks), colorForSummary(modifiedStacks));
                }
                return new SummaryResult(true, false);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundEditBookPacket bookUpdatePacket) {
            Object slot = invokeFirstNoArg(bookUpdatePacket, "slot", "getSlot");
            Object pages = invokeFirstNoArg(bookUpdatePacket, "pages", "getPages");
            Object title = invokeFirstNoArg(bookUpdatePacket, "title", "getTitle");
            if (slot != null || pages != null || title != null) {
                boolean signing = title instanceof Optional<?> optional && optional.isPresent();
                builder.line("Interaction: " + (signing ? "Sign Book" : "Edit Book"), AutismColors.textPrimary());
                if (slot instanceof Number number) {
                    builder.line("Slot: " + formatHotbarSlot(number.intValue()), AutismColors.textPrimary());
                } else if (slot != null) {
                    builder.line("Slot: " + safeLeafString(slot), AutismColors.textPrimary());
                }
                if (title != null) {
                    String titleComponent = extractTextValue(title);
                    if (titleComponent != null && !titleComponent.isBlank()) {
                            builder.line("Title: " + quote(shorten(titleComponent)), AutismColors.successText());
                    }
                }
                if (pages instanceof Collection<?> collection) {
                    builder.line("Pages: " + collection.size(), AutismColors.textPrimary());
                    String preview = summarizeBookPages(collection);
                    if (preview != null) builder.line("Preview: " + preview, AutismColors.textSecondary());
                }
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundSignUpdatePacket updateSignPacket) {
            Object pos = invokeFirstNoArg(updateSignPacket, "getPos", "pos");
            Object lines = invokeFirstNoArg(updateSignPacket, "getText", "text");
            Object front = invokeFirstNoArg(updateSignPacket, "isFront", "front");
            if (pos != null || lines != null || front != null) {
                builder.line("Interaction: Update Sign Component", AutismColors.textPrimary());
                if (pos != null) builder.line("Block Pos: " + summarizeValue(pos), colorForSummary(pos));
                if (front != null) builder.line("Side: " + (Boolean.TRUE.equals(front) ? "Front" : "Back"), AutismColors.textPrimary());
                if (lines instanceof String[] stringLines) {
                    builder.line("Component: " + summarizeSignLines(stringLines), AutismColors.successText());
                } else if (lines != null) {
                    builder.line("Component: " + summarizeValue(lines), colorForSummary(lines));
                }
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundRenameItemPacket renameItemPacket) {
            Object name = invokeFirstNoArg(renameItemPacket, "getName", "name");
            if (name != null) {
                builder.line("Interaction: Rename Item in Anvil", AutismColors.textPrimary());
                builder.line("Name: " + quote(shorten(String.valueOf(name))), AutismColors.successText());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket craftRequestPacket) {
            Object syncId = invokeFirstNoArg(craftRequestPacket, "syncId", "getSyncId");
            Object recipeId = invokeFirstNoArg(craftRequestPacket, "recipeId", "getRecipeId");
            Object craftAll = invokeFirstNoArg(craftRequestPacket, "craftAll", "isCraftAll", "getCraftAll");
            if (syncId != null || recipeId != null || craftAll != null) {
                builder.line("Interaction: Craft Recipe", AutismColors.textPrimary());
                if (syncId != null) builder.line("Sync Id: " + safeLeafString(syncId), AutismColors.textPrimary());
                if (recipeId != null) builder.line("Recipe: " + safeLeafString(recipeId), AutismColors.successText());
                if (craftAll != null) builder.line("Craft All: " + safeLeafString(craftAll), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundSelectTradePacket selectMerchantTradePacket) {
            Object tradeId = invokeFirstNoArg(selectMerchantTradePacket, "getTradeId", "tradeId");
            if (tradeId != null) {
                builder.line("Interaction: Select Merchant Trade", AutismColors.textPrimary());
                builder.line("Trade Index: " + safeLeafString(tradeId), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket screenHandlerPropertyUpdatePacket) {
            Object syncId = invokeFirstNoArg(screenHandlerPropertyUpdatePacket, "getSyncId", "syncId");
            Object property = invokeFirstNoArg(screenHandlerPropertyUpdatePacket, "getPropertyId", "propertyId", "getProperty", "property");
            Object value = invokeFirstNoArg(screenHandlerPropertyUpdatePacket, "getValue", "value");
            if (syncId != null || property != null || value != null) {
                if (syncId != null) builder.line("Sync Id: " + safeLeafString(syncId), AutismColors.textPrimary());
                if (property != null) builder.line("Property: " + safeLeafString(property), AutismColors.textPrimary());
                if (value != null) builder.line("Value: " + safeLeafString(value), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket blockBreakingProgressPacket) {
            Object entityId = invokeFirstNoArg(blockBreakingProgressPacket, "getEntityId", "entityId");
            Object pos = invokeFirstNoArg(blockBreakingProgressPacket, "getPos", "pos");
            Object progress = invokeFirstNoArg(blockBreakingProgressPacket, "getProgress", "progress");
            if (entityId != null || pos != null || progress != null) {
                if (entityId instanceof Number number) {
                    appendEntityIdLine(builder, "Entity Id", number.intValue());
                } else if (entityId != null) {
                    builder.line("Entity Id: " + safeLeafString(entityId), AutismColors.textPrimary());
                }
                if (pos != null) builder.line("Block Pos: " + summarizeValue(pos), colorForSummary(pos));
                if (progress != null) builder.line("Progress: " + safeLeafString(progress), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundEntityEventPacket entityStatusPacket) {
            Object entityId = invokeFirstNoArg(entityStatusPacket, "getEntityId", "entityId");
            if (entityId == null && MC.level != null) {
                Entity entity = entityStatusPacket.getEntity(MC.level);
                if (entity != null) entityId = entity.getId();
            }
            Object status = invokeFirstNoArg(entityStatusPacket, "getStatus", "status");
            if (status == null) status = invokeFirstNoArg(entityStatusPacket, "getEventId", "eventId");
            if (entityId != null || status != null) {
                if (entityId instanceof Number number) {
                    appendEntityIdLine(builder, "Entity Id", number.intValue());
                } else if (entityId != null) {
                    builder.line("Entity Id: " + safeLeafString(entityId), AutismColors.textPrimary());
                }
                if (status != null) builder.line("Status: " + safeLeafString(status), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket entityVelocityUpdatePacket) {
            Object entityId = invokeFirstNoArg(entityVelocityUpdatePacket, "getEntityId", "entityId", "id");
            Object velocity = invokeFirstNoArg(entityVelocityUpdatePacket, "getVelocity", "velocity");
            if (velocity == null) velocity = invokeFirstNoArg(entityVelocityUpdatePacket, "movement");
            if (entityId != null || velocity != null) {
                if (entityId instanceof Number number) {
                    appendEntityIdLine(builder, "Entity Id", number.intValue());
                } else if (entityId != null) {
                    builder.line("Entity Id: " + safeLeafString(entityId), AutismColors.textPrimary());
                }
                if (velocity != null) builder.line("Velocity: " + summarizeValue(velocity), colorForSummary(velocity));
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket gameMessagePacket) {
            Object content = invokeFirstNoArg(gameMessagePacket, "content", "getContent");
            Object overlay = invokeFirstNoArg(gameMessagePacket, "overlay", "isOverlay", "getOverlay");
            if (content != null || overlay != null) {
                String message = extractTextValue(content);
                builder.line("Kind: " + (Boolean.TRUE.equals(overlay) ? "Overlay / HUD message" : "Game / system message"),
                    AutismColors.textPrimary());
                ParsedChatLine parsed = parseChatLine(message);
                if (parsed != null) {
                    builder.line("Possible Sender: " + parsed.sender(), AutismColors.textPrimary());
                    builder.line("Message: " + quote(shorten(parsed.message())), AutismColors.successText());
                } else if (message != null && !message.isBlank()) {
                    builder.line("Message: " + quote(shorten(message)), AutismColors.successText());
                } else if (content != null) {
                    builder.line("Message: " + summarizeValue(content), colorForSummary(content));
                }
                if (overlay != null) builder.line("Overlay: " + safeLeafString(overlay), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket profilelessChatMessagePacket) {
            Object message = invokeFirstNoArg(profilelessChatMessagePacket, "message", "getMessage");
            Object chatType = invokeFirstNoArg(profilelessChatMessagePacket, "chatType", "getChatType");
            ChatSummaryState chatSummary = appendChatSummary(message, chatType, null, null, null, "Incoming Chat Message", builder);
            if (chatSummary.wroteAny()) {
                return new SummaryResult(true, chatSummary.complete());
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerChatPacket chatMessageS2CPacket) {
            Object body = firstNonNull(
                invokeFirstNoArg(chatMessageS2CPacket, "body", "getBody"),
                getRecordComponentValue(chatMessageS2CPacket, 4),
                findRecordComponentByMethodNames(chatMessageS2CPacket, 2, "content", "timestamp", "salt")
            );
            Object serializedParameters = firstNonNull(
                invokeFirstNoArg(chatMessageS2CPacket, "serializedParameters", "getSerializedParameters"),
                getRecordComponentValue(chatMessageS2CPacket, 7),
                findRecordComponentByMethodNames(chatMessageS2CPacket, 2, "name", "targetName", "type")
            );
            Object sender = firstNonNull(
                invokeFirstNoArg(chatMessageS2CPacket, "sender", "getSender"),
                getRecordComponentValue(chatMessageS2CPacket, 1),
                findRecordComponentByValueType(chatMessageS2CPacket, UUID.class)
            );
            Object filterMask = firstNonNull(
                invokeFirstNoArg(chatMessageS2CPacket, "filterMask", "getFilterMask"),
                getRecordComponentValue(chatMessageS2CPacket, 6),
                findRecordComponentByMethodNames(chatMessageS2CPacket, 1, "isFullyFiltered", "isPassThrough")
            );
            Object unsignedContent = firstNonNull(
                invokeFirstNoArg(chatMessageS2CPacket, "unsignedContent", "getUnsignedContent"),
                getRecordComponentValue(chatMessageS2CPacket, 5),
                findRecordTextComponent(chatMessageS2CPacket, body, serializedParameters, sender, filterMask)
            );
            Object message = unsignedContent;
            Object timestamp = null;
            if (body != null) {
                Object bodyContent = firstNonNull(
                    invokeFirstNoArg(body, "content", "getContent"),
                    getRecordComponentValue(body, 0),
                    findRecordTextComponent(body)
                );
                if (bodyContent != null && (message == null || extractTextValue(message) == null || extractTextValue(message).isBlank())) {
                    message = bodyContent;
                }
                timestamp = firstNonNull(
                    invokeFirstNoArg(body, "timestamp", "getTimestamp"),
                    getRecordComponentValue(body, 1),
                    findRecordComponentByValueType(body, Instant.class)
                );
            }
            ChatSummaryState chatSummary = appendChatSummary(message, serializedParameters, sender instanceof UUID uuid ? uuid : null, timestamp, filterMask,
                "Incoming Signed Chat Message", builder);
            if (chatSummary.wroteAny()) {
                return new SummaryResult(true, chatSummary.complete());
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket overlayMessagePacket) {
            Object text = invokeFirstNoArg(overlayMessagePacket, "text", "getText");
            String message = extractTextValue(text);
            if (message != null || text != null) {
                builder.line("Kind: Overlay / HUD message", AutismColors.textPrimary());
                builder.line("Message: " + (message == null ? summarizeValue(text) : quote(shorten(message))),
                    message == null ? colorForSummary(text) : AutismColors.successText());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundOpenBookPacket openWrittenBookPacket) {
            Object hand = invokeFirstNoArg(openWrittenBookPacket, "getHand", "hand");
            if (hand != null) {
                builder.line("Interaction: Open Written Book", AutismColors.textPrimary());
                builder.line("Hand: " + safeLeafString(hand), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.common.ClientboundDisconnectPacket disconnectPacket) {
            Object reason = invokeFirstNoArg(disconnectPacket, "reason", "getReason");
            if (reason != null) {
                builder.line("Reason: " + summarizeValue(reason), colorForSummary(reason));
                return new SummaryResult(true, true);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket playerMovePacket) {
            Object x = invokeFirstNoArg(playerMovePacket, "getX", "x");
            Object y = invokeFirstNoArg(playerMovePacket, "getY", "y");
            Object z = invokeFirstNoArg(playerMovePacket, "getZ", "z");
            Object yaw = invokeFirstNoArg(playerMovePacket, "getYaw", "yaw");
            Object pitch = invokeFirstNoArg(playerMovePacket, "getPitch", "pitch");
            Object onGround = invokeFirstNoArg(playerMovePacket, "isOnGround", "onGround");
            if (x != null || y != null || z != null || yaw != null || pitch != null || onGround != null) {
                if (x != null || y != null || z != null) {
                    builder.line("Pos: x=" + safeLeafString(x) + ", y=" + safeLeafString(y) + ", z=" + safeLeafString(z),
                        AutismColors.textPrimary());
                }
                if (yaw != null || pitch != null) {
                    builder.line("Rot: yaw=" + safeLeafString(yaw) + ", pitch=" + safeLeafString(pitch), AutismColors.textPrimary());
                }
                if (onGround != null) builder.line("On Ground: " + safeLeafString(onGround), AutismColors.textPrimary());
                return new SummaryResult(true, true);
            }
        }

        return SummaryResult.NONE;
    }

    private static void dumpRootFields(Object root, InspectionBuilder builder, IdentityHashMap<Object, Boolean> visited) {
        visited.put(root, Boolean.TRUE);
        List<PropertyValue> properties = getInspectableProperties(root);
        List<Field> fields = getInspectableFields(root.getClass());
        Set<String> consumedFieldNames = new LinkedHashSet<>();
        for (PropertyValue property : properties) {
            dumpValue(property.label(), property.value(), 0, builder, visited);
            consumedFieldNames.add(decapitalize(normalizeName(property.label())));
        }
        boolean wroteAny = !properties.isEmpty();
        for (Field field : fields) {
            String fieldLabel = resolveFieldLabel(field);
            if (fieldLabel == null || fieldLabel.isBlank()) continue;
            if (consumedFieldNames.contains(decapitalize(normalizeName(fieldLabel)))) continue;
            dumpValue(fieldLabel, readField(field, root), 0, builder, visited);
            wroteAny = true;
        }
        if (!wroteAny) {
            String rawFallback = rawFallbackString(root);
            if (rawFallback != null) {
                builder.line("Raw: " + rawFallback, AutismColors.textMuted());
            } else {
                builder.line("<No readable fields>", AutismColors.textMuted());
            }
        }
    }

    private static void dumpValue(String label, Object value, int depth, InspectionBuilder builder, IdentityHashMap<Object, Boolean> visited) {
        String indent = "  ".repeat(Math.max(0, depth));
        try {
            dumpValueUnsafe(label, value, depth, indent, builder, visited);
        } catch (Throwable t) {
            builder.line(indent + label + ": <error reading value: " + t.getClass().getSimpleName() + ">", AutismColors.dangerText());
        }
    }

    private static void dumpValueUnsafe(String label, Object value, int depth, String indent, InspectionBuilder builder,
                                        IdentityHashMap<Object, Boolean> visited) {
        if (builder.isFull()) return;

        if (value == INACCESSIBLE) {
            builder.line(indent + label + ": <inaccessible>", AutismColors.textMuted());
            return;
        }
        if (value == null) {
            builder.line(indent + label + ": null", AutismColors.textMuted());
            return;
        }
        if (depth >= MAX_DEPTH) {
            builder.line(indent + label + ": <max depth reached>", AutismColors.textMuted());
            return;
        }

        Class<?> valueClass = value.getClass();
        if (isSimpleValue(value)) {
            builder.line(indent + label + ": " + formatSimpleValue(label, value), semanticFieldColor(label, value));
            return;
        }

        if (value instanceof Optional<?> optional) {
            if (optional.isEmpty()) {
                builder.line(indent + label + ": Optional.empty", AutismColors.textMuted());
                return;
            }
            builder.line(indent + label + ": Optional", AutismColors.textSecondary());
            dumpValue("value", optional.get(), depth + 1, builder, visited);
            return;
        }

        if (value instanceof BlockHitResult hit) {
            builder.line(indent + label + ": BlockHitResult", AutismColors.textSecondary());
            dumpValue("blockPos", hit.getBlockPos(), depth + 1, builder, visited);
            dumpValue("hitPos", hit.getLocation(), depth + 1, builder, visited);
            dumpValue("side", hit.getDirection(), depth + 1, builder, visited);
            dumpValue("type", hit.getType(), depth + 1, builder, visited);
            dumpValue("insideBlock", hit.isInside(), depth + 1, builder, visited);
            return;
        }

        if (value instanceof Map<?, ?> map) {
            builder.line(indent + label + ": Map[" + map.size() + "]", AutismColors.textSecondary());
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (index >= MAX_COLLECTION_ITEMS) {
                    builder.line(indent + "  ... " + (map.size() - index) + " more entries", AutismColors.textMuted());
                    return;
                }
                if (isSimpleValue(entry.getKey()) && isSimpleValue(entry.getValue())) {
                    builder.line(indent + "  [" + index + "] " + formatSimpleValue(entry.getKey()) + " = " + formatSimpleValue(entry.getValue()),
                        AutismColors.textPrimary());
                } else {
                    builder.line(indent + "  [" + index + "]", AutismColors.textSecondary());
                    dumpValue("key", entry.getKey(), depth + 2, builder, visited);
                    dumpValue("value", entry.getValue(), depth + 2, builder, visited);
                }
                index++;
            }
            return;
        }

        if (value instanceof Collection<?> collection) {
            builder.line(indent + label + ": List[" + collection.size() + "]", AutismColors.textSecondary());
            int index = 0;
            for (Object item : collection) {
                if (index >= MAX_COLLECTION_ITEMS) {
                    builder.line(indent + "  ... " + (collection.size() - index) + " more entries", AutismColors.textMuted());
                    return;
                }
                dumpValue("[" + index + "]", item, depth + 1, builder, visited);
                index++;
            }
            return;
        }

        if (valueClass.isArray()) {
            int length = Array.getLength(value);
            if (valueClass.getComponentType().isPrimitive()) {
                builder.line(indent + label + ": " + formatPrimitiveArray(value), AutismColors.textPrimary());
                return;
            }
            builder.line(indent + label + ": " + prettifyClassName(valueClass) + "[" + length + "]", AutismColors.textSecondary());
            for (int i = 0; i < Math.min(length, MAX_ARRAY_ITEMS); i++) {
                dumpValue("[" + i + "]", Array.get(value, i), depth + 1, builder, visited);
            }
            if (length > MAX_ARRAY_ITEMS) {
                builder.line(indent + "  ... " + (length - MAX_ARRAY_ITEMS) + " more entries", AutismColors.textMuted());
            }
            return;
        }

        if (visited.containsKey(value)) {
            builder.line(indent + label + ": <already shown " + prettifyClassName(valueClass) + ">", AutismColors.textMuted());
            return;
        }

        if (shouldTreatAsLeaf(valueClass)) {
            builder.line(indent + label + ": " + safeLeafString(value), semanticFieldColor(label, value));
            return;
        }

        visited.put(value, Boolean.TRUE);
        List<PropertyValue> properties = getInspectableProperties(value);
        List<Field> fields = getInspectableFields(valueClass);
        if (properties.isEmpty() && fields.isEmpty()) {
            builder.line(indent + label + ": " + safeLeafString(value), semanticFieldColor(label, value));
            return;
        }

        builder.line(indent + label + ": " + prettifyClassName(valueClass), AutismColors.textSecondary());
        Set<String> consumedFieldNames = new LinkedHashSet<>();
        for (PropertyValue property : properties) {
            dumpValue(property.label(), property.value(), depth + 1, builder, visited);
            consumedFieldNames.add(decapitalize(normalizeName(property.label())));
        }
        for (Field field : fields) {
            String fieldLabel = resolveFieldLabel(field);
            if (fieldLabel == null || fieldLabel.isBlank()) continue;
            if (consumedFieldNames.contains(decapitalize(normalizeName(fieldLabel)))) continue;
            dumpValue(fieldLabel, readField(field, value), depth + 1, builder, visited);
        }
    }

    private static List<PropertyValue> getInspectableProperties(Object owner) {
        if (owner == null) return List.of();

        Map<String, PropertyValue> properties = new LinkedHashMap<>();
        Class<?> type = owner.getClass();

        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                Object value = invokeAccessor(component.getAccessor(), owner);
                putProperty(properties, resolveRecordComponentLabel(type, component), value, PropertySource.RECORD);
            }
        }

        Arrays.stream(type.getMethods())
            .filter(method -> isInspectableMethod(method))
            .sorted(Comparator.comparing(Method::getName, String.CASE_INSENSITIVE_ORDER))
            .forEach(method -> putProperty(properties, propertyLabelFor(method), invokeAccessor(method, owner), PropertySource.METHOD));

        if (properties.isEmpty()) {
            addFallbackRecordProperties(properties, owner, type);
        }

        return new ArrayList<>(properties.values());
    }

    private static void addFallbackRecordProperties(Map<String, PropertyValue> properties, Object owner, Class<?> type) {
        if (!type.isRecord()) return;
        Map<String, Integer> labelCounts = new LinkedHashMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            Object value = invokeAccessor(component.getAccessor(), owner);
            String alias = firstNonBlank(resolveRecordComponentLabel(type, component),
                fallbackLabelFor(component.getAccessor().getReturnType(), labelCounts));
            putProperty(properties, alias, value, PropertySource.RECORD);
        }
    }

    private static String fallbackLabelFor(Class<?> type, Map<String, Integer> labelCounts) {
        String base = fallbackBaseLabel(type);
        int count = labelCounts.getOrDefault(base, 0) + 1;
        labelCounts.put(base, count);
        return count == 1 ? base : base + " " + count;
    }

    private static String fallbackBaseLabel(Class<?> type) {
        if (type == null) return "Value";
        if (type == boolean.class || type == Boolean.class) return "Flag";
        if (type == String.class || Component.class.isAssignableFrom(type)) return "Component";
        if (type == UUID.class) return "Uuid";
        if (type == Identifier.class) return "Identifier";
        if (type == BlockPos.class) return "Block Pos";
        if (type == Vec3.class) return "Position";
        if (type == ItemStack.class) return "Item";
        if (type == Tag.class) return "Nbt";
        if (type == Instant.class) return "Timestamp";
        if (type.isEnum()) return "Type";
        if (type.isPrimitive()) {
            if (type == int.class || type == long.class || type == short.class || type == byte.class) return "Number";
            if (type == float.class || type == double.class) return "Decimal";
            if (type == char.class) return "Character";
        }
        if (Number.class.isAssignableFrom(type)) return "Number";
        if (Collection.class.isAssignableFrom(type)) return "Entries";
        if (Map.class.isAssignableFrom(type)) return "Map";
        if (Optional.class.isAssignableFrom(type)) return "Optional";
        if (type.isArray()) return fallbackBaseLabel(type.getComponentType()) + " List";
        return prettifyClassName(type);
    }

    private static void putProperty(Map<String, PropertyValue> properties, String label, Object value, PropertySource source) {
        if (label == null || label.isBlank()) return;
        if (isObfuscatedComponentLabel(label)) return;
        if (isIgnoredPropertyLabel(label)) return;
        String key = normalizeName(label);
        if (properties.containsKey(key)) return;
        properties.put(key, new PropertyValue(prettifyLabel(label), value, source));
    }

    private static boolean isInspectableMethod(Method method) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers)) return false;
        if (Modifier.isStatic(modifiers)) return false;
        if (method.isSynthetic()) return false;
        if (method.getParameterCount() != 0) return false;
        if (method.getReturnType() == Void.TYPE) return false;
        if (isIgnoredPropertyLabel(method.getName())) return false;
        String mappedLabel = normalizeAccessorLabel(AutismYarnMappings.lookupMethodLabel(method), method.getReturnType());
        if (isIgnoredPropertyLabel(mappedLabel)) return false;
        return isGetterLikeMethod(method)
            || EXTRA_ACCESSOR_NAMES.contains(method.getName())
            || (mappedLabel != null && EXTRA_ACCESSOR_NAMES.contains(mappedLabel))
            || (mappedLabel != null && !isObfuscatedComponentLabel(mappedLabel));
    }

    private static boolean isGetterLikeMethod(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) return true;
        return name.startsWith("is") && name.length() > 2
            && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class);
    }

    private static String propertyLabelFor(Method method) {
        String mappedLabel = normalizeAccessorLabel(AutismYarnMappings.lookupMethodLabel(method), method.getReturnType());
        if (mappedLabel != null && !mappedLabel.isBlank()) {
            return mappedLabel;
        }
        return normalizeAccessorLabel(method.getName(), method.getReturnType());
    }

    private static Object invokeAccessor(Method method, Object owner) {
        try {
            if (!method.canAccess(owner)) method.setAccessible(true);
            return method.invoke(owner);
        } catch (Throwable ignored) {
            return INACCESSIBLE;
        }
    }

    private static List<Field> getInspectableFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            fields.addAll(Arrays.stream(cursor.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !Modifier.isTransient(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .filter(field -> {
                    String fieldLabel = resolveFieldLabel(field);
                    return fieldLabel != null && !fieldLabel.isBlank();
                })
                .sorted((a, b) -> resolveFieldLabel(a).compareToIgnoreCase(resolveFieldLabel(b)))
                .collect(Collectors.toList()));
            cursor = cursor.getSuperclass();
        }
        return fields;
    }

    private static Object readField(Field field, Object owner) {
        try {
            if (!field.canAccess(owner)) field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable ignored) {
            return INACCESSIBLE;
        }
    }

    private static Object getField(Object owner, String fieldName) {
        if (owner == null || fieldName == null) return null;
        Class<?> cursor = owner.getClass();
        while (cursor != null && cursor != Object.class) {
            try {
                Field field = cursor.getDeclaredField(fieldName);
                if (!field.canAccess(owner)) field.setAccessible(true);
                return field.get(owner);
            } catch (Throwable ignored) {
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static Object firstNonNullField(Object owner, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Object value = getField(owner, fieldName);
            if (value != null) return value;
        }
        return null;
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value != null && value != INACCESSIBLE) return value;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String resolveRecordComponentLabel(Class<?> ownerType, RecordComponent component) {
        if (component == null) return null;
        return firstNonBlank(AutismYarnMappings.lookupMethodLabel(component.getAccessor()), component.getName());
    }

    private static String resolveFieldLabel(Field field) {
        if (field == null) return null;
        return firstNonBlank(AutismYarnMappings.lookupFieldLabel(field),
            isObfuscatedComponentLabel(field.getName()) ? null : field.getName());
    }

    private static Object getRecordComponentValue(Object owner, int index) {
        if (owner == null || index < 0) return null;
        try {
            Class<?> type = owner.getClass();
            if (!type.isRecord()) return null;
            RecordComponent[] components = type.getRecordComponents();
            if (components == null || index >= components.length) return null;
            return invokeAccessor(components[index].getAccessor(), owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findRecordComponentByValueType(Object owner, Class<?> expectedType) {
        if (owner == null || expectedType == null) return null;
        try {
            Class<?> type = owner.getClass();
            if (!type.isRecord()) return null;
            RecordComponent[] components = type.getRecordComponents();
            if (components == null) return null;
            for (RecordComponent component : components) {
                Object value = invokeAccessor(component.getAccessor(), owner);
                if (value != null && value != INACCESSIBLE && expectedType.isInstance(value)) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findRecordComponentByMethodNames(Object owner, int requiredMatches, String... methodNames) {
        if (owner == null || requiredMatches <= 0 || methodNames == null || methodNames.length == 0) return null;
        try {
            Class<?> type = owner.getClass();
            if (!type.isRecord()) return null;
            RecordComponent[] components = type.getRecordComponents();
            if (components == null) return null;
            for (RecordComponent component : components) {
                Object value = invokeAccessor(component.getAccessor(), owner);
                if (value == null || value == INACCESSIBLE) continue;
                int matches = 0;
                for (String methodName : methodNames) {
                    if (hasNoArgMethod(value.getClass(), methodName)) {
                        matches++;
                    }
                }
                if (matches >= requiredMatches) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findRecordTextComponent(Object owner, Object... excludedValues) {
        if (owner == null) return null;
        try {
            Class<?> type = owner.getClass();
            if (!type.isRecord()) return null;
            RecordComponent[] components = type.getRecordComponents();
            if (components == null) return null;
            for (RecordComponent component : components) {
                Object value = invokeAccessor(component.getAccessor(), owner);
                if (value == null || value == INACCESSIBLE || isExcludedReference(value, excludedValues)) continue;
                String text = extractTextValue(value);
                if (text != null && !text.isBlank()) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Integer getIntField(Object owner, String fieldName) {
        Object value = getField(owner, fieldName);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Boolean getBooleanField(Object owner, String fieldName) {
        Object value = getField(owner, fieldName);
        return value instanceof Boolean bool ? bool : null;
    }

    private static Object invokeNoArg(Object owner, String methodName) {
        if (owner == null || methodName == null) return null;
        try {
            Method method = owner.getClass().getMethod(methodName);
            if (!method.canAccess(owner)) method.setAccessible(true);
            return method.invoke(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeFirstNoArg(Object owner, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArg(owner, methodName);
            if (value != null) return value;
        }
        return null;
    }

    private static Object findInspectablePropertyValue(Object owner, String... labels) {
        if (owner == null || labels == null || labels.length == 0) return null;
        List<PropertyValue> properties = getInspectableProperties(owner);
        for (String label : labels) {
            String normalizedTarget = normalizeName(label);
            for (PropertyValue property : properties) {
                if (normalizedTarget.equals(normalizeName(property.label()))) {
                    Object value = property.value();
                    if (value != null && value != INACCESSIBLE) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasNoArgMethod(Class<?> type, String methodName) {
        if (type == null || methodName == null || methodName.isBlank()) return false;
        try {
            type.getMethod(methodName);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isExcludedReference(Object value, Object... excludedValues) {
        if (value == null || excludedValues == null) return false;
        for (Object excluded : excludedValues) {
            if (value == excluded) return true;
        }
        return false;
    }

    private static boolean packetMatchesHint(Packet<?> packet, String packetNameHint, String... names) {
        if (packetNameHint != null) {
            for (String name : names) {
                if (name.equalsIgnoreCase(packetNameHint)) return true;
            }
        }
        String simpleName = packet == null || packet.getClass() == null ? null : packet.getClass().getSimpleName();
        if (simpleName != null) {
            for (String name : names) {
                if (name.equalsIgnoreCase(simpleName)) return true;
            }
        }
        return false;
    }

    private static boolean isSimpleValue(Object value) {
        if (value == null) return true;
        return value instanceof String
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof Character
            || value instanceof Enum<?>
            || value instanceof UUID
            || value instanceof Instant
            || value instanceof Identifier
            || value instanceof Component
            || value instanceof BlockPos
            || value instanceof Vec3
            || value instanceof Entity
            || value instanceof ItemStack
            || value instanceof Tag
            || value instanceof BlockState
            || value instanceof Holder<?>
            || value instanceof BitSet
            || value instanceof Class<?>;
    }

    private static boolean shouldTreatAsLeaf(Class<?> type) {
        if (type == null) return true;
        String name = type.getName();
        return Entity.class.isAssignableFrom(type)
            || ItemStack.class.isAssignableFrom(type)
            || Tag.class.isAssignableFrom(type)
            || name.startsWith("net.minecraft.world.")
            || name.startsWith("net.minecraft.client.")
            || name.startsWith("io.netty.")
            || name.contains("StreamCodec")
            || name.contains("PacketType")
            || name.contains("ByteBuf");
    }

    private static int colorForValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? AutismColors.packetGreen() : AutismColors.packetPink();
        }
        if (value instanceof Entity || value instanceof UUID) {
            return AutismColors.packetCyan();
        }
        if (value instanceof BlockState || value instanceof BlockPos || value instanceof Vec3) {
            return AutismColors.packetYellow();
        }
        if (value instanceof ItemStack || value instanceof Component || value instanceof String) {
            return AutismColors.packetGreen();
        }
        if (value instanceof Enum<?>) {
            return AutismColors.packetOrange();
        }
        if (value instanceof Identifier) {
            return AutismColors.packetBlue();
        }
        if (value instanceof Number || value instanceof Instant || value instanceof BitSet) {
            return AutismColors.packetGray();
        }
        return AutismColors.packetWhite();
    }

    private static int directionColor(String direction) {
        return "C2S".equalsIgnoreCase(direction) ? AutismColors.packetCyan() : AutismColors.packetOrange();
    }

    private static String formatSimpleValue(Object value) {
        return formatSimpleValue("", value);
    }

    private static String formatSimpleValue(String label, Object value) {
        if (value == null) return "null";
        if (value instanceof Number number && isEntityIdLabel(label)) return formatEntityId(number.intValue());
        if (value instanceof String string) return quote(shorten(string));
        if (value instanceof Character c) return quote(String.valueOf(c));
        if (value instanceof Component text) return quote(shorten(text.getString()));
        if (value instanceof Identifier id) return id.toString();
        if (value instanceof UUID uuid) return uuid.toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        if (value instanceof BlockPos pos) return formatBlockPos(pos);
        if (value instanceof Vec3 vec) return formatVec3(vec);
        if (value instanceof Entity entity) return formatEntity(entity);
        if (value instanceof ItemStack stack) return formatItemStack(stack);
        if (value instanceof Tag nbt) return shorten(nbt.asString().orElse(String.valueOf(nbt)));
        if (value instanceof BlockState state) return formatBlockState(state);
        if (value instanceof Holder<?> entry) return formatHolder(entry);
        if (value instanceof BitSet bitSet) return formatBitSet(bitSet);
        if (value instanceof Class<?> clazz) return prettifyClassName(clazz);
        return rewriteKnownClassAliases(shorten(String.valueOf(value)));
    }

    private static String safeLeafString(Object value) {
        if (value == null) return "null";
        if (isSimpleValue(value)) return formatSimpleValue(value);
        try {
            return rewriteKnownClassAliases(shorten(String.valueOf(value)));
        } catch (Throwable ignored) {
            return "<unprintable " + prettifyClassName(value.getClass()) + ">";
        }
    }

    private static String rawFallbackString(Object value) {
        if (value == null) return null;
        try {
            String raw = rewriteKnownClassAliases(shorten(String.valueOf(value)));
            if (raw == null || raw.isBlank()) return null;
            if (looksLikeDefaultObjectString(raw)) return null;
            return raw;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeDefaultObjectString(String raw) {
        return raw != null && raw.matches("[\\w.$]+@[0-9a-fA-F]+");
    }

    private static String formatHotbarSlot(int slot) {
        if (slot >= 0 && slot <= 8) {
            return (slot + 1) + " (hotbar index " + slot + ")";
        }
        return String.valueOf(slot);
    }

    private static String describePlayerAction(ServerboundPlayerActionPacket.Action action) {
        return switch (action) {
            case START_DESTROY_BLOCK -> "Start breaking targeted block";
            case ABORT_DESTROY_BLOCK -> "Abort breaking targeted block";
            case STOP_DESTROY_BLOCK -> "Finish breaking targeted block";
            case DROP_ALL_ITEMS -> "Drop full held stack";
            case DROP_ITEM -> "Drop one held item";
            case RELEASE_USE_ITEM -> "Release item use";
            case SWAP_ITEM_WITH_OFFHAND -> "Swap main hand and offhand";
            default -> prettifyClassName(action.name());
        };
    }

    private static String describeContainerInput(Object actionType) {
        if (!(actionType instanceof Enum<?> enumValue)) {
            return actionType == null ? "Click Slot" : "Click Slot (" + safeLeafString(actionType) + ")";
        }
        return switch (enumValue.name()) {
            case "PICKUP" -> "Pick up / place stack";
            case "QUICK_MOVE" -> "Quick move / shift-click";
            case "SWAP" -> "Swap with hotbar slot";
            case "CLONE" -> "Clone stack";
            case "THROW" -> "Throw item";
            case "QUICK_CRAFT" -> "Drag split across slots";
            case "PICKUP_ALL" -> "Pick up all matching items";
            default -> "Click Slot (" + enumValue.name() + ")";
        };
    }

    private static String extractTextValue(Object value) {
        if (value == null || value == INACCESSIBLE) return null;
        if (value instanceof Component text) return text.getString();
        if (value instanceof String string) return string;
        if (value instanceof Optional<?> optional) return optional.map(AutismPacketInspector::extractTextValue).orElse(null);
        if (value instanceof String[] array) return String.join(" | ", array);
        Object stringLike = invokeFirstNoArg(value, "getString", "string", "content", "raw", "name");
        if (stringLike instanceof Component text) return text.getString();
        if (stringLike instanceof String string) return string;
        return null;
    }

    private static String summarizeBookPages(Collection<?> pages) {
        List<String> previews = new ArrayList<>();
        int index = 0;
        for (Object page : pages) {
            String text = extractTextValue(page);
            if ((text == null || text.isBlank()) && page != null) {
                text = safeLeafString(page);
            }
            if (text != null && !text.isBlank()) {
                previews.add("p" + (index + 1) + "=" + quote(shorten(text)));
            }
            index++;
            if (previews.size() >= 2) break;
        }
        if (previews.isEmpty()) return null;
        return String.join(" | ", previews);
    }

    private static String summarizeSignLines(String[] lines) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isEmpty()) continue;
            parts.add("L" + (i + 1) + "=" + quote(shorten(line)));
        }
        return parts.isEmpty() ? "<empty sign>" : String.join(" | ", parts);
    }

    private static ChatSummaryState appendChatSummary(Object messageValue, Object parameters, UUID senderUuid, Object timestampValue,
                                                      Object filterMask, String kind, InspectionBuilder builder) {
        boolean wrote = false;
        boolean informative = false;
        if (kind != null && !kind.isBlank()) {
            builder.line("Kind: " + kind, semanticFieldColor("Kind", kind));
            wrote = true;
        }

        String chatType = extractMessageType(parameters);
        if (chatType != null && !chatType.isBlank()) {
            builder.line("Chat Type: " + chatType, semanticFieldColor("Chat Type", chatType));
            wrote = true;
            informative = true;
        }

        String sender = extractMessageSender(parameters, senderUuid);
        if (sender != null && !sender.isBlank()) {
            builder.line("Sender: " + sender, semanticFieldColor("Sender", sender));
            wrote = true;
            informative = true;
        }

        String target = extractMessageTarget(parameters);
        if (target != null && !target.isBlank()) {
            builder.line("Target: " + target, semanticFieldColor("Target", target));
            wrote = true;
            informative = true;
        }

        String message = extractTextValue(messageValue);
        if (message != null && !message.isBlank()) {
            builder.line("Message: " + quote(shorten(message)), semanticFieldColor("Message", message));
            wrote = true;
            informative = true;
        } else if (messageValue != null) {
            builder.line("Message: " + summarizeValue(messageValue), colorForSummary("Message", messageValue));
            wrote = true;
            informative = true;
        }

        if (timestampValue != null) {
            builder.line("Timestamp: " + safeLeafString(timestampValue), semanticFieldColor("Timestamp", timestampValue));
            wrote = true;
            informative = true;
        }

        String filter = describeFilterMask(filterMask);
        if (filter != null) {
            builder.line("Filter: " + filter, semanticFieldColor("Filter", filter));
            wrote = true;
            informative = true;
        }
        return new ChatSummaryState(wrote, informative);
    }

    private static String extractMessageSender(Object parameters, UUID senderUuid) {
        String senderName = extractTextValue(firstNonNull(
            invokeNoArg(parameters, "name"),
            getRecordComponentValue(parameters, 1),
            findRecordTextComponent(parameters, getRecordComponentValue(parameters, 0), getRecordComponentValue(parameters, 2))
        ));
        if (senderName == null || senderName.isBlank()) {
            senderName = resolvePlayerName(senderUuid);
        }
        if (senderName != null && !senderName.isBlank()) {
            return senderUuid == null ? shorten(senderName) : shorten(senderName) + " (" + senderUuid + ")";
        }
        return senderUuid == null ? null : senderUuid.toString();
    }

    private static String extractMessageTarget(Object parameters) {
        String targetName = extractTextValue(firstNonNull(
            invokeNoArg(parameters, "targetName"),
            getRecordComponentValue(parameters, 2)
        ));
        return (targetName == null || targetName.isBlank()) ? null : shorten(targetName);
    }

    private static String extractMessageType(Object parameters) {
        Object type = firstNonNull(
            invokeNoArg(parameters, "type"),
            getRecordComponentValue(parameters, 0)
        );
        return type == null ? null : safeLeafString(type);
    }

    private static String describeFilterMask(Object filterMask) {
        if (filterMask == null) return null;
        Object fullyFiltered = invokeNoArg(filterMask, "isFullyFiltered");
        if (Boolean.TRUE.equals(fullyFiltered)) return "Fully filtered";
        Object passThrough = invokeNoArg(filterMask, "isPassThrough");
        if (Boolean.TRUE.equals(passThrough)) return "Pass-through";
        return "Partially filtered";
    }

    private static String resolvePlayerName(UUID uuid) {
        if (uuid == null) return null;
        try {
            if (MC.getConnection() != null) {
                var entry = MC.getConnection().getPlayerInfo(uuid);
                if (entry != null && entry.getProfile() != null) {
                    String profileName = extractTextValue(entry.getProfile());
                    if (profileName != null && !profileName.isBlank()) {
                        return profileName;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (MC.level != null) {
            for (var player : MC.level.players()) {
                    if (uuid.equals(player.getUUID()) && player.getGameProfile() != null) {
                        String profileName = extractTextValue(player.getGameProfile());
                        if (profileName != null && !profileName.isBlank()) {
                            return profileName;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static ParsedChatLine parseChatLine(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;

        if (text.startsWith("<")) {
            int end = text.indexOf("> ");
            if (end > 1 && end + 2 < text.length()) {
                return new ParsedChatLine(text.substring(1, end).trim(), text.substring(end + 2).trim());
            }
        }

        int colon = text.indexOf(": ");
        if (colon > 0 && colon <= 48 && colon + 2 < text.length()) {
            String sender = text.substring(0, colon).trim();
            String message = text.substring(colon + 2).trim();
            if (!sender.isEmpty() && !message.isEmpty()) {
                return new ParsedChatLine(sender, message);
            }
        }
        return null;
    }

    private static String formatEntity(Entity entity) {
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String name = entity.getDisplayName() == null ? "" : entity.getDisplayName().getString();
        return typeId + " #" + entity.getId() + " [" + shorten(name) + "] @" + formatVec3(new Vec3(entity.getX(), entity.getY(), entity.getZ()));
    }

    private static void appendEntityIdLine(InspectionBuilder builder, String label, int entityId) {
        builder.line(label + ": " + formatEntityId(entityId), AutismColors.packetCyan());
    }

    private static String formatEntityId(int entityId) {
        Entity entity = resolveEntity(entityId);
        if (entity != null) {
            return entityId + " -> " + formatEntity(entity);
        }
        return entityId + " (entity not loaded in client world)";
    }

    private static String formatEntityIdList(Object ids) {
        if (ids == null || ids == INACCESSIBLE) return "null";
        List<String> parts = new ArrayList<>();
        int total = 0;
        if (ids instanceof int[] array) {
            total = array.length;
            for (int i = 0; i < Math.min(array.length, 8); i++) parts.add(formatEntityId(array[i]));
        } else if (ids instanceof Collection<?> collection) {
            total = collection.size();
            int shown = 0;
            for (Object id : collection) {
                if (shown >= 8) break;
                if (id instanceof Number number) {
                    parts.add(formatEntityId(number.intValue()));
                } else {
                    parts.add(summarizeValue(id));
                }
                shown++;
            }
        } else if (ids.getClass().isArray()) {
            int length = Array.getLength(ids);
            total = length;
            for (int i = 0; i < Math.min(length, 8); i++) {
                Object id = Array.get(ids, i);
                parts.add(id instanceof Number number ? formatEntityId(number.intValue()) : summarizeValue(id));
            }
        } else {
            return summarizeValue(ids);
        }
        if (total > parts.size()) parts.add("... +" + (total - parts.size()) + " more");
        return "[" + total + "] " + String.join(", ", parts);
    }

    private static String formatCollectionPreview(Collection<?> collection) {
        if (collection == null) return "null";
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Object item : collection) {
            if (shown >= 6) break;
            parts.add(summarizeValue(item));
            shown++;
        }
        if (collection.size() > shown) parts.add("... +" + (collection.size() - shown) + " more");
        return "[" + collection.size() + "] " + String.join(", ", parts);
    }

    private static Entity resolveEntity(int entityId) {
        try {
            return MC.level == null ? null : MC.level.getEntity(entityId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isEntityIdLabel(String label) {
        String normalized = normalizeName(label);
        return normalized.equals("entityid")
            || normalized.equals("entity")
            || normalized.equals("entityindex")
            || normalized.equals("targetentityid")
            || normalized.equals("sourceentityid")
            || normalized.endsWith("entityid");
    }

    private static String formatEntityType(EntityType<?> type) {
        if (type == null) return "Unknown";
        try {
            return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        } catch (Throwable ignored) {
            return safeLeafString(type);
        }
    }

    private static String formatItemStack(ItemStack stack) {
        if (stack.isEmpty()) return "ItemStack.EMPTY";
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String name = stack.getHoverName() == null ? "" : stack.getHoverName().getString();
        String display = itemId;
        if (!name.isEmpty() && !Objects.equals(name, stack.getItem().getName(stack).getString())) {
            display += " [" + shorten(name) + "]";
        }
        return stack.getCount() + "x " + display;
    }

    private static String formatBlockState(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " " + shorten(state.toString());
    }

    private static String formatBlockPos(BlockPos pos) {
        return "x=" + pos.getX() + ", y=" + pos.getY() + ", z=" + pos.getZ();
    }

    private static String formatVec3(Vec3 vec) {
        return String.format(Locale.ROOT, "x=%.3f, y=%.3f, z=%.3f", vec.x, vec.y, vec.z);
    }

    private static String formatBitSet(BitSet bitSet) {
        int shown = 0;
        StringBuilder builder = new StringBuilder("BitSet{");
        for (int bit = bitSet.nextSetBit(0); bit >= 0; bit = bitSet.nextSetBit(bit + 1)) {
            if (shown >= 16) {
                builder.append("...");
                break;
            }
            if (shown > 0) builder.append(", ");
            builder.append(bit);
            shown++;
        }
        builder.append("}");
        return builder.toString();
    }

    private static String formatHolder(Holder<?> entry) {
        try {
            Optional<?> key = entry.unwrapKey();
            if (key.isPresent()) {
                return shorten(String.valueOf(key.get()));
            }
        } catch (Throwable ignored) {
        }
        try {
            Object value = invokeNoArg(entry, "value");
            if (value != null) {
                return shorten(String.valueOf(value));
            }
        } catch (Throwable ignored) {
        }
        return shorten(String.valueOf(entry));
    }

    private static String summarizeValue(Object value) {
        return summarizeValue("", value);
    }

    private static String summarizeValue(String label, Object value) {
        if (value == null || value == INACCESSIBLE) return null;
        if (isSimpleValue(value)) return formatSimpleValue(label, value);
        if (value instanceof Optional<?> optional) {
            return optional.map(item -> summarizeValue(label, item)).orElse("Optional.empty");
        }
        if (value instanceof Collection<?> collection) {
            return prettifyClassName(value.getClass()) + "[" + collection.size() + "]";
        }
        if (value instanceof Map<?, ?> map) {
            return "Map[" + map.size() + "]";
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return type.getComponentType().getSimpleName() + "[" + Array.getLength(value) + "]";
        }
        return safeLeafString(value);
    }

    private static int colorForSummary(String label, Object value) {
        if (value == null || value == INACCESSIBLE) return AutismColors.textMuted();
        if (value instanceof Collection<?> || value instanceof Map<?, ?> || value.getClass().isArray()) {
            return AutismColors.textSecondary();
        }
        return semanticFieldColor(label, value);
    }

    private static int colorForSummary(Object value) {
        return colorForSummary("", value);
    }

    private static int semanticFieldColor(String label, Object value) {
        String normalized = normalizeName(label);
        if (value instanceof Boolean bool) {
            return bool ? AutismColors.packetGreen() : AutismColors.packetPink();
        }
        if (normalized.contains("sender") || normalized.contains("author") || normalized.contains("player")
            || normalized.equals("entity") || normalized.contains("entityid") || normalized.contains("owner")) {
            return AutismColors.packetCyan();
        }
        if (normalized.contains("target") || normalized.contains("recipient") || normalized.contains("team")) {
            return AutismColors.packetPink();
        }
        if (normalized.contains("message") || normalized.contains("content") || normalized.equals("text")
            || normalized.contains("title") || normalized.contains("book") || normalized.contains("page")
            || normalized.contains("sign") || normalized.contains("recipe") || normalized.contains("item")
            || normalized.contains("stack")) {
            return AutismColors.packetGreen();
        }
        if (normalized.equals("kind") || normalized.contains("action") || normalized.contains("interaction")
            || normalized.equals("type") || normalized.contains("mode") || normalized.contains("reason")
            || normalized.contains("status") || normalized.contains("hand")) {
            return AutismColors.packetOrange();
        }
        if (normalized.contains("timestamp") || normalized.contains("time") || normalized.contains("sequence")
            || normalized.contains("salt")) {
            return AutismColors.packetLightYellow();
        }
        if (normalized.contains("slot") || normalized.contains("syncid") || normalized.contains("index")
            || normalized.contains("revision") || normalized.contains("checksum") || normalized.contains("offset")) {
            return AutismColors.packetBlue();
        }
        if (normalized.contains("sound") || normalized.contains("channel") || normalized.contains("identifier")
            || normalized.contains("packettype")) {
            return AutismColors.packetBlue();
        }
        if (normalized.contains("block") || normalized.contains("pos") || normalized.equals("x")
            || normalized.equals("y") || normalized.equals("z") || normalized.contains("yaw")
            || normalized.contains("pitch") || normalized.contains("velocity")) {
            return AutismColors.packetYellow();
        }
        if (normalized.contains("filter")) {
            String text = value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
            if (text.contains("fully filtered")) return AutismColors.packetPink();
            if (text.contains("pass-through")) return AutismColors.packetGreen();
            return AutismColors.packetYellow();
        }
        return colorForValue(value);
    }

    private static String prettifyLabel(String label) {
        return prettifyClassName(label)
            .replace('.', ' ')
            .trim();
    }

    private static String decapitalize(String value) {
        if (value == null || value.isEmpty()) return "";
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isObfuscatedComponentLabel(String label) {
        if (label == null || label.isBlank()) return false;
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("(comp|field|method|class)_?\\d+");
    }

    private static boolean isIgnoredPropertyLabel(String label) {
        if (label == null || label.isBlank()) return false;
        return IGNORED_PROPERTY_METHODS.contains(label) || IGNORED_PROPERTY_NORMALIZED.contains(normalizeName(label));
    }

    private static int countNonEmpty(List<ItemStack> stacks) {
        int count = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) count++;
        }
        return count;
    }

    private static InteractionCapture captureInteraction(ServerboundInteractPacket packet) {
        InteractionCapture capture = new InteractionCapture();
        try {
            capture.kind = "Interact At";
            capture.hand = packet.hand();
            capture.hitPos = packet.location();
        } catch (Throwable ignored) {
            Object interactionType = getField(packet, "type");
            if (interactionType != null && interactionType != INACCESSIBLE) {
                capture.kind = prettifyClassName(interactionType.getClass());
                Object hand = firstNonNullField(interactionType, "hand", "interactionHand");
                if (hand instanceof InteractionHand actualHand) {
                    capture.hand = actualHand;
                }
                Object hitPos = firstNonNullField(interactionType, "pos", "hitPos", "location");
                if (hitPos instanceof Vec3 actualHitPos) {
                    capture.hitPos = actualHitPos;
                }
            }
        }
        return capture;
    }

    private static String formatPrimitiveArray(Object array) {
        int length = Array.getLength(array);
        StringBuilder builder = new StringBuilder();
        builder.append(array.getClass().getComponentType().getSimpleName()).append('[').append(length).append("] ");
        builder.append('[');
        int shown = Math.min(length, MAX_ARRAY_ITEMS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) builder.append(", ");
            builder.append(Array.get(array, i));
        }
        if (length > shown) builder.append(", ...");
        builder.append(']');
        return builder.toString();
    }

    private static String prettifyClassName(Class<?> type) {
        if (type == null) return "Unknown";
        String alias = AutismYarnMappings.lookupClassAlias(type);
        if (alias != null && !alias.isBlank()) {
            return prettifyClassName(alias);
        }
        return prettifyClassName(type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName());
    }

    private static String prettifyClassName(String value) {
        if (value == null || value.isEmpty()) return "";
        String alias = firstNonBlank(
            AutismYarnMappings.lookupClassAlias(value.replace('/', '.')),
            AutismYarnMappings.lookupSimpleClassAlias(value.substring(value.lastIndexOf('.') + 1))
        );
        String source = (alias == null ? value : alias).replace('$', '.');
        if (source.endsWith("Packet")) {
            source = source.substring(0, source.length() - 6);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (i > 0 && Character.isUpperCase(ch) && Character.isLowerCase(source.charAt(i - 1))) {
                builder.append(' ');
            }
            builder.append(ch);
        }
        return builder.toString();
    }

    private static String normalizeAccessorLabel(String label, Class<?> returnType) {
        if (label == null || label.isBlank()) return label;
        if (label.startsWith("get") && label.length() > 3 && Character.isUpperCase(label.charAt(3))) {
            return label.substring(3);
        }
        if (label.startsWith("is") && label.length() > 2
            && Character.isUpperCase(label.charAt(2))
            && (returnType == boolean.class || returnType == Boolean.class)) {
            return label.substring(2);
        }
        return label;
    }

    private static String summarizeEquipmentEntries(Collection<?> equipmentEntries) {
        if (equipmentEntries == null || equipmentEntries.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Object entry : equipmentEntries) {
            if (shown >= 6) {
                parts.add("... +" + (equipmentEntries.size() - shown) + " more");
                break;
            }
            String summary = summarizeEquipmentEntry(entry);
            if (summary != null && !summary.isBlank()) {
                parts.add(summary);
                shown++;
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String summarizeEquipmentEntry(Object entry) {
        if (entry == null) return null;
        Object slot = firstNonNull(
            invokeFirstNoArg(entry, "getFirst", "first", "slot", "getSlot"),
            getRecordComponentValue(entry, 0),
            getField(entry, "first")
        );
        Object stack = firstNonNull(
            invokeFirstNoArg(entry, "getSecond", "second", "stack", "getStack"),
            getRecordComponentValue(entry, 1),
            getField(entry, "second")
        );
        if (slot == null && stack == null) {
            return summarizeValue(entry);
        }
        String slotComponent = slot == null ? "Unknown Slot" : safeLeafString(slot);
        String stackComponent = stack instanceof ItemStack itemStack ? formatItemStack(itemStack) : summarizeValue(stack);
        return slotComponent + "=" + stackComponent;
    }

    private static String rewriteKnownClassAliases(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        String rewritten = replaceAliases(raw, QUALIFIED_CLASS_ALIAS_PATTERN, true);
        rewritten = replaceAliases(rewritten, SIMPLE_CLASS_ALIAS_PATTERN, false);
        return rewritten;
    }

    private static String replaceAliases(String raw, Pattern pattern, boolean qualified) {
        Matcher matcher = pattern.matcher(raw);
        StringBuffer buffer = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String original = matcher.group(1);
            String alias = qualified
                ? AutismYarnMappings.lookupClassAlias(original)
                : AutismYarnMappings.lookupSimpleClassAlias(original);
            if (alias == null || alias.isBlank()) continue;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(alias));
            changed = true;
        }
        if (!changed) return raw;
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static String shorten(String value) {
        if (value == null) return "";
        String sanitized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (sanitized.length() <= MAX_STRING_LENGTH) return sanitized;
        return sanitized.substring(0, MAX_STRING_LENGTH - 3) + "...";
    }

    private static BlockState getWorldBlockState(BlockPos pos) {
        try {
            return MC.level != null ? MC.level.getBlockState(pos) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static final class PacketInspection {
        private final String title;
        private final List<InspectionLine> lines;
        private final String copyText;

        private PacketInspection(String title, List<InspectionLine> lines, String copyText) {
            this.title = title;
            this.lines = Collections.unmodifiableList(lines);
            this.copyText = copyText;
        }

        public String getTitle() {
            return title;
        }

        public List<InspectionLine> getLines() {
            return lines;
        }

        public String getCopyText() {
            return copyText;
        }
    }

    public static final class InspectionLine {
        private final String text;
        private final int color;

        public InspectionLine(String text, int color) {
            this.text = text;
            this.color = color;
        }

        public String getText() {
            return text;
        }

        public int getColor() {
            return color;
        }
    }

    private record PropertyValue(String label, Object value, PropertySource source) {
    }

    private record ParsedChatLine(String sender, String message) {
    }

    private record SummaryResult(boolean wroteAny, boolean complete) {
        private static final SummaryResult NONE = new SummaryResult(false, false);
    }

    private record ChatSummaryState(boolean wroteAny, boolean complete) {
    }

    private enum PropertySource {
        RECORD,
        METHOD
    }

    private static final class InteractionCapture {
        private String kind;
        private InteractionHand hand;
        private Vec3 hitPos;
    }

    private static final class InspectionBuilder {
        private final String title;
        private final List<InspectionLine> lines = new ArrayList<>();
        private final StringBuilder plain = new StringBuilder();
        private boolean truncated;

        private InspectionBuilder(String title) {
            this.title = title;
        }

        void section(String title, int color) {
            line("[" + title + "]", color);
        }

        void line(String text, int color) {
            if (truncated) return;
            if (lines.size() >= MAX_LINES) {
                truncated = true;
                lines.add(new InspectionLine("... output truncated ...", AutismColors.dangerText()));
                plain.append("... output truncated ...\n");
                return;
            }
            lines.add(new InspectionLine(text, color));
            plain.append(text).append('\n');
        }

        void blank() {
            line("", AutismColors.textPrimary());
        }

        void appendFrom(InspectionBuilder other) {
            if (other == null) return;
            for (InspectionLine line : other.lines) {
                this.line(line.getText(), line.getColor());
            }
        }

        boolean isFull() {
            return truncated;
        }

        PacketInspection build() {
            return new PacketInspection(title, lines, plain.toString());
        }
    }
}
