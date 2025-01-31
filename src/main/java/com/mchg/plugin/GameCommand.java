package com.mchg.plugin;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GameCommand implements CommandExecutor {
    private final HungerGames plugin;
    
    public GameCommand(HungerGames plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players!");
            return true;
        }
        
        Player player = (Player) sender;
        
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "join":
                plugin.addPlayer(player);
                break;
            case "leave":
                plugin.removePlayer(player);
                break;
            case "start":
                if (!player.hasPermission("hungergames.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to start the game!");
                    return true;
                }
                plugin.startGame();
                break;
            case "stop":
                if (!player.hasPermission("hungergames.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to stop the game!");
                    return true;
                }
                plugin.endGame();
                break;
            case "stats":
                showStats(player);
                break;
            case "autostart":
                if (!player.hasPermission("hungergames.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to manage auto-start!");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /hg autostart <on|off>");
                    return true;
                }
                boolean enable = args[1].equalsIgnoreCase("on");
                plugin.getGameManager().setAutoStartEnabled(enable);
                player.sendMessage(ChatColor.GREEN + "Auto-start has been " + 
                    (enable ? "enabled" : "disabled"));
                break;
            case "minplayers":
                if (!player.hasPermission("hungergames.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to set minimum players!");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /hg minplayers <count>");
                    return true;
                }
                try {
                    int count = Integer.parseInt(args[1]);
                    if (count < 2 || count > 24) {
                        player.sendMessage(ChatColor.RED + "Player count must be between 2 and 24!");
                        return true;
                    }
                    plugin.getGameManager().setMinPlayersToStart(count);
                    player.sendMessage(ChatColor.GREEN + "Minimum players set to " + count);
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Invalid number format!");
                }
                break;
            case "addbot":
                if (!player.hasPermission("hungergames.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to add bots!");
                    return true;
                }
                plugin.getBotManager().addBot();
                break;
            case "addbots":
                if (!player.hasPermission("hungergames.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to add bots!");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /hg addbots <count>");
                    return true;
                }
                try {
                    int count = Integer.parseInt(args[1]);
                    if (count < 1 || count > 23) {
                        player.sendMessage(ChatColor.RED + "Bot count must be between 1 and 23!");
                        return true;
                    }
                    for (int i = 0; i < count; i++) {
                        plugin.getBotManager().addBot();
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Invalid number format!");
                }
                break;
            case "clearbots":
                if (!player.hasPermission("hungergames.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to clear bots!");
                    return true;
                }
                plugin.getBotManager().clearBots();
                player.sendMessage(ChatColor.GREEN + "All bots have been removed!");
                break;
            default:
                sendHelp(player);
                break;
        }
        
        return true;
    }
    
    private void showStats(Player player) {
        GameManager.PlayerStats stats = plugin.getGameManager().getPlayerStats(player);
        
        player.sendMessage(ChatColor.GOLD + "=== Your Stats ===");
        player.sendMessage(ChatColor.YELLOW + "Wins: " + ChatColor.WHITE + stats.wins);
        player.sendMessage(ChatColor.YELLOW + "Games Played: " + ChatColor.WHITE + stats.gamesPlayed);
        player.sendMessage(ChatColor.YELLOW + "Kills: " + ChatColor.WHITE + stats.kills);
        player.sendMessage(ChatColor.YELLOW + "Deaths: " + ChatColor.WHITE + stats.deaths);
        if (stats.deaths > 0) {
            double kdr = (double) stats.kills / stats.deaths;
            player.sendMessage(ChatColor.YELLOW + "K/D Ratio: " + ChatColor.WHITE + 
                String.format("%.2f", kdr));
        }
        if (stats.gamesPlayed > 0) {
            double winRate = (double) stats.wins / stats.gamesPlayed * 100;
            player.sendMessage(ChatColor.YELLOW + "Win Rate: " + ChatColor.WHITE + 
                String.format("%.1f", winRate) + "%");
        }
    }
    
    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== HungerGames Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/hg join " + ChatColor.GRAY + "- Join the game");
        player.sendMessage(ChatColor.YELLOW + "/hg leave " + ChatColor.GRAY + "- Leave the game");
        player.sendMessage(ChatColor.YELLOW + "/hg stats " + ChatColor.GRAY + "- View your stats");
        
        if (player.hasPermission("hungergames.admin")) {
            player.sendMessage(ChatColor.GOLD + "=== Admin Commands ===");
            player.sendMessage(ChatColor.YELLOW + "/hg start " + ChatColor.GRAY + "- Force start the game");
            player.sendMessage(ChatColor.YELLOW + "/hg stop " + ChatColor.GRAY + "- Stop the game");
            player.sendMessage(ChatColor.YELLOW + "/hg autostart <on|off> " + ChatColor.GRAY + 
                "- Toggle auto-start");
            player.sendMessage(ChatColor.YELLOW + "/hg minplayers <count> " + ChatColor.GRAY + 
                "- Set minimum players");
            player.sendMessage(ChatColor.YELLOW + "/hg addbot " + ChatColor.GRAY + 
                "- Add a single bot");
            player.sendMessage(ChatColor.YELLOW + "/hg addbots <count> " + ChatColor.GRAY + 
                "- Add multiple bots");
            player.sendMessage(ChatColor.YELLOW + "/hg clearbots " + ChatColor.GRAY + 
                "- Remove all bots");
        }
    }
} 