# Autism Client Addon System

This project now supports a modular addon system, allowing external JARs to extend the client with new modules, commands, and categories.

## Work Completed

### 1. Dynamic Module Categories
Refactored `PackModuleCategory` from a static `enum` to a dynamic class-based system.
- Addons can now register their own categories using `PackModuleCategory.register("Category Name")`.
- Existing UI components (like the Module Menu) have been verified to support this dynamic list.

### 2. Addon Lifecycle & Discovery
Implemented the core addon framework:
- **`AutismAddon`**: An abstract base class that addons must extend. It provides hooks like `onInitialize()` and `onRegisterCategories()`.
- **`AddonManager`**: Handles the discovery of addons using Fabric's entry point system. It automatically populates addon metadata (name, authors, color) from `fabric.mod.json`.
- **Integration**: `AddonManager` is integrated into the main client initialization sequence.

### 3. Registry Exposure
Updated internal registries to allow external access:
- **`PackModuleRegistry`**: Made the `register(PackModule)` method public.
- **`AutismCommands`**: Made the `register(Command)` method public.

## Future Plans

- [ ] **Developer Documentation**: Create a template or guide for creating the first addon JAR.
- [ ] **GUI Enhancements**: Add an "Addons" tab or screen to show loaded addons and their metadata.
- [ ] **Event Bus Access**: Ensure addons can easily hook into the client's internal events (packets, ticks, etc.).
- [ ] **Example Addon**: Implement a small "Example Addon" within the project or as a separate repository to verify end-to-end functionality.

## How to create an addon

1. Create a new Fabric mod project.
2. Add the Autism Client JAR as a dependency.
3. Create a class extending `AutismAddon`.
4. Register your class in `fabric.mod.json` under the `autism` entry point:
   ```json
   "entrypoints": {
     "autism": [
       "com.example.addon.MyAddon"
     ]
   }
   ```
5. Use `onInitialize()` to register your modules and commands.
