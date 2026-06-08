package autismclient.util.macro;

import autismclient.util.AutismContainerTarget;
import autismclient.util.AutismSharedState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;

public class XCarryAction implements MacroAction {
    public static final int GRID_ENTRY_LIMIT = 4;
    public static final int ARMOR_ENTRY_LIMIT = 4;
    public static final int OFFHAND_ENTRY_LIMIT = 1;
    public static final int CURSOR_ENTRY_LIMIT = 1;
    public static final int STORAGE_ENTRY_LIMIT = GRID_ENTRY_LIMIT + ARMOR_ENTRY_LIMIT + OFFHAND_ENTRY_LIMIT;
    public static final int MAX_ENTRIES = STORAGE_ENTRY_LIMIT + CURSOR_ENTRY_LIMIT;
    private static final int MAX_PUT_IN_ATTEMPTS = MAX_ENTRIES;

    public enum Mode { PUT_IN, TAKE_OUT, DROP }
    public enum TransferMode { FAST, CLICK }

    static final class PutInMove {
        final int sourceSlotId;
        final int targetSlotId;

        PutInMove(int sourceSlotId, int targetSlotId) {
            this.sourceSlotId = sourceSlotId;
            this.targetSlotId = targetSlotId;
        }
    }

    public final List<String> entries = new ArrayList<>();
    public final List<ItemTarget> entryTargets = new ArrayList<>();
    public Mode mode = Mode.PUT_IN;
    public TransferMode transferMode = TransferMode.FAST;
    public boolean carryCursor = true;

    public boolean useCrafting = true;
    public boolean useArmor    = true;
    public boolean useOffhand  = true;

    public static final int DEST_AUTO   = Integer.MIN_VALUE;
    public static final int DEST_CURSOR = -1;
    private static final int[] DESTINATION_VALUES = new int[] {
            DEST_AUTO,
            DEST_CURSOR,
            1, 2, 3, 4,
            5, 6, 7, 8,
            45
    };
    public final java.util.List<Integer> entryDestinations = new java.util.ArrayList<>();
    private boolean enabled = true;

    public static int[] destinationValues() {
        return DESTINATION_VALUES.clone();
    }

    public static java.util.List<String> destinationLabels() {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>(DESTINATION_VALUES.length);
        for (int value : DESTINATION_VALUES) labels.add(destinationLabel(value));
        return labels;
    }

    public static int destinationIndex(int destination) {
        for (int i = 0; i < DESTINATION_VALUES.length; i++) {
            if (DESTINATION_VALUES[i] == destination) return i;
        }
        return 0;
    }

    public static String destinationLabel(int destination) {
        if (destination == DEST_AUTO) return "Auto";
        if (destination == DEST_CURSOR) return "Cursor";
        return switch (destination) {
            case 1 -> "Craft 1";
            case 2 -> "Craft 2";
            case 3 -> "Craft 3";
            case 4 -> "Craft 4";
            case 5 -> "Helmet";
            case 6 -> "Chestplate";
            case 7 -> "Leggings";
            case 8 -> "Boots";
            case 45 -> "Offhand";
            default -> "Auto";
        };
    }

    private void forceAllDestinationsEnabled() {
        carryCursor = true;
        useCrafting = true;
        useArmor = true;
        useOffhand = true;
    }

    public int destinationFor(int entryIndex) {
        if (entryIndex < 0 || entryIndex >= entryDestinations.size()) return DEST_AUTO;
        Integer v = entryDestinations.get(entryIndex);
        return v == null ? DEST_AUTO : v;
    }
    public void setDestinationFor(int entryIndex, int destSlotId) {
        while (entryDestinations.size() <= entryIndex) entryDestinations.add(DEST_AUTO);
        entryDestinations.set(entryIndex, destSlotId);
    }
    public void resizeDestinations(int targetSize) {
        while (entryDestinations.size() < targetSize) entryDestinations.add(DEST_AUTO);
        while (entryDestinations.size() > targetSize) entryDestinations.remove(entryDestinations.size() - 1);
    }

    public java.util.Set<Integer> activeStorageSlotIds() {
        java.util.LinkedHashSet<Integer> s = new java.util.LinkedHashSet<>();
        s.add(1); s.add(2); s.add(3); s.add(4);
        s.add(5); s.add(6); s.add(7); s.add(8);
        s.add(45);
        return s;
    }

    @Override
    public void execute(Minecraft mc) {
        if (mc == null || mc.player == null || mc.gameMode == null) return;
        forceAllDestinationsEnabled();

        autismclient.util.AutismSharedState shared = autismclient.util.AutismSharedState.get();
        boolean prevBypass = shared.isXCarryArmorBypass();
        shared.setXCarryArmorBypass(true);
        try {
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                executeWithContainerOpen(mc);
                return;
            }

            InventoryMenu handler = mc.player.inventoryMenu;
            if (!handler.getCarried().isEmpty()) {
                trimCursorToOne(handler, mc);
                updateXCarryState(handler, true);
                return;
            }

            switch (mode) {
                case PUT_IN -> executePutIn(handler, mc);
                case TAKE_OUT -> executeTakeOut(handler, mc);
                case DROP -> executeDrop(handler, mc);
            }
        } finally {
            shared.setXCarryArmorBypass(prevBypass);
        }
    }

    private void executeWithContainerOpen(Minecraft mc) {
        if (mc.getConnection() == null) return;
        forceAllDestinationsEnabled();

        AutismContainerTarget containerTarget = AutismSharedState.get().getLastContainerTarget();
        if (containerTarget == null) return;

        InventoryMenu playerHandler = mc.player.inventoryMenu;
        if (!playerHandler.getCarried().isEmpty()) {
            trimCursorToOne(playerHandler, mc);
            updateXCarryState(playerHandler, true);
            return;
        }

        AbstractContainerMenu containerHandler = mc.player.containerMenu;

        if (mode == Mode.PUT_IN) {
            List<ItemTarget> targets = resolvedEntryTargets();
            int limit = Math.min(maxConfiguredEntries(), targets.size());
            for (int i = 0; i < limit; i++) {
                ItemTarget itemTarget = targets.get(i);
                if (itemTarget == null || itemTarget.hasSlot() || !itemTarget.hasIdentity()) continue;
                for (Slot slot : containerHandler.slots) {
                    if (slot.getItem().isEmpty()) continue;
                    if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
                    if (matchesTarget(slot.getItem(), itemTarget, -1)) {
                        mc.gameMode.handleContainerInput(containerHandler.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
                        break;
                    }
                }
            }
        }

        mc.getConnection().send(new ServerboundContainerClosePacket(containerHandler.containerId));
        mc.player.containerMenu = playerHandler;

        switch (mode) {
            case PUT_IN -> executePutIn(playerHandler, mc);
            case TAKE_OUT -> executeTakeOut(playerHandler, mc);
            case DROP -> executeDrop(playerHandler, mc);
        }

        mc.player.containerMenu = containerHandler;
        containerTarget.interact(mc);
    }

    private void executePutIn(InventoryMenu handler, Minecraft mc) {
        forceAllDestinationsEnabled();
        java.util.List<ItemTarget> targets = resolvedEntryTargets();
        if (targets.isEmpty()) {
            if (hasStoredItems(handler, true)) updateXCarryState(handler, true);
            return;
        }

        java.util.Set<Integer> storageSlotSet = activeStorageSlotIds();
        java.util.List<Integer> autoSlots = new java.util.ArrayList<>(storageSlotSet);
        java.util.Set<Integer> claimedAutoSlots = new java.util.HashSet<>();
        boolean cursorClaimed = false;
        int moved = 0;
        boolean movedCursor = false;

        for (int i = 0; i < targets.size(); i++) {
            ItemTarget target = targets.get(i);
            int sourceSlotId = findSourceSlot(handler, target, storageSlotSet);
            if (sourceSlotId < 0) continue;

            int dest = destinationFor(i);
            if (dest == DEST_CURSOR) {
                if (cursorClaimed || !handler.getCarried().isEmpty()) continue;
                if (moveOneItemToCursor(handler, mc, i, i + 1)) {
                    movedCursor = true;
                    cursorClaimed = true;
                }
                continue;
            }

            int targetSlotId;
            if (dest == DEST_AUTO) {
                targetSlotId = -1;
                for (Integer cand : autoSlots) {
                    if (cand == null || cand < 0 || cand >= handler.slots.size()) continue;
                    if (claimedAutoSlots.contains(cand)) continue;
                    if (handler.slots.get(cand).getItem().isEmpty()) { targetSlotId = cand; break; }
                }
                if (targetSlotId < 0) continue;
            } else {
                targetSlotId = dest;
                if (targetSlotId < 0 || targetSlotId >= handler.slots.size()) continue;
                if (!handler.slots.get(targetSlotId).getItem().isEmpty()) continue;
            }

            if (sourceSlotId == targetSlotId) continue;
            if (moveStack(handler, mc, sourceSlotId, targetSlotId)) {
                moved++;
                claimedAutoSlots.add(targetSlotId);
            }
        }

        int gridCapacity = activeStorageSlotIds().size();
        if (!cursorClaimed && handler.getCarried().isEmpty() && targets.size() > gridCapacity) {
            boolean anyExplicit = false;
            for (int i = 0; i < targets.size(); i++) if (destinationFor(i) != DEST_AUTO) { anyExplicit = true; break; }
            if (!anyExplicit) movedCursor = moveOneItemToCursor(handler, mc, gridCapacity, gridCapacity + CURSOR_ENTRY_LIMIT);
        }

        if (moved > 0 || movedCursor || hasStoredItems(handler, true)) {
            updateXCarryState(handler, true);
        }
    }

    private void executeTakeOut(InventoryMenu handler, Minecraft mc) {
        forceAllDestinationsEnabled();
        for (Integer slotId : activeStorageSlotIds()) {
            if (slotId == null || slotId < 0 || slotId >= handler.slots.size()) continue;
            Slot slot = handler.slots.get(slotId);
            if (slot.getItem().isEmpty()) continue;
            if (!entries.isEmpty() && !matchesCraftingSlot(slot, slotId)) continue;
            mc.gameMode.handleContainerInput(handler.containerId, slotId, 0, ContainerInput.QUICK_MOVE, mc.player);
        }
        updateXCarryState(handler, true);
    }

    private void executeDrop(InventoryMenu handler, Minecraft mc) {
        forceAllDestinationsEnabled();
        for (Integer slotId : activeStorageSlotIds()) {
            if (slotId == null || slotId < 0 || slotId >= handler.slots.size()) continue;
            Slot slot = handler.slots.get(slotId);
            if (slot.getItem().isEmpty()) continue;
            if (!entries.isEmpty() && !matchesCraftingSlot(slot, slotId)) continue;
            mc.gameMode.handleContainerInput(handler.containerId, slotId, 1, ContainerInput.THROW, mc.player);
        }
        updateXCarryState(handler, true);
    }

    private boolean matchesCraftingSlot(Slot slot, int slotId) {
        for (ItemTarget entryTarget : resolvedEntryTargets()) {
            if (entryTarget == null) continue;
            if (entryTarget.hasSlot()) {
                if (entryTarget.slot == slotId && (!entryTarget.hasIdentity() || matchesTarget(slot.getItem(), entryTarget, slotId))) return true;
            } else if (entryTarget.hasIdentity() && matchesTarget(slot.getItem(), entryTarget, slotId)) {
                return true;
            }
        }
        return false;
    }

    PutInMove findNextPutInMove(InventoryMenu handler) {
        if (handler == null) return null;

        forceAllDestinationsEnabled();
        java.util.Set<Integer> storageSlotSet = activeStorageSlotIds();
        java.util.List<Integer> storageSlots = new java.util.ArrayList<>(storageSlotSet);
        List<ItemTarget> targets = resolvedEntryTargets();
        int limit = Math.min(maxConfiguredEntries(), targets.size());
        int nextTargetIdx = 0;
        for (int i = 0; i < limit; i++) {
            int dest = destinationFor(i);
            if (dest == DEST_CURSOR) continue;

            int sourceSlotId = findSourceSlot(handler, targets.get(i), storageSlotSet);
            if (sourceSlotId < 0) continue;

            int targetSlotId = -1;
            if (dest == DEST_AUTO) {
                while (nextTargetIdx < storageSlots.size()) {
                    int candidate = storageSlots.get(nextTargetIdx);
                    if (candidate < 0 || candidate >= handler.slots.size()) { nextTargetIdx++; continue; }
                    if (handler.slots.get(candidate).getItem().isEmpty()) break;
                    nextTargetIdx++;
                }
                if (nextTargetIdx >= storageSlots.size()) return null;
                targetSlotId = storageSlots.get(nextTargetIdx++);
            } else {
                targetSlotId = dest;
                if (targetSlotId < 0 || targetSlotId >= handler.slots.size()) continue;
                if (!handler.slots.get(targetSlotId).getItem().isEmpty()) continue;
            }

            if (sourceSlotId == targetSlotId) continue;
            return new PutInMove(sourceSlotId, targetSlotId);
        }

        return null;
    }

    public boolean requiresCursorStorage() {
        forceAllDestinationsEnabled();
        List<ItemTarget> targets = resolvedEntryTargets();
        int limit = Math.min(maxConfiguredEntries(), targets.size());
        boolean anyExplicit = false;
        int autoCount = 0;
        for (int i = 0; i < limit; i++) {
            int dest = destinationFor(i);
            if (dest == DEST_CURSOR) return true;
            if (dest == DEST_AUTO) autoCount++;
            else anyExplicit = true;
        }
        return !anyExplicit && autoCount > activeStorageSlotIds().size();
    }

    List<Integer> collectContainerTransferSlots(AbstractContainerMenu containerHandler) {
        List<Integer> slotIds = new ArrayList<>();
        if (containerHandler == null) return slotIds;

        List<ItemTarget> targets = resolvedEntryTargets();
        int limit = Math.min(maxConfiguredEntries(), targets.size());
        for (int i = 0; i < limit; i++) {
            ItemTarget itemTarget = targets.get(i);
            if (itemTarget == null || itemTarget.hasSlot() || !itemTarget.hasIdentity()) continue;
            for (Slot slot : containerHandler.slots) {
                if (slot.getItem().isEmpty()) continue;
                if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
                if (!matchesTarget(slot.getItem(), itemTarget, -1)) continue;
                slotIds.add(slot.index);
                break;
            }
        }

        return slotIds;
    }

    @Override
    public CompoundTag toTag() {
        forceAllDestinationsEnabled();
        CompoundTag tag = new CompoundTag();
        tag.putString("type", MacroActionType.XCARRY.name());
        tag.putString("mode", mode.name());
        tag.putString("transferMode", transferMode.name());
        tag.putBoolean("carryCursor", true);
        tag.putBoolean("useCrafting", true);
        tag.putBoolean("useArmor", true);
        tag.putBoolean("useOffhand", true);
        List<ItemTarget> targets = resolvedEntryTargets();
        int savedCount = Math.min(maxConfiguredEntries(), targets.size());
        tag.put("entries", ItemTarget.toTagList(targets.subList(0, savedCount)));
        resizeDestinations(savedCount);
        ListTag destinations = new ListTag();
        for (int i = 0; i < savedCount; i++) {
            destinations.add(StringTag.valueOf(String.valueOf(destinationFor(i))));
        }
        tag.put("entryDestinations", destinations);
        tag.putBoolean("enabled", enabled);
        return tag;
    }

    @Override
    public void fromTag(CompoundTag tag) {
        entries.clear();
        entryTargets.clear();
        entryDestinations.clear();
        String modeStr = tag.getStringOr("mode", "PUT_IN");
        try {
            mode = Mode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            mode = Mode.PUT_IN;
        }

        String transferModeStr = tag.getStringOr("transferMode", "FAST");
        try {
            transferMode = TransferMode.valueOf(transferModeStr);
        } catch (IllegalArgumentException e) {
            transferMode = TransferMode.FAST;
        }

        forceAllDestinationsEnabled();
        if (tag.contains("entries")) {
            ListTag list = tag.getList("entries").orElse(new ListTag());
            for (ItemTarget target : ItemTarget.fromElementList(list)) {
                if (target == null || (!target.hasSlot() && !target.hasIdentity())) continue;
                entryTargets.add(target);
                if (entryTargets.size() >= maxConfiguredEntries()) break;
            }
        }
        if (tag.contains("entryDestinations")) {
            ListTag destinations = tag.getList("entryDestinations").orElse(new ListTag());
            for (Tag element : destinations) {
                int dest = DEST_AUTO;
                try {
                    dest = Integer.parseInt(element.asString().orElse(String.valueOf(DEST_AUTO)));
                } catch (NumberFormatException ignored) {
                }
                entryDestinations.add(dest);
            }
        }
        resizeDestinations(entryTargets.size());
        syncLegacyEntries();

        enabled = tag.getBooleanOr("enabled", true);
    }

    @Override
    public MacroActionType getType() {
        return MacroActionType.XCARRY;
    }

    @Override
    public String getDisplayName() {
        String prefix = switch (mode) {
            case PUT_IN -> transferMode == TransferMode.CLICK ? "XCarry Click" : "XCarry";
            case TAKE_OUT -> "XCarry Out";
            case DROP -> "XCarry Drop";
        };
        forceAllDestinationsEnabled();

        if (entries.isEmpty()) return prefix;
        String first = formatEntry(entries.get(0));
        if (entries.size() > 1) first += " (+" + (entries.size() - 1) + ")";
        return prefix + " [" + first + "]";
    }

    @Override
    public String getIcon() {
        return "XC";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public static String normalizeEntry(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.startsWith("#")) return trimmed;
        int separator = trimmed.indexOf('|');
        String slotPart = separator >= 0 ? trimmed.substring(1, separator) : trimmed.substring(1);
        try {
            int slot = Integer.parseInt(slotPart.trim());
            if (slot < 0) return null;
            String name = separator >= 0 && separator + 1 < trimmed.length()
                    ? trimmed.substring(separator + 1).trim()
                    : "";
            if (!name.isEmpty()) return "#" + slot + "|" + name;
            return "#" + slot;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String formatEntry(String entry) {
        String normalized = normalizeEntry(entry);
        if (normalized == null) return "";
        int slot = parseEntrySlot(normalized);
        String name = parseEntryName(normalized);
        if (slot >= 0 && !name.isEmpty()) return name + " @ Slot " + slot;
        if (slot >= 0) return "Slot " + slot;
        return normalized;
    }

    private static int findSourceSlot(InventoryMenu handler, ItemTarget target) {
        return findSourceSlot(handler, target, java.util.Set.of());
    }

    private static int findSourceSlot(InventoryMenu handler, ItemTarget target, java.util.Set<Integer> excludedSlotIds) {
        if (target == null) return -1;
        java.util.Set<Integer> excluded = excludedSlotIds == null ? java.util.Set.of() : excludedSlotIds;

        if (target.hasSlot()) {
            int exactSlot = resolvePlayerVisibleSlot(handler, target.slot);
            if (exactSlot < 0) return -1;
            if (excluded.contains(exactSlot)) return -1;
            if (!target.hasIdentity()) return exactSlot;
            Slot slot = handler.slots.get(exactSlot);
            return matchesTarget(slot.getItem(), target, target.slot) ? exactSlot : -1;
        }

        for (Slot slot : handler.slots) {
            if (excluded.contains(slot.index)) continue;
            if (!isEligibleSourceSlot(slot)) continue;
            int visibleSlot = resolveVisibleSlotForPlayerSlot(handler, slot.index);
            if (matchesTarget(slot.getItem(), target, visibleSlot)) return slot.index;
        }
        return -1;
    }

    private static boolean moveStack(InventoryMenu handler, Minecraft mc, int fromSlotId, int toSlotId) {
        if (mc == null || mc.player == null || mc.gameMode == null) return false;
        if (fromSlotId < 0 || fromSlotId >= handler.slots.size()) return false;
        if (toSlotId < 0 || toSlotId >= handler.slots.size()) return false;
        if (fromSlotId == toSlotId) return true;
        if (!handler.getCarried().isEmpty()) return false;

        if (handler.slots.get(fromSlotId).getItem().isEmpty()) return false;
        if (!handler.slots.get(toSlotId).getItem().isEmpty()) return false;

        mc.gameMode.handleContainerInput(handler.containerId, fromSlotId, 0, ContainerInput.PICKUP, mc.player);
        if (handler.getCarried().isEmpty()) return false;

        mc.gameMode.handleContainerInput(handler.containerId, toSlotId, 0, ContainerInput.PICKUP, mc.player);
        if (handler.getCarried().isEmpty()) {
            return handler.slots.get(fromSlotId).getItem().isEmpty()
                    && !handler.slots.get(toSlotId).getItem().isEmpty();
        }

        mc.gameMode.handleContainerInput(handler.containerId, fromSlotId, 0, ContainerInput.PICKUP, mc.player);
        return false;
    }

    private boolean moveOneItemToCursor(InventoryMenu handler, Minecraft mc, int startTargetIndex, int maxTargets) {
        if (handler == null || mc == null || mc.player == null || mc.gameMode == null) return false;
        if (!handler.getCarried().isEmpty()) return trimCursorToOne(handler, mc);

        List<ItemTarget> targets = resolvedEntryTargets();
        java.util.Set<Integer> storageSlotSet = activeStorageSlotIds();
        int limit = Math.min(maxTargets, targets.size());
        for (int i = Math.max(0, startTargetIndex); i < limit; i++) {
            int sourceSlotId = findSourceSlot(handler, targets.get(i), storageSlotSet);
            if (sourceSlotId < 0 || sourceSlotId >= handler.slots.size()) continue;
            Slot sourceSlot = handler.slots.get(sourceSlotId);
            if (sourceSlot == null || sourceSlot.getItem().isEmpty()) continue;

            ItemStack expected = sourceSlot.getItem().copy();
            mc.gameMode.handleContainerInput(handler.containerId, sourceSlotId, 0, ContainerInput.PICKUP, mc.player);
            if (handler.getCarried().isEmpty()) return false;

            int safety = Math.max(0, handler.getCarried().getCount() - 1);
            while (handler.getCarried().getCount() > 1 && safety-- > 0) {
                mc.gameMode.handleContainerInput(handler.containerId, sourceSlotId, 1, ContainerInput.PICKUP, mc.player);
            }

            ItemStack carried = handler.getCarried();
            return !carried.isEmpty()
                    && carried.getCount() == 1
                    && ItemStack.isSameItemSameComponents(carried, expected);
        }
        return false;
    }

    private static boolean trimCursorToOne(InventoryMenu handler, Minecraft mc) {
        if (handler == null || mc == null || mc.player == null || mc.gameMode == null) return false;
        if (handler.getCarried().isEmpty()) return false;
        if (handler.getCarried().getCount() <= 1) return true;

        int safety = Math.max(0, handler.getCarried().getCount() - 1);
        while (!handler.getCarried().isEmpty() && handler.getCarried().getCount() > 1 && safety-- > 0) {
            int returnSlotId = findCursorReturnSlot(handler, handler.getCarried());
            if (returnSlotId < 0) break;
            mc.gameMode.handleContainerInput(handler.containerId, returnSlotId, 1, ContainerInput.PICKUP, mc.player);
        }
        return !handler.getCarried().isEmpty() && handler.getCarried().getCount() == 1;
    }

    private static int findCursorReturnSlot(InventoryMenu handler, ItemStack carried) {
        if (handler == null || carried == null || carried.isEmpty()) return -1;
        for (Slot slot : handler.slots) {
            if (slot == null || slot.index < 5) continue;
            if (slot.index >= 5 && slot.index <= 8) continue;
            if (slot.index == 45) continue;
            if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) return slot.index;
            if (ItemStack.isSameItemSameComponents(stack, carried)
                    && stack.getCount() < Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack))) {
                return slot.index;
            }
        }
        return -1;
    }

    public static boolean hasCraftingGridItems(InventoryMenu handler) {
        if (handler == null) return false;
        for (int slotId = 1; slotId <= 4 && slotId < handler.slots.size(); slotId++) {
            if (!handler.slots.get(slotId).getItem().isEmpty()) return true;
        }
        return false;
    }

    public static boolean hasCursorItem(InventoryMenu handler) {
        return handler != null && !handler.getCarried().isEmpty();
    }

    public static boolean hasStoredItems(InventoryMenu handler, boolean includeCursor) {
        return hasCraftingGridItems(handler) || (includeCursor && hasCursorItem(handler));
    }

    public static boolean hasStoredItems(InventoryMenu handler, boolean includeCursor, java.util.Set<Integer> slotIds) {
        if (handler == null) return false;
        if (slotIds != null) {
            for (Integer id : slotIds) {
                if (id == null || id < 0 || id >= handler.slots.size()) continue;
                if (!handler.slots.get(id).getItem().isEmpty()) return true;
            }
        }
        return includeCursor && hasCursorItem(handler);
    }

    private void updateXCarryState(InventoryMenu handler, boolean includeCursor) {
        boolean active = hasStoredItems(handler, includeCursor, activeStorageSlotIds());
        AutismSharedState shared = AutismSharedState.get();
        if (active) {
            shared.mergeXCarryForcedTargets(activeStorageSlotIds(), includeCursor);
            shared.setXCarryForced(true);
            shared.setXCarryActive(true);
            return;
        }

        if (shared.isXCarryForced()) {
            boolean stillHeld = hasStoredItems(
                    handler,
                    shared.isXCarryForcedCarryCursor(),
                    shared.getXCarryForcedSlotMask());
            if (stillHeld) {
                shared.setXCarryActive(true);
                return;
            }
            shared.setXCarryForced(false);
        }
        shared.setXCarryActive(false);
    }

    public int maxConfiguredEntries() {

        return MAX_ENTRIES;
    }

    public static int maxEntriesFor(boolean carryCursor) {
        return STORAGE_ENTRY_LIMIT + (carryCursor ? CURSOR_ENTRY_LIMIT : 0);
    }

    static void sendOpenTarget(Minecraft mc, AutismContainerTarget target) {
        if (target == null) return;
        target.interact(mc);
    }

    private static boolean isEligibleSourceSlot(Slot slot) {
        return slot != null && slot.index >= 5 && !slot.getItem().isEmpty();
    }

    private static int resolvePlayerVisibleSlot(AbstractContainerMenu handler, int configuredSlot) {
        for (Slot slot : handler.slots) {
            if (slot == null || slot.index < 0 || slot.index >= handler.slots.size()) continue;
            if (slot.index <= 4) continue;
            if (slot.index >= 5 && slot.index <= 8) continue;
            if (slot.index == 45) continue;
            if (slot.container == null) continue;
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                int visibleSlot = slot.getContainerSlot();
                if (visibleSlot == configuredSlot) return slot.index;
            }
        }
        return -1;
    }

    private static boolean matchesTarget(ItemStack stack, ItemTarget target, int visibleSlot) {
        if (stack == null || stack.isEmpty()) return false;
        if (target == null || !target.hasIdentity()) return true;
        return target.matches(stack, visibleSlot);
    }

    private static int parseEntrySlot(String entry) {
        if (entry == null || !entry.startsWith("#")) return -1;
        int separator = entry.indexOf('|');
        String raw = separator >= 0 ? entry.substring(1, separator) : entry.substring(1);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String parseEntryName(String entry) {
        if (entry == null || entry.isBlank()) return "";
        if (!entry.startsWith("#")) return entry.trim();
        int separator = entry.indexOf('|');
        return separator >= 0 && separator + 1 < entry.length() ? entry.substring(separator + 1).trim() : "";
    }

    private static int resolveVisibleSlotForPlayerSlot(InventoryMenu handler, int handlerSlotId) {
        if (handlerSlotId < 0 || handlerSlotId >= handler.slots.size()) return -1;
        Slot slot = handler.slots.get(handlerSlotId);
        if (slot == null || !(slot.container instanceof net.minecraft.world.entity.player.Inventory)) return -1;
        return slot.getContainerSlot();
    }

    private List<ItemTarget> resolvedEntryTargets() {
        if (!entryTargets.isEmpty()) return entryTargets;
        for (String entry : entries) {
            String normalized = normalizeEntry(entry);
            if (normalized == null) continue;
            ItemTarget target = ItemTarget.fromLegacyEntry(normalized);
            if (target.hasSlot() || target.hasIdentity()) entryTargets.add(target);
            if (entryTargets.size() >= maxConfiguredEntries()) break;
        }
        return entryTargets;
    }

    private void syncLegacyEntries() {
        entries.clear();
        for (ItemTarget target : entryTargets) {
            if (target == null) continue;
            String entry = normalizeEntry(target.toLegacyEntry());
            if (entry == null || entries.contains(entry)) continue;
            entries.add(entry);
            if (entries.size() >= maxConfiguredEntries()) break;
        }
    }
}
