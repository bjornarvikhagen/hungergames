package com.mchg.plugin;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class SpectatorManager {
    private final HungerGames plugin;
    private final Map<UUID, UUID> spectatorTargets; // Spectator UUID -> Target UUID
    private final Map<UUID, Location> deathLocations; // Player UUID -> Death Location
    
    private static final ItemStack PLAYER_TRACKER = createItem(Material.COMPASS, ChatColor.GREEN + "Player Tracker");
    private static final ItemStack TELEPORTER = createItem(Material.ENDER_PEARL, ChatColor.AQUA + "Quick Teleport");
    private static final ItemStack NIGHT_VISION = createItem(Material.ENDER_EYE, ChatColor.GOLD + "Toggle Night Vision");
    private static final ItemStack SPEED_TOGGLE = createItem(Material.FEATHER, ChatColor.YELLOW + "Toggle Speed");
    
    public SpectatorManager(HungerGames plugin) {
        this.plugin = plugin;
        this.spectatorTargets = new HashMap<>();
        this.deathLocations = new HashMap<>();
        startTrackerUpdater();
    }
    
    private static ItemStack createItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
    
    public void setSpectator(Player player) {
        player.setGameMode(GameMode.SPECTATOR);
        deathLocations.put(player.getUniqueId(), player.getLocation());
        
        // Give spectator items when they respawn
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && player.getGameMode() == GameMode.SPECTATOR) {
                    giveSpectatorItems(player);
                }
            }
        }.runTaskLater(plugin, 5L);
    }
    
    private void giveSpectatorItems(Player player) {
        player.getInventory().clear();
        player.getInventory().setItem(0, PLAYER_TRACKER.clone());
        player.getInventory().setItem(1, TELEPORTER.clone());
        player.getInventory().setItem(2, NIGHT_VISION.clone());
        player.getInventory().setItem(3, SPEED_TOGGLE.clone());
    }
    
    public void handleSpectatorInteract(Player spectator, ItemStack item) {
        if (item == null || !spectator.getGameMode().equals(GameMode.SPECTATOR)) return;
        
        String itemName = item.getItemMeta() != null ? item.getItemMeta().getDisplayName() : "";
        
        switch (itemName) {
            case "Player Tracker":
                openPlayerSelector(spectator);
                break;
            case "Quick Teleport":
                teleportToNextPlayer(spectator);
                break;
            case "Toggle Night Vision":
                toggleNightVision(spectator);
                break;
            case "Toggle Speed":
                toggleSpectatorSpeed(spectator);
                break;
        }
    }
    
    private void openPlayerSelector(Player spectator) {
        List<Player> alivePlayers = plugin.getAlivePlayers();
        if (alivePlayers.isEmpty()) {
            spectator.sendMessage(ChatColor.RED + "No players to track!");
            return;
        }
        
        // TODO: Implement a proper GUI menu for player selection
        Player target = alivePlayers.get(new Random().nextInt(alivePlayers.size()));
        spectatorTargets.put(spectator.getUniqueId(), target.getUniqueId());
        spectator.sendMessage(ChatColor.GREEN + "Now tracking: " + target.getName());
    }
    
    private void teleportToNextPlayer(Player spectator) {
        List<Player> alivePlayers = plugin.getAlivePlayers();
        if (alivePlayers.isEmpty()) {
            spectator.sendMessage(ChatColor.RED + "No players to teleport to!");
            return;
        }
        
        UUID currentTarget = spectatorTargets.get(spectator.getUniqueId());
        int currentIndex = -1;
        
        if (currentTarget != null) {
            for (int i = 0; i < alivePlayers.size(); i++) {
                if (alivePlayers.get(i).getUniqueId().equals(currentTarget)) {
                    currentIndex = i;
                    break;
                }
            }
        }
        
        int nextIndex = (currentIndex + 1) % alivePlayers.size();
        Player nextTarget = alivePlayers.get(nextIndex);
        
        spectator.teleport(nextTarget.getLocation());
        spectatorTargets.put(spectator.getUniqueId(), nextTarget.getUniqueId());
        spectator.sendMessage(ChatColor.GREEN + "Teleported to: " + nextTarget.getName());
    }
    
    private void toggleNightVision(Player spectator) {
        if (spectator.hasPotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION)) {
            spectator.removePotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION);
            spectator.sendMessage(ChatColor.RED + "Night Vision disabled");
        } else {
            spectator.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.NIGHT_VISION, 
                Integer.MAX_VALUE, 
                0, 
                true, 
                false));
            spectator.sendMessage(ChatColor.GREEN + "Night Vision enabled");
        }
    }
    
    private void toggleSpectatorSpeed(Player spectator) {
        if (spectator.getFlySpeed() > 0.1f) {
            spectator.setFlySpeed(0.1f);
            spectator.sendMessage(ChatColor.RED + "Speed boost disabled");
        } else {
            spectator.setFlySpeed(0.3f);
            spectator.sendMessage(ChatColor.GREEN + "Speed boost enabled");
        }
    }
    
    private void startTrackerUpdater() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<UUID, UUID> entry : spectatorTargets.entrySet()) {
                    Player spectator = plugin.getServer().getPlayer(entry.getKey());
                    Player target = plugin.getServer().getPlayer(entry.getValue());
                    
                    if (spectator != null && target != null && 
                        spectator.isOnline() && target.isOnline() &&
                        spectator.getInventory().contains(PLAYER_TRACKER)) {
                        
                        spectator.setCompassTarget(target.getLocation());
                        
                        // Update action bar with distance
                        double distance = spectator.getLocation().distance(target.getLocation());
                        String message = ChatColor.GREEN + "Tracking: " + target.getName() + 
                            ChatColor.GRAY + " - Distance: " + 
                            ChatColor.YELLOW + String.format("%.1f", distance) + "m";
                        spectator.spigot().sendMessage(
                            net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L); // Update every half second
    }
    
    public Location getDeathLocation(Player player) {
        return deathLocations.get(player.getUniqueId());
    }
    
    public void clearSpectatorData() {
        spectatorTargets.clear();
        deathLocations.clear();
    }
} 