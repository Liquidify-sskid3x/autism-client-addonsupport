package autismclient.addons;

import java.awt.Color;

public abstract class AutismAddon {
    public String name;
    public String authors;
    public Color color = Color.WHITE;

    /**
     * Called when the addon is initialized.
     * This is the place to register modules and other components.
     */
    public abstract void onInitialize();

    /**
     * Called before initialization to register custom module categories.
     */
    public void onRegisterCategories() {}

    /**
     * Returns the base package of the addon.
     */
    public abstract String getPackage();
}
