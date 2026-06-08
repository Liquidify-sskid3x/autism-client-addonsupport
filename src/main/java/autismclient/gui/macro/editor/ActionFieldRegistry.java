package autismclient.gui.macro.editor;

import autismclient.util.macro.MacroActionType;
import autismclient.util.macro.WaitForHealthAction;

import java.util.EnumMap;
import java.util.Map;

public final class ActionFieldRegistry {

    private static final ActionFieldSchema EMPTY = ActionFieldSchema.builder().build();
    private static final Map<MacroActionType, ActionFieldSchema> SCHEMAS =
            new EnumMap<>(MacroActionType.class);

    static {

        SCHEMAS.put(MacroActionType.DELAY, ActionFieldSchema.builder()
                .toggle("useTicks",   "Use Ticks")
                .number("delayMs",    "Delay (ms)")   .range(0, 300_000) .hideWhen("useTicks")
                .number("delayTicks", "Delay (ticks)").range(0, 20_000)  .showWhen("useTicks")
                .build());

        SCHEMAS.put(MacroActionType.PACKET, ActionFieldSchema.builder()
                .text  ("description", "Description")
                .toggle("regenerate",  "Regenerate")
                .build());

        SCHEMAS.put(MacroActionType.PACKET_CLICK, ActionFieldSchema.builder()
                .enumField    ("mode",   "Click Mode", "LEFT_CLICK", "RIGHT_CLICK", "QUICK_MOVE")
                .number       ("times",  "Repeats").range(1, 100)
                .toggle       ("queue",  "Queue Exact")
                .targetSummary("target", "Captured")
                .capturePacketClick("target", "Re-capture")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_PACKET, EMPTY);

        SCHEMAS.put(MacroActionType.WAIT_HEALTH, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .decimal("healthThreshold", "Target Health").decRange(0, 20)
                .enumField("comparison", "Condition",
                        WaitForHealthAction.COMPARISON_DROPS_BELOW,
                        WaitForHealthAction.COMPARISON_RISES_ABOVE)
                .build());

        SCHEMAS.put(MacroActionType.WAIT_ITEM, ActionFieldSchema.builder()
                .stringList("itemNames", "Items").addLabel("Add Item").captureItemSlot()
                .toggle    ("waitForGuiBefore", "Before")
                .toggle    ("waitForGuiAfter",  "After")
                .text      ("guiName",     "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_BLOCK, ActionFieldSchema.builder()
                .toggle     ("listenDuringPreviousAction", "Listen During Previous")
                .enumField  ("checkMode",    "Check Mode",    "AT_POSITION", "IN_REACH", "LOOKING_AT")
                .enumField  ("waitBehavior", "Wait For",      "PLACED", "DESTROYED")
                .toggle     ("anyBlock",     "Any Block")
                .stringList ("blockIds",     "Block IDs")    .addLabel("Add Block").captureBlock().hideWhen("anyBlock")
                .blockPos   ("pos",          "Position").captureBlock().showWhenEnum("checkMode", "AT_POSITION")
                .toggle     ("mustBeInReach","Must Be In Reach").showWhenEnum("checkMode", "AT_POSITION")
                .decimal    ("searchRadius", "Search Radius").decRange(0, 32).showWhenEnum("checkMode", "IN_REACH")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_GUI, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .enumField("waitMode", "Wait Mode", "OPEN", "CLOSE")
                .enumField("guiType", "GUI", "ANY", "CONTAINER", "INVENTORY", "SIGN", "HANGING_SIGN", "BOOK", "BOOK_EDIT", "BOOK_SIGN", "BOOK_VIEW", "CHAT")
                .text     ("guiTitle", "Title")
                .number("timeoutMs", "Timeout ms").range(0, 300000)
                .build());

        SCHEMAS.put(MacroActionType.CLICK, ActionFieldSchema.builder()
                .enumField("clickType",  "Click Type", "LEFT", "RIGHT")
                .number   ("clickCount", "Click Count").range(1, 100)
                .toggle   ("waitForGuiBefore", "Before")
                .toggle   ("waitForGuiAfter",  "After")
                .text     ("guiName",    "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.ROTATE, ActionFieldSchema.builder()
                .decimal("yaw",               "Yaw")              .decRange(-180, 180)
                .decimal("pitch",             "Pitch")            .decRange(-90, 90)
                .toggle ("smooth",            "Smooth")
                .number ("smoothness",        "Smoothness")       .range(1, 10).showWhen("smooth")
                .toggle ("waitForCompletion", "Wait for Completion")
                .build());

        SCHEMAS.put(MacroActionType.USE_ITEM, ActionFieldSchema.builder()
                .text     ("itemName", "Item Name").captureItemSlot()
                .number   ("slot",     "Slot").range(-1, 500)
                .enumField("useMode",  "Use Mode", "AUTOMATIC", "CUSTOM_HOLD")
                .toggle   ("waitForFinish", "Wait Finish")
                .number   ("holdTicks","Hold Ticks").range(1, 1000).showWhenEnum("useMode", "CUSTOM_HOLD")
                .number   ("useCount", "Use Count") .range(1, 1000).showWhenEnum("useMode", "AUTOMATIC")
                .build());

        SCHEMAS.put(MacroActionType.INVENTORY, ActionFieldSchema.builder()
                .enumField("mode",       "Mode", "OPEN", "CLOSE")
                .toggle   ("waitForGuiBefore", "Before").showWhenEnum("mode", "OPEN")
                .toggle   ("waitForGuiAfter",  "After").showWhenEnum("mode", "OPEN")
                .toggle   ("sendPacket", "Close without pkt").showWhenEnum("mode", "CLOSE")
                .build());

        SCHEMAS.put(MacroActionType.SEND_PACKET, ActionFieldSchema.builder()
                .text  ("customName", "Custom Name")
                .toggle("waitForGuiBefore", "Before")
                .toggle("waitForGuiAfter",  "After")
                .text  ("guiName",    "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.PAYLOAD, EMPTY);

        SCHEMAS.put(MacroActionType.CRAFT, EMPTY);

        SCHEMAS.put(MacroActionType.SELECT_SLOT, ActionFieldSchema.builder()
                .slot("slot",     "Slot")
                .text("itemName", "Item Name").captureItemSlot()
                .enumField("strategy", "Strategy", "FIRST_MATCH", "BEST_DURABILITY", "WORST_DURABILITY", "LARGEST_STACK", "EMPTY_SLOT")
                .text("outputVariable", "Save Slot As")
                .build());

        SCHEMAS.put(MacroActionType.XCARRY, ActionFieldSchema.builder()
                .enumField  ("mode",         "Mode",  "PUT_IN", "TAKE_OUT", "DROP")
                .enumField  ("transferMode", "Transfer", "FAST", "CLICK").showWhenEnum("mode", "PUT_IN")
                .stringList ("entries",      "Items").addLabel("Add Item").captureItemSlot()
                .build());

        SCHEMAS.put(MacroActionType.DROP, EMPTY);

        SCHEMAS.put(MacroActionType.ITEM, EMPTY);

        SCHEMAS.put(MacroActionType.TICK_SYNC, ActionFieldSchema.builder()
                .number("tickOffset",  "Tick Offset")    .range(0, 20)
                .number("preGenCount", "Pre-gen Count")  .range(0, 100)
                .build());

        SCHEMAS.put(MacroActionType.REVISION_SYNC, ActionFieldSchema.builder()
                .number("revisionOffset", "Revision Offset").range(0, 100)
                .number("preGenCount",    "Pre-gen Count")  .range(0, 100)
                .build());

        SCHEMAS.put(MacroActionType.SERVER_TICK_SYNC, ActionFieldSchema.builder()
                .number("bufferMs",    "Buffer (ms)")    .range(0, 5_000)
                .number("maxWaitMs",   "Max Wait (ms)")  .range(100, 60_000)
                .toggle("ignorePing",  "Ignore Ping")
                .number("preGenCount", "Pre-gen Count")  .range(0, 100)
                .build());

        SCHEMAS.put(MacroActionType.CLOSE_GUI, ActionFieldSchema.builder()
                .text  ("guiName",      "GUI Name")
                .toggle("useItemFilter","Filter by Item")
                .text  ("itemName",     "Item Name") .captureItemSlot().showWhen("useItemFilter")
                .slot  ("targetSlot",   "Target Slot").showWhen("useItemFilter")
                .toggle("sendPacket",   "Close without pkt")
                .build());

        SCHEMAS.put(MacroActionType.SWAP_SLOTS, ActionFieldSchema.builder()
                .toggle("fromUseItemName", "From: Use Item Name")
                .text  ("fromItemName",    "From: Item Name").captureItemSlot().showWhen("fromUseItemName")
                .slot  ("fromSlot",        "From: Slot")     .captureItemSlot().hideWhen("fromUseItemName")
                .toggle("toUseItemName",   "To: Use Item Name")
                .text  ("toItemName",      "To: Item Name")  .captureItemSlot().showWhen("toUseItemName")
                .slot  ("toSlot",          "To: Slot")       .captureItemSlot().hideWhen("toUseItemName")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_COOLDOWN, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .text  ("itemName",     "Item Name").captureItemSlot()
                .toggle("checkMainHand","Check Main Hand")
                .build());

        SCHEMAS.put(MacroActionType.GO_TO, ActionFieldSchema.builder()
                .blockPos("pos",            "Target Position").xyzDouble(true)
                .toggle  ("waitForArrival", "Wait for Arrival")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_POS, ActionFieldSchema.builder()
                .toggle  ("listenDuringPreviousAction", "Listen During Previous")
                .blockPos("pos",        "Position")      .xyzDouble(true).captureBlock()
                .decimal ("leeway",     "Leeway")        .decRange(0, 100)
                .toggle  ("checkRotation","Check Rotation")
                .decimal ("yaw",        "Yaw")           .decRange(-180, 180).showWhen("checkRotation")
                .decimal ("pitch",      "Pitch")         .decRange(-90, 90)  .showWhen("checkRotation")
                .decimal ("rotLeeway",  "Rotation Leeway").decRange(0, 180)  .showWhen("checkRotation")
                .build());

        SCHEMAS.put(MacroActionType.DISCONNECT, ActionFieldSchema.builder()
                .enumField("mode",          "Mode",         "DISCONNECT", "KICK", "KICK_DUPE", "AUTO_DISCONNECT")
                .number   ("delayMs",       "Delay (ms)")   .range(0, 10_000).showWhenEnum("mode", "DISCONNECT")
                .enumField("lagMethod",     "Lag Method",   "CLICK_SLOT", "BOAT_NBT", "ENTITY_NBT")
                                                           .hideWhenEnum("mode", "DISCONNECT")
                                                           .hideWhenEnum("mode", "AUTO_DISCONNECT")
                .enumField("kickMethod",    "Kick Method",  "HURT", "CLIENT_SETTINGS", "INVALID_SLOT")
                                                           .hideWhenEnum("mode", "DISCONNECT")
                                                           .hideWhenEnum("mode", "AUTO_DISCONNECT")
                .number   ("packetCount",   "Packet Count") .range(1, 1000).hideWhenEnum("mode", "DISCONNECT")
                                                                .hideWhenEnum("mode", "AUTO_DISCONNECT")
                .toggle   ("useNextAction", "Use Next Action").showWhenEnum("mode", "KICK_DUPE")

                .enumField("trigger",       "Trigger",      "TELEPORT", "POSITION", "WORLD_CHANGE", "GUI_CLOSE", "INVENTORY_CLEAR")
                                                           .showWhenEnum("mode", "AUTO_DISCONNECT")
                .decimal  ("tolerance",     "Tolerance")    .decRange(0, 100).showWhenEnum("trigger", "POSITION")
                .number   ("bufferMs",      "Buffer (ms)")  .range(0, 1000).showWhenEnum("mode", "AUTO_DISCONNECT")
                .number   ("timeoutSec",     "Timeout (sec)") .range(1, 300).showWhenEnum("mode", "AUTO_DISCONNECT")
                .build());

        SCHEMAS.put(MacroActionType.TOGGLE_MODULE, ActionFieldSchema.builder()
                .text     ("moduleName",  "Module Name")
                .enumField("toggleMode",  "Toggle Mode", "TOGGLE", "ENABLE", "DISABLE")
                .build());

        SCHEMAS.put(MacroActionType.START_MACRO, ActionFieldSchema.builder()
                .macroSelect("macroName",        "Macro")
                .toggle("restartIfRunning", "Restart If Running")
                .build());

        SCHEMAS.put(MacroActionType.STOP_MACRO, ActionFieldSchema.builder()
                .enumField("target",    "Target", "SELF", "SELECTED", "ALL")
                .macroSelect("macroName", "Macro").showWhenEnum("target", "SELECTED")
                .build());

        SCHEMAS.put(MacroActionType.SNEAK, ActionFieldSchema.builder()
                .toggle("sneak",      "Sneak")
                .toggle("persistent", "Persistent")
                .build());

        SCHEMAS.put(MacroActionType.JUMP, ActionFieldSchema.builder()
                .toggle("tap",           "Tap (single tick)")
                .number("durationTicks", "Duration (ticks)").range(1, 200).hideWhen("tap")
                .build());

        SCHEMAS.put(MacroActionType.SPRINT, ActionFieldSchema.builder()
                .toggle("sprint",     "Sprint")
                .toggle("persistent", "Persistent")
                .build());

        SCHEMAS.put(MacroActionType.MOVE, ActionFieldSchema.builder()
                .enumField("direction",     "Direction",      "FORWARD", "BACKWARD", "LEFT", "RIGHT")
                .number   ("durationTicks", "Duration (ticks)").range(1, 10_000)
                .toggle   ("nonBlocking",   "Non-blocking")
                .build());

        SCHEMAS.put(MacroActionType.LOOK_AT_BLOCK, ActionFieldSchema.builder()
                .enumField("targetMode", "Target Mode", "SPECIFIC", "BLOCK", "ENTITY")
                .blockPos("blockPos", "Block Position").xyzKeys("blockX", "blockY", "blockZ").captureBlock().showWhenEnum("targetMode", "SPECIFIC")
                .decimal("searchRadius", "Search Radius").decRange(1, 64).showWhenEnum("targetMode", "BLOCK").showWhenEnum("targetMode", "ENTITY")
                .stringList("blockIds", "Blocks").captureCatalog().showWhenEnum("targetMode", "BLOCK")
                .stringList("entityIds", "Entities").captureEntity().addLabel("Entity").showWhenEnum("targetMode", "ENTITY")
                .toggle("smooth", "Smooth")
                .number("smoothness", "Smoothness").range(1, 10).showWhen("smooth")
                .toggle("waitForCompletion", "Wait for Completion")
                .build());

        SCHEMAS.put(MacroActionType.REPEAT, ActionFieldSchema.builder()
                .number("stepCount",   "Steps to Repeat").range(1, 1000)
                .number("repeatCount", "Repeat Count")   .range(1, 10_000)
                .build());

        SCHEMAS.put(MacroActionType.WAIT_CHAT, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .text  ("pattern",     "Pattern")
                .toggle("useRegex",    "Use Regex")
                .number("fuzzyPercent","Match Strength").range(40, 100).hideWhen("useRegex")
                .number("timeoutMs",   "Timeout (ms)")  .range(0, 300_000)
                .toggle("waitForGuiBefore", "Before")
                .toggle("waitForGuiAfter",  "After")
                .text  ("waitGuiName", "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_ENTITY, ActionFieldSchema.builder()
                .toggle    ("listenDuringPreviousAction", "Listen During Previous")
                .enumField ("checkMode",       "Check Mode",       "RADIUS", "LOOKING_AT", "WITHIN_REACH", "MOUNTED_IN", "NEARBY")
                .stringList("entityIds",       "Entity IDs")       .addLabel("Add Entity").captureEntity()
                .toggle    ("containerEntitiesOnly", "Containers Only")
                .toggle    ("centerOnPlayer",  "Center on Player").showWhenEnum("checkMode", "RADIUS").showWhenEnum("checkMode", "NEARBY")
                .blockPos  ("pos",             "Position")         .xyzDouble(true).captureBlock()
                .decimal   ("radius",          "Radius")           .decRange(0, 100).showWhenEnum("checkMode", "RADIUS").showWhenEnum("checkMode", "NEARBY")
                .toggle    ("mustBeLookingAt", "Must Be Looking At").showWhenEnum("checkMode", "RADIUS")
                .number("timeoutMs", "Timeout ms").range(0, 300000)
                .build());

        SCHEMAS.put(MacroActionType.WAIT_SLOT_CHANGE, EMPTY);

        SCHEMAS.put(MacroActionType.OPEN_CONTAINER, ActionFieldSchema.builder()
                .enumField("targetMode", "Target", "BLOCK", "ENTITY", "LAST_TARGET")
                .blockPos("pos",       "Container Position").captureBlock().showWhenEnum("targetMode", "BLOCK")
                .stringList("entityTargets", "Entity").addLabel("Pick Entity").captureEntity().showWhenEnum("targetMode", "ENTITY")
                .toggle  ("waitForTarget", "Wait Target")
                .toggle  ("waitForGuiBefore", "Before")
                .toggle  ("waitForGuiAfter",  "After")
                .text    ("guiName",          "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.INTERACT_ENTITY, ActionFieldSchema.builder()
                .enumField("targetMode", "Target", "ENTITY", "LAST_TARGET")
                .stringList("entityTargets", "Entity").addLabel("Pick Entity").captureEntity().showWhenEnum("targetMode", "ENTITY")
                .toggle  ("waitForTarget", "Wait Target")
                .toggle  ("waitForGuiBefore", "Before")
                .toggle  ("waitForGuiAfter",  "After")
                .text    ("guiName",          "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.DESYNC, EMPTY);

        SCHEMAS.put(MacroActionType.RESTORE_GUI, ActionFieldSchema.builder()
                .toggle("waitForGuiBefore", "Before")
                .toggle("waitForGuiAfter",  "After")
                .build());

        SCHEMAS.put(MacroActionType.SAVE_GUI, ActionFieldSchema.builder()
                .toggle("closeAfter", "Close After Saving")
                .toggle("sendPacket", "Close without pkt").showWhen("closeAfter")
                .build());

        SCHEMAS.put(MacroActionType.SEND_TOGGLE, ActionFieldSchema.builder()
                .enumField("mode", "Mode", "ENABLE", "DISABLE")
                .build());

        SCHEMAS.put(MacroActionType.DELAY_PACKETS, ActionFieldSchema.builder()
                .enumField ("mode",           "Mode",        "ENABLE", "DISABLE")
                .toggle    ("flushOnDisable",  "Flush on Disable").showWhenEnum("mode", "DISABLE")
                .stringList("c2sPackets",      "C2S Packets") .addLabel("Add C2S Packet").showWhenEnum("mode", "ENABLE")
                .stringList("s2cPackets",      "S2C Packets") .addLabel("Add S2C Packet").showWhenEnum("mode", "ENABLE")
                .build());

        SCHEMAS.put(MacroActionType.INVENTORY_AUDIT, ActionFieldSchema.builder()
                .enumField ("mode",              "Mode",                "DUPE", "DUPE_SPAM")
                .stringList("targetItems",       "Targets")            .addLabel("Add Item").captureItemSlot()

                .enumField ("openMode",          "Open Method",       "COMMAND", "CONTAINER")
                .text      ("openCommand",       "Open Command")
                .blockPos  ("containerPos",      "Container")         .xyzKeys("containerX", "containerY", "containerZ").captureBlock()

                .enumField ("dupeVector",        "Dupe Vector", "DESYNC_REOPEN", "CLOSE_NO_PACKET",
                                                        "SHIFT_CLICK_REOPEN", "DELAYED_PACKETS", "SWAP_HOTBAR", "DROP_EXPLOIT",
                                                        "DELAYED_DESYNC_REOPEN", "SWAP_DESYNC_REOPEN", "DROP_DELAYED_PACKETS")
                .number    ("delayBeforeReopen", "Delay Before (ms)") .range(0, 10_000)
                .number    ("delayAfterReopen",  "Delay After (ms)")  .range(0, 10_000)
                .number    ("iterations",        "Iterations")         .range(1, 100)
                .number    ("maxTransferAttempts", "Max Transfers")   .range(1, 20)
                .number    ("transferRetryDelayMs", "Retry Delay (ms)").range(10, 500)
                .toggle    ("multipleStacks",    "Multiple Stacks")

                .number    ("spamCount",         "Spam Count")        .range(1, 20).showWhenEnum("mode", "DUPE_SPAM")
                .number    ("spamDelayMs",         "Spam Delay (ms)")  .range(10, 1000).showWhenEnum("mode", "DUPE_SPAM")
                .build());

        SCHEMAS.put(MacroActionType.STORE_ITEM, ActionFieldSchema.builder()
                .enumField ("mode",        "Mode",               "LOOT", "STORE")
                .toggle    ("allItems",    "All Items")
                .stringList("targetItems", "Target Items")       .addLabel("Add Item").captureItemSlot().hideWhen("allItems")
                .toggle    ("persistent",  "Loop Forever")
                .number    ("delayTicks",  "Item Delay").range(0, 200)
                .toggle    ("closeAfter",  "Close After")        .hideWhen("persistent")
                .toggle    ("closeSendPkt","Close without pkt")  .showWhen("closeAfter")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_SOUND, ActionFieldSchema.builder()
                .toggle    ("listenDuringPreviousAction", "Listen During Previous")
                .stringList("soundIds",      "Sound IDs")    .addLabel("Add Sound ID")
                .toggle    ("waitForGuiBefore", "Before")
                .toggle    ("waitForGuiAfter",  "After")
                .text      ("waitGuiName",   "GUI Name")
                .toggle    ("checkDistance", "Check Distance")
                .decimal   ("maxDistance",   "Max Distance") .decRange(0, 256).showWhen("checkDistance")
                .build());

        SCHEMAS.put(MacroActionType.MINE, ActionFieldSchema.builder()
                .stringList("targetBlocks",       "Target Blocks")       .addLabel("Add Block").captureCatalog()
                .toggle    ("stopInventoryFull",  "Stop: Inventory Full").exclusiveWith("stopSlotsUsed")
                .toggle    ("stopSlotsUsed",      "Stop: Slots Used")    .exclusiveWith("stopInventoryFull")
                .number    ("slotsUsedThreshold", "Slots Used Threshold").range(1, 36)   .showWhen("stopSlotsUsed")
                .toggle    ("stopMinedCount",     "Stop: Mined Count")
                .number    ("minedCountTarget",   "Mined Count Target")  .range(1, 10_000).showWhen("stopMinedCount")
                .toggle    ("stopAfterTime",      "Stop: After Time")
                .number    ("timeoutSeconds",     "Timeout (seconds)")   .range(1, 86400) .showWhen("stopAfterTime")
                .build());

        SCHEMAS.put(MacroActionType.INSTA_BREAK, ActionFieldSchema.builder()
                .blockPos  ("blockPos",            "Target Block").xyzKeys("x", "y", "z").captureBlock()
                .number    ("delayTicks",          "Delay").range(0, 20)
                .number    ("times",               "Times (0 = Infinite)").range(0, 10_000)
                .toggle    ("autoPickaxe",         "Auto Pickaxe")
                .toggle    ("interact",            "Interact")
                .enumField ("interactTiming",      "Interact Timing", "WITH", "BEFORE", "AFTER", "AFTER_PLUS", "CUSTOM").showWhen("interact")
                .number    ("interactCustomMs",    "Custom ms (±)").range(-5000, 5000).showWhenEnum("interactTiming", "CUSTOM")
                .toggle    ("sneak",               "Sneak While Mining")
                .enumField ("sneakMode",           "Sneak Mode", "Packet", "Vanilla").showWhen("sneak")
                .toggle    ("manualDirection",     "Manual Direction")
                .enumField ("direction",           "Direction", "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST").showWhen("manualDirection")
                .toggle    ("waitForGuiBefore",    "Before")
                .toggle    ("waitForGuiAfter",     "After")
                .text      ("guiName",             "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.BREAK, ActionFieldSchema.builder()
                .blockPos  ("blockPos",            "Target Block").xyzKeys("x", "y", "z").captureBlock()
                .number    ("delayTicks",          "Start Delay").range(0, 100)
                .number    ("times",               "Times (0 = Infinite)").range(0, 10_000)
                .toggle    ("autoTool",            "Auto Tool")
                .toggle    ("considerInventory",   "Tool From Inventory").showWhen("autoTool")
                .toggle    ("interact",            "Interact (GUI Race)")
                .enumField ("interactTiming",      "Interact Timing", "WITH", "BEFORE", "AFTER", "AFTER_PLUS", "CUSTOM").showWhen("interact")
                .number    ("interactCustomMs",    "Custom ms (±)").range(-5000, 5000).showWhenEnum("interactTiming", "CUSTOM")
                .toggle    ("sneak",               "Sneak While Mining")
                .enumField ("sneakMode",           "Sneak Mode", "Packet", "Vanilla").showWhen("sneak")
                .toggle    ("manualDirection",     "Manual Direction")
                .enumField ("direction",           "Direction", "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST").showWhen("manualDirection")
                .toggle    ("waitForGuiBefore",    "Before")
                .toggle    ("waitForGuiAfter",     "After")
                .text      ("guiName",             "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.PAY, ActionFieldSchema.builder()
                .text      ("commandTemplate", "Command Template")
                .text      ("amountInput",     "Amount")
                .toggle    ("delayEnabled",    "Use Delay")
                .number    ("delayMs",         "Delay (ms)").range(0, 60_000).showWhen("delayEnabled")
                .stringList("players",         "Players")  .addLabel("Add Player")
                .build());

        SCHEMAS.put(MacroActionType.SEND_CHAT, ActionFieldSchema.builder()
                .text  ("message",   "Message")
                .toggle("waitForGuiBefore", "Before")
                .toggle("waitForGuiAfter",  "After")
                .text  ("guiName",   "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.NBT_BOOK, ActionFieldSchema.builder()
                .number("pages",      "Pages")      .range(1, 100)
                .number("characters", "Chars/Page").range(1, 1024)
                .text  ("title",      "Title")
                .toggle("sign", "Sign")
                .toggle("appendCount", "Append Count")
                .enumField("dataSource", "Data Source", "Random", "File", "Pasted")
                .enumField("randomType", "Random Type", "Utf8", "Ascii", "PaperMC").showWhenEnum("dataSource", "Random")
                .text  ("customText", "Pasted Text").showWhenEnum("dataSource", "Pasted")
                .text  ("customFilePath", "Text File").showWhenEnum("dataSource", "File")
                .toggle("wordWrap", "Word Wrap").showWhenEnum("dataSource", "File").showWhenEnum("dataSource", "Pasted")
                .number("delayTicks", "Delay (ticks)").range(0, 200)
                .number("bookCount",  "Book Count")   .range(1, 64)
                .toggle("requireHeldWritableBook", "Require Held Book")
                .toggle("dropInventoryBefore",     "Drop Inventory First")
                .toggle("disconnectAfter",         "Disconnect After")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_LAN_STEP, EMPTY);

        SCHEMAS.put(MacroActionType.WAIT_MACRO_STEP, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .macroSelect("macroName", "Macro")
                .enumField("mode",      "Wait For", "COMPLETED_STEP", "STARTED_STEP", "FINISHED")
                .number   ("step",      "Step").range(1, 1000).hideWhenEnum("mode", "FINISHED")
                .number   ("timeoutMs", "Timeout (ms)").range(0, 300_000)
                .build());

        SCHEMAS.put(MacroActionType.WAIT_WORLD_CHANGE, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .text("targetDimension", "Target Dimension")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_POSITION_DELTA, ActionFieldSchema.builder()
                .toggle ("listenDuringPreviousAction", "Listen During Previous")
                .decimal("distance",       "Distance").decRange(0, 1000)
                .toggle ("horizontalOnly", "Horizontal Only")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_TELEPORT, ActionFieldSchema.builder()
                .toggle ("listenDuringPreviousAction", "Listen During Previous")
                .decimal("minDistance",    "Min Distance").decRange(0, 1000)
                .toggle ("horizontalOnly", "Horizontal Only")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_GAMEMODE_CHANGE, ActionFieldSchema.builder()
                .toggle   ("listenDuringPreviousAction", "Listen During Previous")
                .enumField("match", "Match", "ANY_CHANGE", "TO_MODE")
                .enumField("gameMode", "Game Mode", "SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR").showWhenEnum("match", "TO_MODE")
                .toggle   ("detectFake", "Detect Fake")
                .number   ("timeoutMs", "Timeout ms").range(0, 300_000)
                .build());

        SCHEMAS.put(MacroActionType.WAIT_MOVEMENT, ActionFieldSchema.builder()
                .toggle   ("listenDuringPreviousAction", "Listen During Previous")
                .enumField("mode", "Wait For", "POSITION", "POSITION_DELTA", "WORLD_CHANGE", "TELEPORT")
                .blockPos ("pos",           "Position").xyzDouble(true).captureBlock().showWhenEnum("mode", "POSITION")
                .decimal  ("leeway",        "Leeway").decRange(0, 100).showWhenEnum("mode", "POSITION")
                .toggle   ("checkRotation", "Check Rotation").showWhenEnum("mode", "POSITION")
                .decimal  ("yaw",           "Yaw").decRange(-180, 180).showWhen("checkRotation")
                .decimal  ("pitch",         "Pitch").decRange(-90, 90).showWhen("checkRotation")
                .decimal  ("rotLeeway",     "Rotation Leeway").decRange(0, 180).showWhen("checkRotation")
                .decimal  ("distance",      "Distance").decRange(0, 1000).showWhenEnum("mode", "POSITION_DELTA")
                .decimal  ("minDistance",   "Min Distance").decRange(0, 1000).showWhenEnum("mode", "TELEPORT")
                .toggle   ("horizontalOnly","Horizontal Only").showWhenEnum("mode", "POSITION_DELTA").showWhenEnum("mode", "TELEPORT")
                .text     ("targetDimension","Target Dimension").showWhenEnum("mode", "WORLD_CHANGE")
                .build());

        SCHEMAS.put(MacroActionType.RACE, ActionFieldSchema.builder()
                .text      ("label",       "Label")
                .stringList("raceSteps", "Race Steps").addLabel("Step")
                .number    ("timeoutMs",   "Timeout (ms)").range(0, 600_000)
                .build());

        SCHEMAS.put(MacroActionType.REPORT, ActionFieldSchema.builder()
                .text     ("reportLabel",        "Label")
                .enumField("startActionType",    "Start Action",
                            "ITEM", "PACKET_CLICK", "SEND_CHAT", "PAYLOAD",
                            "CLOSE_GUI", "CLICK", "USE_ITEM", "XCARRY", "DROP",
                            "SELECT_SLOT", "SWAP_SLOTS", "SEND_PACKET",
                            "INVENTORY", "RESTORE_GUI", "SAVE_GUI", "DESYNC",
                            "NBT_BOOK", "PAY", "INSTA_BREAK", "TOGGLE_MODULE",
                            "START_MACRO", "STOP_MACRO", "SNEAK", "JUMP", "SPRINT")
                .enumField("endConditionType",   "End Condition",
                            "WAIT_GUI", "WAIT_CHAT", "WAIT_PACKET",
                            "WAIT_HEALTH", "WAIT_SLOT_CHANGE", "WAIT_BLOCK",
                            "WAIT_ENTITY", "WAIT_COOLDOWN", "WAIT_POS",
                            "WAIT_SOUND", "TICK_SYNC", "REVISION_SYNC",
                            "SERVER_TICK_SYNC", "WAIT_WORLD_CHANGE",
                            "WAIT_POSITION_DELTA", "WAIT_TELEPORT",
                            "WAIT_GAMEMODE_CHANGE")
                .number   ("timeoutMs",          "Timeout (ms)").range(100, 600_000)
                .toggle   ("stashToSharedState", "Stash for later")
                .build());

        SCHEMAS.put(MacroActionType.VCLIP, ActionFieldSchema.builder()
                .enumField("mode",              "Mode", "MANUAL", "TOP", "BOTTOM")
                .decimal("deltaY",              "Y Blocks").decRange(-10000, 10000).showWhenEnum("mode", "MANUAL")
                .toggle ("useSegmented",        "Segmented (Paper)")
                .number ("segmentBlocks",       "Segment Size").range(1, 50).showWhen("useSegmented")
                .number ("maxPackets",          "Max Packets").range(1, 100).showWhen("useSegmented")
                .toggle ("forceGrounded",       "Force Grounded")
                .toggle ("updateLocalPosition", "Update Local Pos")
                .toggle ("tryVehicleFirst",     "Use Vehicle If Riding")
                .build());

        SCHEMAS.put(MacroActionType.HCLIP, ActionFieldSchema.builder()
                .enumField("mode",              "Mode", "MANUAL", "FORWARD", "BACK")
                .decimal("blocks",              "Blocks Forward").decRange(-10000, 10000).showWhenEnum("mode", "MANUAL")
                .toggle ("useSegmented",        "Packet Padding")
                .number ("segmentBlocks",       "Padding Size").range(1, 50).showWhen("useSegmented")
                .number ("maxPackets",          "Max Packets").range(1, 100).showWhen("useSegmented")
                .number ("searchRadius",        "Search Radius").range(1, 128)
                .number ("verticalRange",       "Vertical Range").range(0, 32)
                .number ("maxRoutePackets",     "Max Route Packets").range(1, 200)
                .toggle ("forceGrounded",       "On Ground")
                .toggle ("updateLocalPosition", "Update Local Pos")
                .toggle ("tryVehicleFirst",     "Use Vehicle If Riding")
                .build());

        SCHEMAS.put(MacroActionType.PACKET_GATE, ActionFieldSchema.builder()
                .enumField("mode", "Mode", "CANCEL", "DELAY", "ALLOW_ONLY")
                .enumField("scope", "Ends", "END_MARKER", "MACRO_PASS")
                .stringList("packetNames", "Packets").addLabel("Add Packet").capturePacketName()
                .toggle("flushOnDisable", "Flush").showWhenEnum("mode", "DELAY")
                .build());

        SCHEMAS.put(MacroActionType.PACKET_BURST, ActionFieldSchema.builder()
                .enumField("mode", "Mode", "CONTAINER_CLICK", "ENTITY_INTERACT", "CLIENT_COMMAND", "BUNDLE_SELECT", "USE_ITEM", "RELEASE_ITEM", "SET_CARRIED_ITEM", "CLIENT_INFORMATION", "CLOSE_CONTAINER")
                .number("count", "Count").range(1, 10000)
                .number("delayTicks", "Delay Ticks").range(0, 2000)
                .slot("slot", "Slot").showWhenEnum("mode", "CONTAINER_CLICK").showWhenEnum("mode", "BUNDLE_SELECT")
                .number("button", "Button").range(0, 10).showWhenEnum("mode", "CONTAINER_CLICK")
                .enumField("containerInput", "Input", "PICKUP", "QUICK_MOVE", "SWAP", "CLONE", "THROW", "QUICK_CRAFT", "PICKUP_ALL").showWhenEnum("mode", "CONTAINER_CLICK")
                .number("entityId", "Entity ID").range(-1, 999999).showWhenEnum("mode", "ENTITY_INTERACT")
                .enumField("hand", "Hand", "MAIN_HAND", "OFF_HAND").showWhenEnum("mode", "ENTITY_INTERACT").showWhenEnum("mode", "USE_ITEM")
                .enumField("playerCommand", "Player Cmd", "OPEN_INVENTORY", "START_FALL_FLYING", "START_SPRINTING", "STOP_SPRINTING").showWhenEnum("mode", "CLIENT_COMMAND")
                .number("bundleIndex", "Bundle Index").range(-2000, 2000).showWhenEnum("mode", "BUNDLE_SELECT")
                .number("carriedSlot", "Carried Slot").range(0, 8).showWhenEnum("mode", "SET_CARRIED_ITEM")
                .number("containerId", "Container ID").range(0, 255).showWhenEnum("mode", "CLOSE_CONTAINER")
                .toggle("flushBefore", "Flush First")
                .build());

        SCHEMAS.put(MacroActionType.BUNDLE_DUPE_V2, ActionFieldSchema.builder()
                .number("hotbarSlot", "Hotbar Slot").range(0, 8)
                .number("bundlePacketCount", "Bundle Packets").range(1, 10000)
                .number("delayAfterPickingUpMs", "Pickup Delay").range(0, 5000)
                .number("delayAfterPuttingBackMs", "Putback Delay").range(0, 5000)
                .number("dropDelayMs", "Drop Delay").range(0, 5000)
                .number("bundleIndex", "Bundle Index").range(-2000, 2000)
                .number("maxCycles", "Max Cycles").range(0, 100000)
                .build());

        SCHEMAS.put(MacroActionType.CONTAINER_CLICK_SEQUENCE, ActionFieldSchema.builder()
                .enumField("slotSource", "Slots", "SINGLE", "RANGE", "LIST", "CAPTURED_SEQUENCE")
                .enumField("containerSource", "Container", "CURRENT", "SAVED_GUI", "PLAYER_INVENTORY", "MANUAL")
                .slot("slot", "Slot").showWhenEnum("slotSource", "SINGLE")
                .slot("startSlot", "Start").range(0, 500).showWhenEnum("slotSource", "RANGE")
                .slot("endSlot", "End").range(0, 500).showWhenEnum("slotSource", "RANGE")
                .stringList("slots", "Slot List").addLabel("Add Slot").showWhenEnum("slotSource", "LIST").showWhenEnum("slotSource", "CAPTURED_SEQUENCE")
                .number("manualContainerId", "Manual ID").range(0, 255).showWhenEnum("containerSource", "MANUAL")
                .number("savedContainerId", "Saved ID").range(-1, 255).showWhenEnum("containerSource", "SAVED_GUI")
                .enumField("button", "Button", "0 (Left/Primary)", "1 (Right/Secondary)", "2 (Middle)", "3", "4", "5", "6", "7", "8", "9")
                .enumField("containerInput", "Input", "PICKUP", "QUICK_MOVE", "SWAP", "CLONE", "THROW", "QUICK_CRAFT", "PICKUP_ALL")
                .number("repeatCount", "Repeats").range(1, 1000)
                .number("delayTicks", "Delay Ticks").range(0, 2000)
                .build());

        SCHEMAS.put(MacroActionType.ASSERT, ActionFieldSchema.builder()
                .enumField("check", "Check", "HELD_ITEM", "INVENTORY_ITEM", "GUI_TYPE", "LOOKING_AT_ENTITY", "LOOKING_AT_CONTAINER_ENTITY", "MOUNTED_ENTITY", "HAS_BUNDLE", "BUNDLE_V2_READY", "HAS_WRITABLE_BOOK", "CONNECTION", "LOOKING_AT_BLOCK")
                .enumField("failureBehavior", "On Fail", "STOP_MACRO", "WARN_ONLY")
                .text("itemName", "Item").captureItemSlot().showWhenEnum("check", "HELD_ITEM").showWhenEnum("check", "INVENTORY_ITEM")
                .enumField("guiType", "GUI", "ANY", "CONTAINER", "INVENTORY", "SIGN", "HANGING_SIGN", "BOOK", "BOOK_EDIT", "BOOK_SIGN", "BOOK_VIEW", "CHAT").showWhenEnum("check", "GUI_TYPE")
                .text("entityId", "Entity").captureEntity().showWhenEnum("check", "LOOKING_AT_ENTITY").showWhenEnum("check", "LOOKING_AT_CONTAINER_ENTITY").showWhenEnum("check", "MOUNTED_ENTITY")
                .text("message", "Message")
                .build());

        SCHEMAS.put(MacroActionType.USE_ITEM_PHASE, ActionFieldSchema.builder()
                .enumField("phase", "Phase", "USE_ONCE", "START_USE", "RELEASE_USE", "USE_BLOCK", "SWING")
                .text("itemName", "Item").captureItemSlot().hideWhenEnum("phase", "SWING")
                .enumField("hand", "Hand", "MAIN_HAND", "OFF_HAND").hideWhenEnum("phase", "RELEASE_USE")
                .number("repeat", "Repeats").range(1, 1000)

                .number("holdTicks", "Hold Ticks").range(0, 2000).showWhenEnum("phase", "START_USE")
                .toggle("gateDuringHold", "Block Packets While Holding").showWhenEnum("phase", "START_USE")
                .toggle("gatePlayerActions", "Block Player Actions").showWhen("gateDuringHold")
                .toggle("gateContainerClicks", "Block Container Clicks").showWhen("gateDuringHold")
                .toggle("releaseAfterHold", "Release After Hold").showWhenEnum("phase", "START_USE")
                .number("swapSlotBeforeRelease", "Swap Slot (-1 = off)").range(-1, 500).showWhenEnum("phase", "START_USE")
                .number("swapButton", "Swap Hotbar Btn").range(0, 8).showWhenEnum("phase", "START_USE")
                .toggle("dropSlotAfterRelease", "Drop Slot After Release").showWhenEnum("phase", "START_USE")
                .number("dropSlot", "Drop Slot #").range(0, 500).showWhen("dropSlotAfterRelease")
                .build());

        SCHEMAS.put(MacroActionType.SEND_COMMAND_PACKET, ActionFieldSchema.builder()
                .text("command", "Command")
                .toggle("stripLeadingSlash", "Strip Slash")
                .build());

        SCHEMAS.put(MacroActionType.WAIT_PACKET_MATCH, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .enumField("direction", "Direction", "C2S", "S2C", "ANY")
                .text("packetName", "Packet")
                .text("fieldName", "Field")
                .enumField("operator", "Operator", "EXISTS", "EQUALS", "CONTAINS", "NOT_EQUALS")
                .text("value", "Value")
                .number("timeoutMs", "Timeout ms").range(0, 300000)
                .build());

        SCHEMAS.put(MacroActionType.WAIT_INVENTORY_PREDICATE, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .enumField("condition", "Condition", "ITEM_EXISTS", "COUNT_AT_LEAST", "COUNT_CHANGED", "COUNT_INCREASED", "COUNT_DECREASED", "SLOT_EMPTY", "SLOT_FILLED", "SLOT_CHANGED", "INVENTORY_FULL", "INVENTORY_EMPTY", "CURSOR_MATCHES", "CURSOR_EMPTY", "CURSOR_FILLED", "SELECTED_SLOT")
                .text("itemName", "Item").captureItemSlot().showWhenEnum("condition", "ITEM_EXISTS").showWhenEnum("condition", "COUNT_AT_LEAST").showWhenEnum("condition", "COUNT_CHANGED").showWhenEnum("condition", "COUNT_INCREASED").showWhenEnum("condition", "COUNT_DECREASED").showWhenEnum("condition", "CURSOR_MATCHES")
                .number("count", "Count").range(1, 100000).showWhenEnum("condition", "COUNT_AT_LEAST")
                .slot("slot", "Slot").showWhenEnum("condition", "SLOT_EMPTY").showWhenEnum("condition", "SLOT_FILLED").showWhenEnum("condition", "SLOT_CHANGED").showWhenEnum("condition", "SELECTED_SLOT")
                .number("timeoutMs", "Timeout ms").range(0, 300000)
                .build());

        SCHEMAS.put(MacroActionType.WAIT_DURABILITY, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .enumField("targetMode", "Target", "HELD", "ITEM", "SLOT")
                .text("itemName", "Item").captureItemSlot().showWhenEnum("targetMode", "ITEM")
                .slot("slot", "Slot").range(0, 40).showWhenEnum("targetMode", "SLOT")
                .enumField("measurement", "Measure", "REMAINING", "DAMAGE_USED", "PERCENT_REMAINING")
                .enumField("comparison", "Compare", "BELOW", "AT_MOST", "EXACT", "AT_LEAST", "ABOVE")
                .number("value", "Value").range(0, 4096)
                .toggle("useNext", "Use Next")
                .number("timeoutMs", "Timeout ms").range(0, 300000)
                .build());

        SCHEMAS.put(MacroActionType.WAIT_FREE_SLOTS, ActionFieldSchema.builder()
                .toggle("listenDuringPreviousAction", "Listen During Previous")
                .enumField("countMode", "Count", "FREE_SLOTS", "FILLED_SLOTS")
                .enumField("comparison", "Compare", "BELOW", "AT_MOST", "EXACT", "AT_LEAST", "ABOVE")
                .number("slots", "Slots").range(0, 36)
                .number("timeoutMs", "Timeout ms").range(0, 300000)
                .build());

        SCHEMAS.put(MacroActionType.BRANCH, ActionFieldSchema.builder()
                .enumField("conditionKind", "Condition", "ALWAYS", "GUI_TYPE", "INVENTORY_ITEM", "ENTITY_TARGET", "HELD_ITEM")
                .text("value", "Value")
                .number("thenSteps", "Then Steps").range(0, 100)
                .number("elseSteps", "Else Steps").range(0, 100)
                .build());

        SCHEMAS.put(MacroActionType.FINALLY, ActionFieldSchema.builder()
                .number("bodyCount", "Cleanup Rows").range(0, 100)
                .build());

        SCHEMAS.put(MacroActionType.MACRO_VARIABLES, ActionFieldSchema.builder()
                .stringList("names", "Names").addLabel("Add Name")
                .stringList("values", "Values").addLabel("Add Value")
                .build());

        SCHEMAS.put(MacroActionType.FAKE_GAMEMODE, ActionFieldSchema.builder()
                .enumField("mode", "Mode", "SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR", "RESET")
                .build());

        SCHEMAS.put(MacroActionType.PLACE, ActionFieldSchema.builder()
                .text("itemName", "Item").captureItemSlot()
                .blockPos("blockPos", "Place Against").xyzKeys("blockX", "blockY", "blockZ").captureBlock()
                .toggle("manualDirection", "Manual Face")
                .enumField("direction", "Face", "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST").showWhen("manualDirection")
                .toggle("sneak", "Sneak While Placing")
                .enumField("sneakMode", "Sneak Mode", "Packet", "Vanilla").showWhen("sneak")
                .toggle("interact", "Interact Placed")
                .enumField("interactTiming", "Interact Timing", "AFTER", "AFTER_PLUS", "WITH", "BEFORE", "CUSTOM").showWhen("interact")
                .number("interactCustomMs", "Custom ms (±)").range(-5000, 5000).showWhenEnum("interactTiming", "CUSTOM")
                .toggle("waitForGuiBefore", "Before")
                .toggle("waitForGuiAfter", "After")
                .text("guiName", "GUI Name")
                .build());

        SCHEMAS.put(MacroActionType.SIGN_EDIT, ActionFieldSchema.builder()
                .enumField("targetMode", "Target", "CURRENT_SIGN_GUI", "LAST_INTERACTED_BLOCK", "MANUAL_POS")
                .text("line1", "Line 1")
                .text("line2", "Line 2")
                .text("line3", "Line 3")
                .text("line4", "Line 4")
                .toggle("frontText", "Front")
                .number("x", "X").range(-30000000, 30000000).showWhenEnum("targetMode", "MANUAL_POS")
                .number("y", "Y").range(-2048, 2048).showWhenEnum("targetMode", "MANUAL_POS")
                .number("z", "Z").range(-30000000, 30000000).showWhenEnum("targetMode", "MANUAL_POS")
                .toggle("waitForGuiBefore", "Wait Before")
                .toggle("waitForGuiAfter", "Wait After")
                .enumField("guiName", "GUI", "SIGN", "HANGING_SIGN", "ANY")
                .enumField("closeMode", "Close", "STAY_OPEN", "CLOSE_LOCAL", "CLOSE_WITH_PACKET", "SEND_CLOSE_PACKET_ONLY")
                .toggle("sendCommandAfter", "Run Command After")
                .text("commandAfter", "Command").showWhen("sendCommandAfter")
                .number("closePacketContainerId", "Close Container ID").range(0, 255).showWhenEnum("closeMode", "SEND_CLOSE_PACKET_ONLY")
                .build());

    }

    public static ActionFieldSchema get(MacroActionType type) {
        return SCHEMAS.getOrDefault(type, EMPTY);
    }

    private ActionFieldRegistry() {}
}
