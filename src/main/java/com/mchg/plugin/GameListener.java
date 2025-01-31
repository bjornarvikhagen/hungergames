package com.mchg.plugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.block.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

public class GameListener implements Listener {
    private final HungerGames plugin;
    
    public GameListener(HungerGames plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        try {
            Player player = event.getEntity();
            if (plugin.getPlayers().contains(player)) {
                event.setKeepInventory(false);
                event.getDrops().clear(); // Clear drops to prevent item farming
                plugin.eliminatePlayer(player);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling player death: " + e.getMessage());
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        try {
            Player player = event.getPlayer();
            if (plugin.getPlayerStates().containsKey(player)) {
                if (plugin.getGameState() == GameState.ACTIVE) {
                    // Set them as spectator at death location
                    event.setRespawnLocation(player.getLocation());
                    player.setGameMode(GameMode.SPECTATOR);
                } else {
                    // Send them to lobby
                    event.setRespawnLocation(plugin.getServer().getWorlds().get(0).getSpawnLocation());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling player respawn: " + e.getMessage());
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            Player player = event.getPlayer();
            if (plugin.getPlayers().contains(player)) {
                plugin.removePlayer(player);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling player quit: " + e.getMessage());
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            if (plugin.getPlayerStates().containsKey(player) && 
                plugin.getPlayerStates().get(player) == PlayerState.DEAD &&
                plugin.getGameState() == GameState.ACTIVE) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling player join: " + e.getMessage());
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        try {
            if (!(event.getEntity() instanceof Player)) return;
            Player player = (Player) event.getEntity();
            
            // Cancel all damage during non-active game states
            if (plugin.getGameState() != GameState.ACTIVE) {
                event.setCancelled(true);
                return;
            }
            
            // Handle PvP specifically
            if (event instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent pvpEvent = (EntityDamageByEntityEvent) event;
                if (!(pvpEvent.getDamager() instanceof Player)) return;
                
                Player damager = (Player) pvpEvent.getDamager();
                
                // Check if either player is not in the game
                if (!plugin.getPlayers().contains(player) || !plugin.getPlayers().contains(damager)) {
                    event.setCancelled(true);
                    return;
                }
                
                // Check if either player is dead
                if (plugin.getPlayerStates().get(player) == PlayerState.DEAD || 
                    plugin.getPlayerStates().get(damager) == PlayerState.DEAD) {
                    event.setCancelled(true);
                    return;
                }
            }
            
            // Cancel damage for players in grace period
            if (plugin.isGracePeriod()) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling entity damage: " + e.getMessage());
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        try {
            Player player = event.getPlayer();
            if (!plugin.getPlayers().contains(player)) return;
            
            // Prevent movement during countdown (allow head movement)
            if (plugin.getGameState() == GameState.STARTING) {
                Location from = event.getFrom();
                Location to = event.getTo();
                if (to != null && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
                    event.setTo(from);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling player movement: " + e.getMessage());
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        try {
            Player player = event.getPlayer();
            // Prevent item dropping during countdown and grace period
            if (plugin.getPlayers().contains(player) && 
                (plugin.getGameState() == GameState.STARTING || 
                (plugin.getGameState() == GameState.ACTIVE && plugin.isGracePeriod()))) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling item drop: " + e.getMessage());
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        try {
            if (!(event.getEntity() instanceof Player)) return;
            Player player = (Player) event.getEntity();
            
            // Prevent item pickup during countdown and grace period
            if (plugin.getPlayers().contains(player) && 
                (plugin.getGameState() == GameState.STARTING || 
                (plugin.getGameState() == GameState.ACTIVE && plugin.isGracePeriod()))) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling item pickup: " + e.getMessage());
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        try {
            Player player = event.getPlayer();
            if (plugin.getPlayers().contains(player) && 
                (plugin.getGameState() != GameState.ACTIVE || plugin.isGracePeriod())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling block break: " + e.getMessage());
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        try {
            Player player = event.getPlayer();
            if (plugin.getPlayers().contains(player) && 
                (plugin.getGameState() != GameState.ACTIVE || plugin.isGracePeriod())) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling block place: " + e.getMessage());
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerPortal(PlayerPortalEvent event) {
        try {
            if (plugin.getGameState() == GameState.ACTIVE) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Portal travel is disabled during the game!");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling portal event: " + e.getMessage());
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCombatLog(PlayerQuitEvent event) {
        try {
            Player player = event.getPlayer();
            if (plugin.getGameState() == GameState.ACTIVE && 
                plugin.getPlayerStates().get(player) == PlayerState.ALIVE) {
                plugin.eliminatePlayer(player);
                plugin.broadcast(ChatColor.RED + player.getName() + " was eliminated for combat logging!");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling combat log: " + e.getMessage());
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        try {
            Player player = event.getPlayer();
            if (player.getGameMode() == GameMode.SPECTATOR) {
                ItemStack item = event.getItem();
                if (item != null) {
                    plugin.getSpectatorManager().handleSpectatorInteract(player, item);
                }
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling spectator interact: " + e.getMessage());
            event.setCancelled(true);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        try {
            if (!(event.getWhoClicked() instanceof Player)) return;
            Player player = (Player) event.getWhoClicked();
            
            // Prevent inventory manipulation during countdown and grace period
            if (plugin.getPlayers().contains(player) && 
                (plugin.getGameState() == GameState.STARTING || 
                (plugin.getGameState() == GameState.ACTIVE && plugin.isGracePeriod()))) {
                event.setCancelled(true);
            }
            
            // Prevent spectators from modifying inventory
            if (player.getGameMode() == GameMode.SPECTATOR) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling inventory click: " + e.getMessage());
            event.setCancelled(true);
        }
    }
} 