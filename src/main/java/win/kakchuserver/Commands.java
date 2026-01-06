package win.kakchuserver;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Commands - compact /help output: places 2-5 commands per line depending on length
 * Adds clickable Prev / Next buttons for players to navigate pages.

 * This version reads layout config from config.yml:
 *  help:
 *    lines-per-page: 3
 *    max-line-chars: 45
 *    max-per-line: 5
 *    force-second-item: true
 */
public class Commands implements CommandExecutor {

    // legacy defaults (used if config missing)
    private static final int DEFAULT_LINES_PER_PAGE = 3;
    private static final int DEFAULT_MAX_LINE_CHARS = 60;
    private static final int DEFAULT_MAX_PER_LINE = 5;
    private static final boolean DEFAULT_FORCE_SECOND_ITEM = true;

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {

        // Normalize command label to lowercase
        String name = label.toLowerCase();

        // Handle aliases automatically using plugin.yml
        switch (name) {
            case "map":
            case "servermap":
            case "worldmap":
                if (!sender.hasPermission("kakchuplugin.map")) {
                    sender.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }
                String mapUrl = Manager.getInstance().getConfig().getString("links.map-url", "there is no map found in config.");
                sendMessage(sender,
                        "§6Link To The World Map:",
                        "§b§n" + mapUrl
                );
                return true;

            case "help":
            case "commands":
            case "assistance":
                if (!sender.hasPermission("kakchuplugin.help")) {
                    sender.sendMessage("§cYou do not have permission to use this command.");
                    return true;
                }

                int page = 1;
                if (args.length >= 1) {
                    try {
                        page = Integer.parseInt(args[0]);
                        if (page < 1) page = 1;
                    } catch (NumberFormatException ignored) {
                        // ignore and default to page 1
                    }
                }
                sendHelpPage(sender, page);
                return true;

            default:
                return false;
        }
    }

    private void sendHelpPage(@NotNull CommandSender sender, int page) {
        List<HelpEntry> entries = buildHelpEntries();

        // Pack all entries into visual lines using configured layout
        List<List<HelpEntry>> allLines = packIntoLines(entries);

        int linesPerPage = getLinesPerPage();
        if (linesPerPage < 1) linesPerPage = DEFAULT_LINES_PER_PAGE;

        int totalPages = (allLines.size() + linesPerPage - 1) / linesPerPage;
        if (totalPages == 0) totalPages = 1;
        if (page > totalPages) page = totalPages;
        if (page < 1) page = 1;

        sendMessage(sender, "§6Available Commands (Page " + page + " of " + totalPages + "): ");

        int startLine = (page - 1) * linesPerPage;
        int endLine = Math.min(startLine + linesPerPage, allLines.size());
        List<List<HelpEntry>> pageLines = (startLine < endLine) ? allLines.subList(startLine, endLine) : new ArrayList<>();

        // Send each line either as clickable components (players) or plain text (console)
        boolean isPlayer = sender instanceof org.bukkit.entity.Player;
        for (List<HelpEntry> line : pageLines) {
            if (isPlayer) {
                TextComponent parent = new TextComponent("");
                boolean first = true;
                for (HelpEntry e : line) {
                    if (!first) {
                        // spacer between commands
                        parent.addExtra(new TextComponent(" §7| "));
                    } else {
                        first = false;
                    }
                    TextComponent cmdComp = new TextComponent("§b" + e.command);
                    // clicking a command places it into the player's chat input (SUGGEST)
                    cmdComp.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, e.command));
                    cmdComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§7" + e.description)));
                    parent.addExtra(cmdComp);
                }
                sender.spigot().sendMessage(parent);
            } else {
                // console fallback - join entries with " | " using command - description
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (HelpEntry e : line) {
                    if (!first) sb.append(" | ");
                    first = false;
                    sb.append(e.command).append(" - ").append(e.description);
                }
                sender.sendMessage(sb.toString());
            }
        }

        // Footer with clickable Prev / Next for players, console receives plain hint
        if (totalPages > 1) {
            if (sender instanceof org.bukkit.entity.Player) {
                int prev = Math.max(1, page - 1);
                int next = Math.min(totalPages, page + 1);

                TextComponent footer = new TextComponent("");

                // Prev button
                if (page > 1) {
                    TextComponent prevComp = new TextComponent("§a« Prev ");
                    prevComp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/help " + prev));
                    prevComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§7Go to page " + prev)));
                    footer.addExtra(prevComp);
                } else {
                    // disabled grey prev
                    footer.addExtra(new TextComponent("§7« Prev "));
                }

                // center page indicator
                footer.addExtra(new TextComponent("§f Page §e" + page + " §f/ §e" + totalPages + " "));

                // Next button
                if (page < totalPages) {
                    TextComponent nextComp = new TextComponent("§aNext »");
                    nextComp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/help " + next));
                    nextComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§7Go to page " + next)));
                    footer.addExtra(nextComp);
                } else {
                    // disabled grey next
                    footer.addExtra(new TextComponent("§7Next »"));
                }

                sender.spigot().sendMessage(footer);
                // small usage hint
                sender.spigot().sendMessage(new TextComponent("§7Click a command to place it in chat. Click the arrows to navigate pages."));
            } else {
                // Console fallback
                sendMessage(sender, "§7Use §b/help <page> §7to view other pages.");
            }
        }
    }

    /**
     * Packs the given list of HelpEntry items into visual lines.
     * Each line will attempt to contain up to maxPerLine entries and respect maxLineChars.
     * Uses configuration values, falling back to defaults if missing.
     */
    private List<List<HelpEntry>> packIntoLines(List<HelpEntry> entries) {
        List<List<HelpEntry>> lines = new ArrayList<>();

        int maxLineChars = getMaxLineChars();
        int maxPerLine = getMaxPerLine();
        boolean forceSecondItem = getForceSecondItem();

        if (maxLineChars < 10) maxLineChars = DEFAULT_MAX_LINE_CHARS;
        if (maxPerLine < 1) maxPerLine = DEFAULT_MAX_PER_LINE;

        int i = 0;
        while (i < entries.size()) {
            List<HelpEntry> line = new ArrayList<>();
            int currentLen = 0;

            // Always put at least one item
            HelpEntry first = entries.get(i);
            line.add(first);
            currentLen += displayLength(first);
            i++;

            // Try to add more up to maxPerLine, respecting maxLineChars where practical.
            while (i < entries.size() && line.size() < maxPerLine) {
                HelpEntry next = entries.get(i);
                int nextLen = displayLength(next) + 3; // add small separator cost

                // If adding next won't exceed char budget, do it.
                if (currentLen + nextLen <= maxLineChars) {
                    line.add(next);
                    currentLen += nextLen;
                    i++;
                    continue;
                }

                // If configured to force a second item and current line only has one
                // and there are remaining items, add it even if it exceeds maxLineChars.
                if (forceSecondItem && line.size() == 1 && (i < entries.size())) {
                    line.add(next);
                    currentLen += nextLen;
                    i++;
                    continue;
                }

                // otherwise break the line
                break;
            }

            lines.add(line);
        }
        return lines;
    }

    /**
     * Estimate visible length of a command label for packing decisions.
     * We ignore color codes and just use the raw command text length.
     */
    private int displayLength(HelpEntry e) {
        if (e == null || e.command == null) return 0;
        // strip leading slashes for packing smaller width ("/home" -> "home" looks slightly shorter),
        // but keep them in the displayed text.
        String cmd = e.command.startsWith("/") ? e.command.substring(1) : e.command;
        return Math.max(1, cmd.length());
    }

    private List<HelpEntry> buildHelpEntries() {
        var config = Manager.getInstance().getConfig();

        List<Map<?, ?>> rawEntries = config.getMapList("help.entries");
        if (rawEntries.isEmpty()) return List.of();

        List<HelpEntry> list = new ArrayList<>(rawEntries.size());

        for (Map<?, ?> raw : rawEntries) {
            if (raw == null) continue;

            Object cmdObj  = raw.get("command");
            Object descObj = raw.get("description");

            String cmd  = (cmdObj == null) ? "" : cmdObj.toString();
            String desc = (descObj == null) ? "" : descObj.toString();

            cmd  = cmd.stripLeading();
            desc = desc.trim();

            if (!cmd.isEmpty()) {
                list.add(new HelpEntry(cmd, desc));
            }
        }

        return list;
    }

    private void sendMessage(@NotNull CommandSender sender, String... messages) {
        for (String msg : messages) {
            sender.sendMessage(msg);
        }
    }

    private String getBuildTime() {
        try (InputStream stream = Manager.getInstance().getResource("plugin.yml")) {
            if (stream != null) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
                return yaml.getString("build-time", "unknown");
            }
        } catch (IOException e) {
            Manager.getInstance().getLogger().log(Level.SEVERE, "Failed to load build time from plugin.yml", e);
        }
        return "unknown";
    }

    // Simple container for help entries
    private static class HelpEntry {
        final String command;
        final String description;

        HelpEntry(String command, String description) {
            this.command = command;
            this.description = description;
        }
    }

    // -------------------- Config helpers --------------------

    private int getLinesPerPage() {
        try {
            return Manager.getInstance().getConfig().getInt("help.lines-per-page", DEFAULT_LINES_PER_PAGE);
        } catch (Exception ignored) {
            return DEFAULT_LINES_PER_PAGE;
        }
    }

    private int getMaxLineChars() {
        try {
            return Manager.getInstance().getConfig().getInt("help.max-line-chars", DEFAULT_MAX_LINE_CHARS);
        } catch (Exception ignored) {
            return DEFAULT_MAX_LINE_CHARS;
        }
    }

    private int getMaxPerLine() {
        try {
            return Manager.getInstance().getConfig().getInt("help.max-per-line", DEFAULT_MAX_PER_LINE);
        } catch (Exception ignored) {
            return DEFAULT_MAX_PER_LINE;
        }
    }

    private boolean getForceSecondItem() {
        try {
            return Manager.getInstance().getConfig().getBoolean("help.force-second-item", DEFAULT_FORCE_SECOND_ITEM);
        } catch (Exception ignored) {
            return DEFAULT_FORCE_SECOND_ITEM;
        }
    }
}