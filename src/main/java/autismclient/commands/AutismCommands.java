package autismclient.commands;

import autismclient.AutismClientAddon;
import autismclient.modules.PackHideState;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismCompatManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public final class AutismCommands {
    private static final CommandDispatcher<AutismCommandSource> DISPATCHER = new CommandDispatcher<>();
    private static final List<Command> ALL = new ArrayList<>();
    private static final Map<String, Command> BY_NAME = new LinkedHashMap<>();
    private static boolean initialized = false;

    private AutismCommands() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        register(new autismclient.commands.impl.ToggleCommand());
        register(new autismclient.commands.impl.MacroCommand());
        register(new autismclient.commands.impl.DelayCommand());
        register(new autismclient.commands.impl.SendCommand());
        register(new autismclient.commands.impl.VClipCommand());
        register(new autismclient.commands.impl.HClipCommand());
        register(new autismclient.commands.impl.NbtCommand());
        register(new autismclient.commands.impl.ServerCommand());
        register(new autismclient.commands.impl.PluginsCommand());
        register(new autismclient.commands.impl.PrefixCommand());
        register(new autismclient.commands.impl.CommandsCommand());
        register(new autismclient.commands.impl.HelpCommand());
        register(new autismclient.commands.impl.ModulesCommand());
        register(new autismclient.commands.impl.BindsCommand());
        register(new autismclient.commands.impl.BindCommand());
        register(new autismclient.commands.impl.DismountCommand());
        register(new autismclient.commands.impl.DisconnectCommand());
        register(new autismclient.commands.impl.SayCommand());
        register(new autismclient.commands.impl.DropCommand());
        register(new autismclient.commands.impl.GamemodeCommand());
        register(new autismclient.commands.impl.GiveCommand());
        register(new autismclient.commands.impl.DamageCommand());
        register(new autismclient.commands.impl.XCarryCommand());
    }

    public static void register(Command command) {
        ALL.add(command);
        LiteralArgumentBuilder<AutismCommandSource> primary = LiteralArgumentBuilder.literal(command.name());
        command.build(primary);
        DISPATCHER.register(primary);
        BY_NAME.put(command.name().toLowerCase(Locale.ROOT), command);

        for (String alias : command.aliases()) {
            if (alias == null || alias.isBlank()) continue;
            LiteralArgumentBuilder<AutismCommandSource> aliasBuilder = LiteralArgumentBuilder.literal(alias);
            command.build(aliasBuilder);
            DISPATCHER.register(aliasBuilder);
            BY_NAME.put(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    public static CommandDispatcher<AutismCommandSource> dispatcher() { return DISPATCHER; }

    public static List<Command> all() { return Collections.unmodifiableList(ALL); }

    public static Command find(String nameOrAlias) {
        if (nameOrAlias == null) return null;
        return BY_NAME.get(nameOrAlias.trim().toLowerCase(Locale.ROOT));
    }

    public static String effectivePrefix() { return AutismCompatManager.effectiveCommandPrefix(); }

    public static boolean isAutismCommandMessage(String message) {
        if (commandsBlockedByPanic()) return false;
        if (message == null || message.isBlank()) return false;
        String trimmed = message.trim();
        String prefix = effectivePrefix();
        return !prefix.isEmpty() && trimmed.startsWith(prefix);
    }

    public static boolean commandsBlockedByPanic() {
        return PackHideState.isActive();
    }

    public static boolean isBlockedPanicCommandMessage(String message) {
        return commandsBlockedByPanic() && isAutismCommandMessage(message);
    }

    public static String commandBody(String message) {
        if (!isAutismCommandMessage(message)) return "";
        String trimmed = message.trim();
        int prefixLength = effectivePrefix().length();
        return trimmed.length() <= prefixLength ? "" : trimmed.substring(prefixLength).trim();
    }

    public static boolean dispatch(String body) {
        if (commandsBlockedByPanic()) return true;
        if (body == null) return false;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return false;
        try {
            DISPATCHER.execute(trimmed, AutismCommandSource.INSTANCE);
            return true;
        } catch (CommandSyntaxException e) {
            sendSyntaxError(trimmed, e);
            return true;
        } catch (Exception e) {
            AutismClientAddon.LOG.warn("[Commands] dispatch failed for '{}'", trimmed, e);
            AutismClientMessaging.sendPrefixed("§cCommand error: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return true;
        }
    }

    private static void sendSyntaxError(String body, CommandSyntaxException e) {
        String first = firstToken(body);
        Command command = find(first);
        String prefix = effectivePrefix();
        if (command == null) {
            AutismClientMessaging.sendPrefixed("§cUnknown AUTISM command: §f" + first);
            AutismClientMessaging.sendPrefixed("§7Use §f" + prefix + "commands §7or §f" + prefix + "help§7.");
            return;
        }

        String message = e.getMessage();
        if (message == null || message.isBlank()) message = "Incomplete or invalid command.";
        AutismClientMessaging.sendPrefixed("§c" + message);
        AutismClientMessaging.sendPrefixed("§7Use §f" + prefix + "help " + command.name() + "§7.");
    }

    private static String firstToken(String body) {
        if (body == null) return "";
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return "";
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
