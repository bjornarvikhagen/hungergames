package com.mchg.plugin;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FireworkEffect;
import org.bukkit.entity.FireworkMeta;
import org.bukkit.Color;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

public class GameManager {
    private final HungerGames plugin;
    private final Map<UUID, PlayerStats> playerStats;
    private Scoreboard scoreboard;
    private boolean autoStartEnabled = true;
    private int minPlayersToStart = 2;
    private int maxPlayersPerGame = 24;
    private int autoStartTimer = 60; // seconds
    private BukkitRunnable autoStartTask;
    
    private final Object scoreboardLock = new Object();
    private final Object statsLock = new Object();
    
    private final Map<UUID, ItemStack[]> savedInventories = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedArmorContents = new HashMap<>();
    private final Map<UUID, Location> savedLocations = new HashMap<>();
    
    public GameManager(HungerGames plugin) {
        this.plugin = plugin;
        this.playerStats = new HashMap<>();
        setupScoreboard();
    }
    
    private void setupScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        scoreboard = manager.getNewScoreboard();
        
        Objective objective = scoreboard.registerNewObjective("hgStats", "dummy", 
            ChatColor.GOLD + "Hunger Games");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }
    
    public void updateScoreboard() {
        synchronized (scoreboardLock) {
            try {
                Objective objective = scoreboard.getObjective("hgStats");
                if (objective != null) {
                    objective.unregister();
                }
                objective = scoreboard.registerNewObjective("hgStats", "dummy", 
                    ChatColor.GOLD + "Hunger Games");
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
                
                int aliveCount = plugin.getAlivePlayers().size();
                int totalPlayers = plugin.getPlayers().size();
                
                objective.getScore(ChatColor.GREEN + "Players Alive").setScore(aliveCount);
                objective.getScore(ChatColor.RED + "Players Dead").setScore(totalPlayers - aliveCount);
                
                if (plugin.getGameState() == GameState.ACTIVE) {
                    World gameWorld = plugin.getGameWorld();
                    if (gameWorld != null) {
                        WorldBorder border = gameWorld.getWorldBorder();
                        objective.getScore(ChatColor.YELLOW + "Border: " + 
                            (int)border.getSize()).setScore(0);
                    }
                }
                
                for (Player player : plugin.getPlayers()) {
                    if (player.isOnline()) {
                        player.setScoreboard(scoreboard);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to update scoreboard: " + e.getMessage());
            }
        }
    }
    
    public void checkAutoStart() {
        if (!autoStartEnabled || plugin.getGameState() != GameState.WAITING) {
            return;
        }
        
        int playerCount = plugin.getPlayers().size();
        
        if (playerCount >= minPlayersToStart && playerCount <= maxPlayersPerGame) {
            if (autoStartTask == null) {
                startAutoStartTimer();
            }
        } else {
            cancelAutoStart();
        }
    }
    
    private void startAutoStartTimer() {
        autoStartTask = new BukkitRunnable() {
            int countdown = autoStartTimer;
            
            @Override
            public void run() {
                if (countdown <= 0) {
                    plugin.startGame();
                    cancel();
                    autoStartTask = null;
                    return;
                }
                
                if (countdown <= 10 || countdown % 30 == 0) {
                    plugin.broadcast(ChatColor.GOLD + "Game starting in " + countdown + " seconds!");
                }
                
                countdown--;
            }
        };
        autoStartTask.runTaskTimer(plugin, 0L, 20L);
    }
    
    public void cancelAutoStart() {
        if (autoStartTask != null) {
            autoStartTask.cancel();
            autoStartTask = null;
            plugin.broadcast(ChatColor.RED + "Auto-start cancelled - not enough players!");
        }
    }
    
    public void handleWin(Player winner) {
        if (winner == null || !winner.isOnline()) return;
        
        synchronized (statsLock) {
            try {
                PlayerStats stats = getPlayerStats(winner);
                stats.wins++;
                stats.gamesPlayed++;
                
                // Special effects for the winner
                winner.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 1));
                if (winner.getWorld() != null) {
                    winner.getWorld().strikeLightningEffect(winner.getLocation());
                    winner.getWorld().playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                }
                
                // Fireworks celebration
                new BukkitRunnable() {
                    int count = 0;
                    @Override
                    public void run() {
                        if (count >= 5 || !winner.isOnline()) {
                            cancel();
                            return;
                        }
                        launchFirework(winner.getLocation());
                        count++;
                    }
                }.runTaskTimer(plugin, 0L, 20L);
                
                // Broadcast stats
                plugin.broadcast(ChatColor.GOLD + "======================");
                plugin.broadcast(ChatColor.GOLD + "GAME OVER!");
                plugin.broadcast(ChatColor.GOLD + winner.getName() + " has won the game!");
                plugin.broadcast(ChatColor.GOLD + "Total Wins: " + stats.wins);
                plugin.broadcast(ChatColor.GOLD + "Games Played: " + stats.gamesPlayed);
                plugin.broadcast(ChatColor.GOLD + "======================");
            } catch (Exception e) {
                plugin.getLogger().warning("Error handling win for " + winner.getName() + ": " + e.getMessage());
            }
        }
    }
    
    public void handleDeath(Player player, Player killer) {
        if (player == null || !player.isOnline()) return;
        
        synchronized (statsLock) {
            try {
                PlayerStats deadStats = getPlayerStats(player);
                deadStats.deaths++;
                deadStats.gamesPlayed++;
                
                if (killer != null && killer.isOnline()) {
                    PlayerStats killerStats = getPlayerStats(killer);
                    killerStats.kills++;
                    
                    // Kill rewards
                    killer.setHealth(Math.min(killer.getHealth() + 6.0, killer.getMaxHealth()));
                    killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0));
                    
                    plugin.broadcast(ChatColor.RED + player.getName() + " was eliminated by " + 
                        killer.getName() + " (" + killerStats.kills + " kills)");
                } else {
                    plugin.broadcast(ChatColor.RED + player.getName() + " was eliminated");
                }
                
                // Death effects
                if (player.getWorld() != null) {
                    player.getWorld().strikeLightningEffect(player.getLocation());
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WITHER_DEATH, 1.0f, 1.0f);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error handling death for " + player.getName() + ": " + e.getMessage());
            }
        }
    }
    
    private void launchFirework(Location location) {
        if (location == null || location.getWorld() == null) return;
        
        try {
            World world = location.getWorld();
            world.spawnParticle(Particle.EXPLOSION_HUGE, location, 1);
            world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f);
            
            Firework firework = (Firework) world.spawnEntity(location, EntityType.FIREWORK);
            FireworkMeta meta = firework.getFireworkMeta();
            
            FireworkEffect effect = FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.RED, Color.GOLD)
                .withFade(Color.YELLOW)
                .trail(true)
                .build();
                
            meta.addEffect(effect);
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to launch firework: " + e.getMessage());
        }
    }
    
    public PlayerStats getPlayerStats(Player player) {
        return playerStats.computeIfAbsent(player.getUniqueId(), k -> new PlayerStats());
    }
    
    public void setAutoStartEnabled(boolean enabled) {
        this.autoStartEnabled = enabled;
        if (!enabled) {
            cancelAutoStart();
        }
    }
    
    public boolean isAutoStartEnabled() {
        return autoStartEnabled;
    }
    
    public void setMinPlayersToStart(int count) {
        this.minPlayersToStart = count;
    }
    
    public int getMinPlayersToStart() {
        return minPlayersToStart;
    }
    
    public void savePlayerState(Player player) {
        UUID uuid = player.getUniqueId();
        savedInventories.put(uuid, player.getInventory().getContents());
        savedArmorContents.put(uuid, player.getInventory().getArmorContents());
        savedLocations.put(uuid, player.getLocation());
        
        // Clear player's inventory
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
    }
    
    public void restorePlayerState(Player player) {
        UUID uuid = player.getUniqueId();
        if (savedInventories.containsKey(uuid)) {
            player.getInventory().setContents(savedInventories.get(uuid));
            player.getInventory().setArmorContents(savedArmorContents.get(uuid));
            
            Location loc = savedLocations.get(uuid);
            if (loc != null && loc.getWorld() != null) {
                player.teleport(loc);
            }
            
            savedInventories.remove(uuid);
            savedArmorContents.remove(uuid);
            savedLocations.remove(uuid);
        }
    }
    
    public void clearSavedStates() {
        savedInventories.clear();
        savedArmorContents.clear();
        savedLocations.clear();
    }

    public void handlePlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        if (savedInventories.containsKey(uuid)) {
            // Save to config for crash recovery
            String path = "saved_states." + uuid;
            YamlConfiguration config = new YamlConfiguration();
            
            try {
                config.set(path + ".inventory", savedInventories.get(uuid));
                config.set(path + ".armor", savedArmorContents.get(uuid));
                if (savedLocations.get(uuid) != null) {
                    config.set(path + ".location", savedLocations.get(uuid));
                }
                
                File stateFile = new File(plugin.getDataFolder(), "player_states.yml");
                config.save(stateFile);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save player state for " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    public void loadSavedStates() {
        File stateFile = new File(plugin.getDataFolder(), "player_states.yml");
        if (stateFile.exists()) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(stateFile);
                
                for (String uuidStr : config.getConfigurationSection("saved_states").getKeys(false)) {
                    UUID uuid = UUID.fromString(uuidStr);
                    String path = "saved_states." + uuidStr;
                    
                    savedInventories.put(uuid, (ItemStack[]) config.get(path + ".inventory"));
                    savedArmorContents.put(uuid, (ItemStack[]) config.get(path + ".armor"));
                    if (config.contains(path + ".location")) {
                        savedLocations.put(uuid, (Location) config.get(path + ".location"));
                    }
                }
                
                // Delete the file after loading
                stateFile.delete();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load saved player states: " + e.getMessage());
            }
        }
    }
    
    public static class PlayerStats {
        public int kills = 0;
        public int deaths = 0;
        public int wins = 0;
        public int gamesPlayed = 0;
    }
} 