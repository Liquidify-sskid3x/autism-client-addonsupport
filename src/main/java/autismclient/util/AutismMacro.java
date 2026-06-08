package autismclient.util;

import autismclient.util.macro.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AutismMacro {
    public String name = "New Macro";
    public String description = "";
    public boolean loop = false;
    public int loopCount = -1;
    public int keyCode = -1;
    public List<MacroAction> actions = new ArrayList<>();

    public AutismMacro() {}

    public AutismMacro(String name) {
        this.name = name;
    }

    public AutismMacro deepCopy() {
        return new AutismMacro().fromTag(this.toTag());
    }

    public AutismMacro deepCopy(String newName) {
        AutismMacro copy = deepCopy();
        if (newName != null && !newName.isBlank()) {
            copy.name = newName;
        }
        return copy;
    }

    public void execute() {
        execute(true);
    }

    public void execute(boolean regenerate) {
        executeTracked(regenerate);
    }

    public long executeTracked() {
        return executeTracked(true);
    }

    public long executeTracked(boolean regenerate) {
        if (actions.isEmpty()) {
            AutismClientMessaging.sendPrefixed("§cMacro has no actions!");
            return -1L;
        }

        if (regenerate) {
            regenerateAllPackets();
        }
        return MacroExecutor.executeTracked(this);
    }

    public void regenerateAllPackets() {
        for (MacroAction action : actions) {
            if (action instanceof SendPacketAction) {
                ((SendPacketAction) action).regeneratePackets();
            }
        }
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("description", description);
        tag.putBoolean("loop", loop);
        tag.putInt("loopCount", loopCount);
        tag.putInt("keyCode", keyCode);

        ListTag actionsList = new ListTag();
        for (MacroAction action : actions) {
            actionsList.add(action.toTag());
        }
        tag.put("actions", actionsList);

        return tag;
    }

    public AutismMacro fromTag(CompoundTag tag) {
        if (tag.contains("name")) name = tag.getStringOr("name", "");
        if (tag.contains("description")) description = tag.getStringOr("description", "");
        if (tag.contains("loop")) loop = tag.getBooleanOr("loop", false);
        if (tag.contains("loopCount")) loopCount = tag.getIntOr("loopCount", -1);
        if (tag.contains("keyCode")) keyCode = tag.getIntOr("keyCode", -1);

        if (tag.contains("actions")) {

            List<MacroAction> actions = new ArrayList<>();
            ListTag actionsList = (ListTag) tag.get("actions");
            for (Tag element : actionsList) {
                if (element instanceof CompoundTag) {
                    CompoundTag actionTag = (CompoundTag) element;
                    if (actionTag.contains("type")) {
                        try {
                            String typeName = actionTag.getStringOr("type", "");
                            MacroAction migratedLegacy = createHardDebloatMigration(typeName, actionTag);
                            if (migratedLegacy != null) {
                                actions.add(migratedLegacy);
                                continue;
                            }
                            MacroActionType type = MacroActionType.valueOf(typeName);
                            MacroAction action = null;
                            switch (type) {
                                case DELAY: action = new DelayAction(); break;
                                case PACKET: action = new PacketAction(); break;
                                case PACKET_CLICK: action = new PacketClickAction(); break;
                                case WAIT_PACKET: action = new WaitForPacketAction(); break;
                                case WAIT_HEALTH: action = new WaitForHealthAction(); break;
                                case WAIT_ITEM: {

                                    WaitForSlotChangeAction migrated = new WaitForSlotChangeAction();
                                    migrated.fromTagLegacyItem(actionTag);
                                    action = migrated;
                                    break;
                                }
                                case WAIT_BLOCK: action = new WaitForBlockAction(); break;
                                case WAIT_GUI: action = new WaitForGuiAction(); break;
                                case CLICK: action = new ClickAction(); break;
                                case ROTATE: action = new RotateAction(); break;
                                case USE_ITEM: action = new UseItemAction(); break;
                                case INVENTORY: action = new InventoryAction(); break;
                                case SEND_PACKET: action = new SendPacketAction(); break;
                                case CRAFT: action = new CraftAction(); break;
                                case SELECT_SLOT: action = new SelectSlotAction(); break;
                                case XCARRY: action = new XCarryAction(); break;
                                case DROP: action = new DropAction(); break;
                                case ITEM: action = new ItemAction(); break;
                                case TICK_SYNC: action = new TickSyncAction(); break;
                                case REVISION_SYNC: action = new RevisionSyncAction(); break;
                                case SERVER_TICK_SYNC: action = new ServerTickSyncAction(); break;
                                case CLOSE_GUI: action = new CloseGuiAction(); break;
                                case SWAP_SLOTS: action = new SwapSlotsAction(); break;
                                case WAIT_COOLDOWN: action = new WaitForCooldownAction(); break;
                                case GO_TO: action = new GoToAction(); break;
                                case WAIT_POS: action = new WaitPosAction(); break;
                                case PAYLOAD: action = new PayloadAction(); break;
                                case DISCONNECT: action = new DisconnectAction(); break;
                                case TOGGLE_MODULE: action = new ToggleModuleAction(); break;
                                case START_MACRO: action = new StartMacroAction(); break;
                                case STOP_MACRO: action = new StopMacroAction(); break;
                                case SNEAK: action = new SneakAction(); break;
                                case JUMP: action = new JumpAction(); break;
                                case SPRINT: action = new SprintAction(); break;
                                case MOVE: action = new MoveAction(); break;
                                case LOOK_AT_BLOCK: action = new LookAtBlockAction(); break;
                                case REPEAT: action = new RepeatAction(); break;
                                case WAIT_CHAT: action = new WaitForChatAction(); break;
                                case WAIT_ENTITY: action = new WaitForEntityAction(); break;
                                case WAIT_SLOT_CHANGE: action = new WaitForSlotChangeAction(); break;
                                case OPEN_CONTAINER: action = new OpenContainerAction(); break;
                                case INTERACT_ENTITY: action = new autismclient.util.macro.InteractEntityAction(); break;
                                case DESYNC: action = new DesyncAction(); break;
                                case RESTORE_GUI: action = new RestoreGuiAction(); break;
                                case SAVE_GUI: action = new SaveGuiAction(); break;
                                case SEND_TOGGLE: action = new SendToggleAction(); break;
                                case DELAY_PACKETS: action = new DelayPacketsAction(); break;
                                case INVENTORY_AUDIT: action = new InventoryAuditAction(); break;
                                case STORE_ITEM: action = new StoreItemAction(); break;
                                case WAIT_SOUND: action = new WaitForSoundAction(); break;
                                case MINE: action = new MineAction(); break;
                                case INSTA_BREAK: action = new InstaBreakAction(); break;
                                case BREAK: action = new BreakAction(); break;
                                case PAY: action = new PayAction(); break;
                                case NBT_BOOK: action = new NbtBookAction(); break;
                                case SEND_CHAT: action = new SendChatAction(); break;
                                case WAIT_LAN_STEP: action = new WaitForLanStepAction(); break;
                                case WAIT_MACRO_STEP: action = new WaitForMacroStepAction(); break;
                                case WAIT_WORLD_CHANGE: action = new WaitForWorldChangeAction(); break;
                                case WAIT_POSITION_DELTA: action = new WaitForPositionDeltaAction(); break;
                                case WAIT_TELEPORT: action = new WaitForTeleportAction(); break;
                                case WAIT_GAMEMODE_CHANGE: action = new WaitGamemodeChangeAction(); break;
                                case WAIT_MOVEMENT: action = new WaitMovementAction(); break;
                                case RACE: action = new autismclient.util.macro.RaceAction(); break;
                                case REPORT: action = new autismclient.util.macro.ReportAction(); break;
                                case VCLIP: action = new autismclient.util.macro.VClipAction(); break;
                                case HCLIP: action = new autismclient.util.macro.HClipAction(); break;
                                case PACKET_GATE: action = new PacketGateAction(); break;
                                case END_PACKET_GATE: action = new EndPacketGateAction(); break;
                                case PACKET_BURST: action = new PacketBurstAction(); break;
                                case CONTAINER_CLICK_SEQUENCE: action = new ContainerClickSequenceAction(); break;
                                case ASSERT: action = new AssertAction(); break;
                                case USE_ITEM_PHASE: action = new UseItemPhaseAction(); break;
                                case SEND_COMMAND_PACKET: action = new SendCommandPacketAction(); break;
                                case WAIT_PACKET_MATCH: action = new WaitPacketMatchAction(); break;
                                case WAIT_INVENTORY_PREDICATE: action = new WaitInventoryPredicateAction(); break;
                                case WAIT_DURABILITY: action = new WaitDurabilityAction(); break;
                                case WAIT_FREE_SLOTS: action = new WaitFreeSlotsAction(); break;
                                case WAIT_ENTITY_TARGET: action = new WaitEntityTargetAction(); break;
                                case WAIT_GUI_TYPE: action = new WaitGuiTypeAction(); break;
                                case BRANCH: action = new BranchAction(); break;
                                case FINALLY: action = new FinallyAction(); break;
                                case MACRO_VARIABLES: action = new MacroVariablesAction(); break;
                                case FAKE_GAMEMODE: action = new FakeGamemodeAction(); break;
                                case BUNDLE_DUPE_V2: action = new BundleDupeV2Action(); break;
                                case PLACE: action = new PlaceAction(); break;
                                case SIGN_EDIT: action = new SignEditAction(); break;
                            }

                            if (action != null) {

                                if (type != MacroActionType.WAIT_ITEM) action.fromTag(actionTag);
                                actions.add(action);
                            }
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
            this.actions = actions;
            migrateLegacyRaceGroups();
        }
        return this;
    }

    public static MacroAction createActionFromTag(CompoundTag actionTag) {
        if (actionTag == null || !actionTag.contains("type")) return null;
        try {
            String typeName = actionTag.getStringOr("type", "");
            MacroAction migratedLegacy = createHardDebloatMigration(typeName, actionTag);
            if (migratedLegacy != null) return migratedLegacy;
            MacroActionType type = MacroActionType.valueOf(typeName);
            MacroAction action = null;
            switch (type) {
                case DELAY: action = new DelayAction(); break;
                case PACKET: action = new PacketAction(); break;
                case PACKET_CLICK: action = new PacketClickAction(); break;
                case WAIT_PACKET: action = new WaitForPacketAction(); break;
                case WAIT_HEALTH: action = new WaitForHealthAction(); break;
                case WAIT_ITEM: {
                    WaitForSlotChangeAction migrated = new WaitForSlotChangeAction();
                    migrated.fromTagLegacyItem(actionTag);
                    action = migrated;
                    break;
                }
                case WAIT_BLOCK: action = new WaitForBlockAction(); break;
                case WAIT_GUI: action = new WaitForGuiAction(); break;
                case CLICK: action = new ClickAction(); break;
                case ROTATE: action = new RotateAction(); break;
                case USE_ITEM: action = new UseItemAction(); break;
                case INVENTORY: action = new InventoryAction(); break;
                case SEND_PACKET: action = new SendPacketAction(); break;
                case CRAFT: action = new CraftAction(); break;
                case SELECT_SLOT: action = new SelectSlotAction(); break;
                case XCARRY: action = new XCarryAction(); break;
                case DROP: action = new DropAction(); break;
                case ITEM: action = new ItemAction(); break;
                case TICK_SYNC: action = new TickSyncAction(); break;
                case REVISION_SYNC: action = new RevisionSyncAction(); break;
                case SERVER_TICK_SYNC: action = new ServerTickSyncAction(); break;
                case CLOSE_GUI: action = new CloseGuiAction(); break;
                case SWAP_SLOTS: action = new SwapSlotsAction(); break;
                case WAIT_COOLDOWN: action = new WaitForCooldownAction(); break;
                case GO_TO: action = new GoToAction(); break;
                case WAIT_POS: action = new WaitPosAction(); break;
                case PAYLOAD: action = new PayloadAction(); break;
                case DISCONNECT: action = new DisconnectAction(); break;
                case TOGGLE_MODULE: action = new ToggleModuleAction(); break;
                case START_MACRO: action = new StartMacroAction(); break;
                case STOP_MACRO: action = new StopMacroAction(); break;
                case SNEAK: action = new SneakAction(); break;
                case JUMP: action = new JumpAction(); break;
                case SPRINT: action = new SprintAction(); break;
                case MOVE: action = new MoveAction(); break;
                case LOOK_AT_BLOCK: action = new LookAtBlockAction(); break;
                case REPEAT: action = new RepeatAction(); break;
                case WAIT_CHAT: action = new WaitForChatAction(); break;
                case WAIT_ENTITY: action = new WaitForEntityAction(); break;
                case WAIT_SLOT_CHANGE: action = new WaitForSlotChangeAction(); break;
                case OPEN_CONTAINER: action = new OpenContainerAction(); break;
                case INTERACT_ENTITY: action = new autismclient.util.macro.InteractEntityAction(); break;
                case DESYNC: action = new DesyncAction(); break;
                case RESTORE_GUI: action = new RestoreGuiAction(); break;
                case SAVE_GUI: action = new SaveGuiAction(); break;
                case SEND_TOGGLE: action = new SendToggleAction(); break;
                case DELAY_PACKETS: action = new DelayPacketsAction(); break;
                case INVENTORY_AUDIT: action = new InventoryAuditAction(); break;
                case STORE_ITEM: action = new StoreItemAction(); break;
                case WAIT_SOUND: action = new WaitForSoundAction(); break;
                case MINE: action = new MineAction(); break;
                case INSTA_BREAK: action = new InstaBreakAction(); break;
                case BREAK: action = new BreakAction(); break;
                case PAY: action = new PayAction(); break;
                case NBT_BOOK: action = new NbtBookAction(); break;
                case SEND_CHAT: action = new SendChatAction(); break;
                case WAIT_LAN_STEP: action = new WaitForLanStepAction(); break;
                case WAIT_MACRO_STEP: action = new WaitForMacroStepAction(); break;
                case WAIT_WORLD_CHANGE: action = new WaitForWorldChangeAction(); break;
                case WAIT_POSITION_DELTA: action = new WaitForPositionDeltaAction(); break;
                case WAIT_TELEPORT: action = new WaitForTeleportAction(); break;
                case WAIT_GAMEMODE_CHANGE: action = new WaitGamemodeChangeAction(); break;
                case WAIT_MOVEMENT: action = new WaitMovementAction(); break;
                case RACE: action = new autismclient.util.macro.RaceAction(); break;
                case REPORT: action = new autismclient.util.macro.ReportAction(); break;
                case VCLIP: action = new autismclient.util.macro.VClipAction(); break;
                case HCLIP: action = new autismclient.util.macro.HClipAction(); break;
                case PACKET_GATE: action = new PacketGateAction(); break;
                case END_PACKET_GATE: action = new EndPacketGateAction(); break;
                case PACKET_BURST: action = new PacketBurstAction(); break;
                case CONTAINER_CLICK_SEQUENCE: action = new ContainerClickSequenceAction(); break;
                case ASSERT: action = new AssertAction(); break;
                case USE_ITEM_PHASE: action = new UseItemPhaseAction(); break;
                case SEND_COMMAND_PACKET: action = new SendCommandPacketAction(); break;
                case WAIT_PACKET_MATCH: action = new WaitPacketMatchAction(); break;
                case WAIT_INVENTORY_PREDICATE: action = new WaitInventoryPredicateAction(); break;
                case WAIT_DURABILITY: action = new WaitDurabilityAction(); break;
                case WAIT_FREE_SLOTS: action = new WaitFreeSlotsAction(); break;
                case WAIT_ENTITY_TARGET: action = new WaitEntityTargetAction(); break;
                case WAIT_GUI_TYPE: action = new WaitGuiTypeAction(); break;
                case BRANCH: action = new BranchAction(); break;
                case FINALLY: action = new FinallyAction(); break;
                case MACRO_VARIABLES: action = new MacroVariablesAction(); break;
                case FAKE_GAMEMODE: action = new FakeGamemodeAction(); break;
                case BUNDLE_DUPE_V2: action = new BundleDupeV2Action(); break;
                case PLACE: action = new PlaceAction(); break;
                case SIGN_EDIT: action = new SignEditAction(); break;
            }
            if (action != null) {
                if (type != MacroActionType.WAIT_ITEM) action.fromTag(actionTag);
            }
            return action;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static MacroAction createHardDebloatMigration(String typeName, CompoundTag actionTag) {
        if ("WAIT_ENTITY_TARGET".equals(typeName)) {
            WaitForEntityAction migrated = new WaitForEntityAction();
            migrated.fromLegacyTargetTag(actionTag);
            return migrated;
        }
        if ("WAIT_GUI_TYPE".equals(typeName)) {
            WaitForGuiAction migrated = new WaitForGuiAction();
            migrated.fromGuiTypeTag(actionTag);
            return migrated;
        }
        return null;
    }

    private void migrateLegacyRaceGroups() {
        for (int i = 0; i < actions.size(); i++) {
            if (!(actions.get(i) instanceof RaceAction race) || !race.needsLegacyMigration()) continue;

            int inserted = 0;
            PacketClickAction packetClick = race.consumeLegacyPacketClickAction();
            if (packetClick != null) {
                actions.add(i + 1, packetClick);
                inserted++;
            }

            if (race.legacyUseNextAction()) {
                int j = i + 1 + inserted;
                while (j < actions.size()) {
                    MacroAction candidate = actions.get(j);
                    if (!RaceAction.isBodyAction(candidate)) break;
                    inserted++;
                    j++;
                }
            }

            race.bodyCount = inserted;
            race.clearLegacyMigration();
            i += inserted;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AutismMacro that = (AutismMacro) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}

