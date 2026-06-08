# AUTISM Client   Addon Development Kit

This repo contains the AUTISM Client (the main utility mod) plus an example addon (`autism-test-addon`) that demonstrates how to build and package external addons.

## Requirements

- **Java 25** (toolchain auto-resolved by Gradle)
- **Fabric Loader** ≥0.18.4
- **Fabric API** ≥0.147.0
- **Minecraft** 26.1.2 (snapshots)
- **Git** (optional, for cloning)

---

## Project Structure

```
autism-client-addons/
├── build.gradle.kts              # Root build   produces AUTISM Client.jar
├── settings.gradle.kts            # Root settings (includes :autism-test-addon)
├── gradle/
│   └── libs.versions.toml         # Shared version catalog
├── src/main/java/autismclient/    # Main client source (API classes)
│   ├── addons/AutismAddon.java    # Abstract addon base class
│   ├── modules/
│   │   ├── PackModule.java        # Base module class
│   │   ├── PackModuleOption.java  # Setting system (bool, decimal, enum, entity list…)
│   │   ├── PackModuleRegistry.java
│   │   ├── PackModuleCategory.java
│   │   └── PackBuiltinModules.java
│   ├── commands/
│   │   ├── Command.java           # Base command class
│   │   └── AutismCommands.java
│   └── util/
│       ├── AutismInputClicker.java   # Queue simulated clicks
│       ├── AutismClientMessaging.java # Chat message helpers
│       └── AutismRotationUtil.java   # Rotation helpers
└── autism-test-addon/            # Example addon (subproject)
    ├── build.gradle.kts
    └── src/main/
        ├── java/com/example/testaddon/
        │   ├── TestAddon.java        # AutismAddon entry point
        │   ├── TestAddonInit.java    # Fabric ClientModInitializer
        │   └── TriggerBotModule.java # Example module
        └── resources/fabric.mod.json
```

---

## Building

### Build everything (client + addon)

```bash
./gradlew build
```

Output:

- **Client:** `build/libs/AUTISM Client-<version>.jar`
- **Addon:** `autism-test-addon/build/libs/autism-test-addon-<version>.jar`

### Run in development

```bash
./gradlew :runClient
```

---

## How the Addon System Works

Addons hook into the AUTISM Client through two entry points:

### 1. Fabric Entry Point   `client`

A standard Fabric `ClientModInitializer`. Use this to register Fabric‑API events (HUD layers, tick listeners, etc.).

```java
public class MyAddonInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Fabric API events go here
    }
}
```

Registered in `fabric.mod.json` under `"entrypoints"."client"`.

### 2. AUTISM Entry Point   `autism`

An addon class that extends `AutismAddon`. This is where you register modules, commands, and categories.

```java
public class MyAddon extends AutismAddon {
    @Override
    public void onRegisterCategories() {
        // Register custom categories BEFORE modules
    }

    @Override
    public void onInitialize() {
        // Register modules & commands
    }

    @Override
    public String getPackage() {
        return "com.example.myaddon";
    }
}
```

Registered in `fabric.mod.json` under `"entrypoints"."autism"`.

---

## Creating a Module

### Step 1 Extend `PackModule`

```java
public class MyModule extends PackModule {
    public MyModule() {
        super("my-module", "My Module", MyAddon.MY_CATEGORY, "Description.");
    }
}
```

Constructor parameters:

| Parameter   | Description                          |
|-------------|--------------------------------------|
| `id`        | Unique string identifier             |
| `name`      | Display name in UI                   |
| `category`  | `PackModuleCategory` (built-in or custom) |
| `description` | Tooltip description               |

### Step 2 Add Options (Settings)

Options are added in the constructor via `option(PackModuleOption.…​.build())`.

**Option types:**

```java
// Boolean toggle
option(PackModuleOption.bool("enabled", "Enabled", true).build());

// Integer slider
option(PackModuleOption.integer("count", "Count", 10, 1, 100, 1).build());

// Decimal slider
option(PackModuleOption.decimal("range", "Range", 4.2, 1.0, 8.0, 0.1).build());

// Enum choice
option(PackModuleOption.enumChoice("mode", "Mode", "Fast", "Fast", "Slow", "Accurate").build());

// Entity type list (multi‑select registry, like AimAssist entity filter)
option(PackModuleOption.registryList(Type.ENTITY_TYPE_LIST, "entities", "Entities", "minecraft:player")
    .description("Entities to target.").build());

// Color picker
option(PackModuleOption.color("color", "Color", 0xCCFF00AA).build());

// Action button
option(PackModuleOption.action("reset", "Reset", this::resetSomething).build());
```

### Step 3 Read Option Values at Runtime

```java
boolean myBool   = bool("enabled");
int    myInt     = integer("count");
double myDouble  = decimal("range");
String myChoice  = choice("mode");        // alias for text()
String myText    = text("name");
List<String> ids = list("entities");      // ENTITY_TYPE_LIST returns List<String>
```

### Step 4   Override Lifecycle Hooks

```java
@Override public void onEnable()  { /* called when module is turned on */  }
@Override public void onDisable() { /* called when module is turned off */ }

@Override public void tick() {
    // Called every client tick   use for combat, movement, etc.
}

@Override public void onRenderLevel(float partialTick) {
    // Called every frame during world rendering
    // Use for 3D world‑space effects (ESP, tracers)
}

@Override public boolean onPacketSend(Packet<?> packet)    { return false; } // true = cancel
@Override public boolean onPacketReceive(Packet<?> packet) { return false; } // true = cancel
@Override public void onGameJoin()  { }
@Override public void onGameLeft()  { }
```

### Step 5 Register in `onInitialize()`

```java
PackModuleRegistry.register(new MyModule());
```

---

## Built-in Categories

```java
PackModuleCategory.MOVEMENT
PackModuleCategory.PLAYER
PackModuleCategory.MISC
PackModuleCategory.RENDER
```

### Custom Categories

Register in `onRegisterCategories()`:

```java
public static PackModuleCategory MY_CATEGORY;

@Override
public void onRegisterCategories() {
    MY_CATEGORY = PackModuleCategory.register("My Category");
}
```

---

## Creating a Command

### Extend `Command`

```java
public class MyCommand extends Command {
    public MyCommand() {
        super("mycommand", "Does something cool.");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> {
            AutismClientMessaging.sendPrefixed("Hello from addon!");
            return SUCCESS;
        });
    }
}
```

### Register

```java
AutismCommands.register(new MyCommand());
```

---

## HUD / 2D Screen Rendering

Use Fabric's `HudLayerRegistrationCallback` (Fabric API ≥0.116, MC 1.21.5+):

```java
HudLayerRegistrationCallback.EVENT.register(layeredDrawer -> {
    layeredDrawer.attachLayerAfter(
        IdentifiedLayer.CROSSHAIR,
        Identifier.fromNamespaceAndPath("my-addon", "my-element"),
        (graphics, deltaTracker) -> {
            // graphics is GuiGraphicsExtractor
            graphics.fill(x0, y0, x1, y1, color);
        }
    );
});
```

Place this in your `ClientModInitializer#onInitializeClient()`.

Render helpers available:

```java
UiRenderer.rect(context, UiBounds.of(x, y, w, h), color);  // filled rect
UiRenderer.outline(context, UiBounds.of(x, y, w, h), color); // 1px border
```

---

## Simulating Input

Use `AutismInputClicker` for reliable attack/use simulation (handles cooldown, keybind state, hides itself when screens are open):

```java
AutismInputClicker.queueAttackClick();  // fires one left‑click
AutismInputClicker.queueUseClick();     // fires one right‑click
AutismInputClicker.queueHotbarSlot(4);  // switches to hotbar slot 5
```

Call these from `tick()`. The clicker automatically respects Minecraft's input state.

---

## Messaging

Send messages in the AUTISM client's chat style:

```java
AutismClientMessaging.send("Raw message");
AutismClientMessaging.sendPrefixed("TriggerBot: attacked Player"); // → [AUTISM] TriggerBot: attacked Player
```

---

## Entity Targeting Pattern (like AimAssist)

The entity type list option stores entity IDs (e.g. `minecraft:player`). Use a cached lookup for fast matching:

```java
private Set<String> cachedEntityIds() {
    List<String> entries = list("entities");
    String source = String.join("|", entries);
    if (source.equals(cachedEntityListSource)) return cachedEntityIds;
    Set<String> normalized = new LinkedHashSet<>();
    for (String entry : entries) {
        String value = entry.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) continue;
        normalized.add(value);
        int split = value.indexOf(':');
        if (split >= 0 && split + 1 < value.length())
            normalized.add(value.substring(split + 1));
    }
    cachedEntityListSource = source;
    cachedEntityIds = Set.copyOf(normalized);
    return cachedEntityIds;
}

private boolean matches(Entity entity) {
    Set<String> ids = cachedEntityIds();
    if (ids.isEmpty()) return true;
    String key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    String simple = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
    return ids.contains(key) || ids.contains(simple);
}
```

---

## Addon Build Configuration

A minimal `build.gradle.kts` using the AUTISM Client as a compile‑only dependency:

```kotlin
plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = "my-addon"
    version      = "1.0.0"
    group        = "com.example.myaddon"
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    compileOnly(project(":"))   // AUTISM Client jar   provided at runtime
}
```

### `fabric.mod.json` Template

```json
{
  "schemaVersion": 1,
  "id": "my-addon",
  "version": "${version}",
  "name": "My Addon",
  "description": "Description here.",
  "authors": ["you"],
  "environment": "client",
  "entrypoints": {
    "client": [ "com.example.myaddon.MyAddonInit" ],
    "autism":  [ "com.example.myaddon.MyAddon" ]
  },
  "depends": {
    "java": ">=25",
    "minecraft": "${mc_version}",
    "fabricloader": ">=0.18.4",
    "fabric-api": "*",
    "autism": "*"
  },
  "custom": {
    "autism:color": "#00FFAA"
  }
}
```

### Distribution

Building `./gradlew build` produces two jars:

1. **`build/libs/AUTISM Client-<version>.jar`**   the main utility mod
2. **`autism-test-addon/build/libs/autism-test-addon-<version>.jar`**   your addon

Users place **both** jars in their `mods/` folder. Fabric Loader resolves the `"autism": "*"` dependency at runtime.

---

## Quick Reference   Addon API Classes

| Class / Method | Purpose |
|----------------|---------|
| `AutismAddon` | Base class; override `onInitialize()`, `onRegisterCategories()`, `getPackage()` |
| `PackModule` | Base for all modules; override `tick()`, `onEnable()`, `onDisable()`, `onRenderLevel()`, `onPacketSend/Receive()` |
| `PackModuleOption` | Fluent setting builder: `.bool()`, `.decimal()`, `.integer()`, `.enumChoice()`, `.registryList(Type.ENTITY_TYPE_LIST)`, `.color()`, `.action()` |
| `PackModuleCategory` | `register("name")` creates a custom category |
| `PackModuleRegistry` | `register(module)`, `get(id)`, `all()` |
| `Command` | Base for commands; `build(LiteralArgumentBuilder<AutismCommandSource>)`, use `SUCCESS` return |
| `AutismCommands` | `register(command)` |
| `AutismInputClicker` | `queueAttackClick()`, `queueUseClick()`, `queueHotbarSlot(int)` |
| `AutismClientMessaging` | `send(text)`, `sendPrefixed(text)` |
| `AutismRotationUtil` | `playerRotation()`, `lookingAt()`, `apply()`, `interpolate()` |
| `HudLayerRegistrationCallback` (Fabric API) | Register HUD overlay elements |
| `GuiGraphicsExtractor` | `fill(x0, y0, x1, y1, color)` for 2D screen rendering |

# THIS IS BASED OFF OF THE METEOR CLIENT'S ADDON MANAGER AND ADDON SUPPORT, NOTHING IS CHANGED.
