package com.example.testaddon;

import autismclient.addons.AutismAddon;
import autismclient.modules.PackModuleCategory;
import autismclient.modules.PackModuleRegistry;

public class TestAddon extends AutismAddon {

    public static PackModuleCategory LIQUIDIFY_CATEGORY;

    @Override
    public void onRegisterCategories() {
        LIQUIDIFY_CATEGORY = PackModuleCategory.register("Liquidify");
    }

    @Override
    public void onInitialize() {
        PackModuleRegistry.register(new TriggerBotModule());
    }

    @Override
    public String getPackage() {
        return "com.example.testaddon";
    }
}
