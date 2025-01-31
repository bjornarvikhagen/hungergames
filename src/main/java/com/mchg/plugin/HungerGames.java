package com.mchg.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.GameMode;
import org.bukkit.WorldBorder;
import org.bukkit.potion.PotionEffect;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.io.File;

public class HungerGames extends JavaPlugin {
    private GameState gameState = GameState.WAITING;
    private List<Player> players = new ArrayList<>();
    private Map<Player, PlayerState> playerStates = new HashMap<>();
    private Location spawnLocation;
    private int countdownSeconds = 30;
    private int gracePeriodSeconds = 30;
    private int borderShrinkMinutes = 10;
    private WorldManager worldManager;
    private GameManager gameManager;
    private LootManager lootManager;
    private SpectatorManager spectatorManager;
    private BotManager botManager;
    
    private final Object playerLock = new Object();
    private final Object gameStateLock = new Object();
    
    private static final int TELEPORT_RETRY_COUNT = 3;
    private static final int TELEPORT_RETRY_DELAY_TICKS = 10;
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        worldManager = new WorldManager(this);
        gameManager = new GameManager(this);
        lootManager = new LootManager(this);
        spectatorManager = new SpectatorManager(this);
        botManager = new BotManager(this);
        setupSpawnLocation();
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getCommand("hg").setExecutor(new GameCommand(this));
        
        // Load any saved states
        gameManager.loadSavedStates();
        
        // Recover player states
        for (Player player : getServer().getOnlinePlayers()) {
            if (playerStates.containsKey(player)) {
                recoverPlayerState(player);
            }
        }
        
        // Schedule periodic scoreboard updates
        new BukkitRunnable() {
            @Override
            public void run() {
                if (gameState != GameState.WAITING) {
                    gameManager.updateScoreboard();
                }
            }
        }.runTaskTimer(this, 20L, 20L);
        
        getLogger().info("HungerGames plugin has been enabled!");
    }
    
    @Override
    public void onDisable() {
        // Save states of any players in game
        for (Player player : players) {
            if (player.isOnline()) {
                gameManager.handlePlayerQuit(player);
            }
        }
        endGame();
        if (worldManager != null) {
            worldManager.deleteGameWorld();
        }
        if (spectatorManager != null) {
            spectatorManager.clearSpectatorData();
        }
        if (botManager != null) {
            botManager.clearBots();
        }
        getLogger().info("HungerGames plugin has been disabled!");
    }
    
    private void setupSpawnLocation() {
        World world = Bukkit.getWorlds().get(0);  // Use default world instead of creating new one
        spawnLocation = new Location(world, 0, world.getHighestBlockYAt(0, 0) + 1, 0);
    }
    
    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        countdownSeconds = getConfig().getInt("game.countdown-seconds", 30);
        gracePeriodSeconds = getConfig().getInt("game.grace-period-seconds", 30);
        borderShrinkMinutes = getConfig().getInt("game.border-shrink-minutes", 10);
        gameManager.setMinPlayersToStart(getConfig().getInt("game.min-players", 2));
        gameManager.setMaxPlayersPerGame(getConfig().getInt("game.max-players", 24));
        
        // Load saved game state if exists
        File stateFile = new File(getDataFolder(), "gamestate.dat");
        if (stateFile.exists()) {
            try {
                YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile);
                if (state.getBoolean("game-in-progress", false)) {
                    getLogger().info("Detected interrupted game, cleaning up...");
                    endGame();
                }
            } catch (Exception e) {
                getLogger().warning("Failed to load game state: " + e.getMessage());
            }
        }
    }
    
    // Add grace period tracking
    private long graceEndTime = 0;
    
    public boolean isGracePeriod() {
        return gameState == GameState.ACTIVE && System.currentTimeMillis() < graceEndTime;
    }
    
    private boolean safeTeleport(Player player, Location location) {
        for (int attempt = 0; attempt < TELEPORT_RETRY_COUNT; attempt++) {
            try {
                if (player.teleport(location)) {
                    return true;
                }
                if (attempt < TELEPORT_RETRY_COUNT - 1) {
                    Thread.sleep(TELEPORT_RETRY_DELAY_TICKS * 50);
                }
            } catch (Exception e) {
                getLogger().warning("Teleport attempt " + (attempt + 1) + " failed for " + player.getName() + ": " + e.getMessage());
            }
        }
        return false;
    }
    
    private void recoverPlayerState(Player player) {
        try {
            if (!player.isOnline()) return;
            
            PlayerState state = playerStates.get(player);
            if (state == null) {
                removePlayer(player);
                return;
            }
            
            World gameWorld = worldManager.getGameWorld();
            if (gameWorld == null || gameState == GameState.WAITING) {
                player.teleport(getServer().getWorlds().get(0).getSpawnLocation());
                player.setGameMode(GameMode.ADVENTURE);
                removePlayer(player);
                return;
            }
            
            if (state == PlayerState.DEAD) {
                player.setGameMode(GameMode.SPECTATOR);
            } else if (gameState == GameState.STARTING) {
                player.setGameMode(GameMode.ADVENTURE);
            } else if (gameState == GameState.ACTIVE) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            
            if (!player.getWorld().equals(gameWorld)) {
                safeTeleport(player, gameWorld.getSpawnLocation());
            }
        } catch (Exception e) {
            getLogger().warning("Failed to recover state for " + player.getName() + ": " + e.getMessage());
            removePlayer(player);
        }
    }
    
    public void startGame() {
        synchronized (gameStateLock) {
            if (gameState != GameState.WAITING) {
                return;
            }
            
            try {
                gameState = GameState.STARTING;
                saveGameState();
                
                synchronized (playerLock) {
                    if (players.size() < gameManager.getMinPlayersToStart()) {
                        broadcast(ChatColor.RED + "Not enough players to start the game!");
                        return;
                    }
                }
                
                broadcast(ChatColor.GOLD + "Preparing game world...");
                
                // Create and prepare world asynchronously
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            // Create new world for the game
                            World gameWorld = worldManager.createNewWorld();
                            if (gameWorld == null) {
                                broadcast(ChatColor.RED + "Failed to create game world!");
                                endGame();
                                return;
                            }
                            
                            // Pre-load spawn area
                            broadcast(ChatColor.GOLD + "Generating spawn area...");
                            int radius = 200; // Adjust based on border size
                            int chunksLoaded = 0;
                            int totalChunks = (radius * 2 / 16) * (radius * 2 / 16);
                            
                            for (int x = -radius; x <= radius; x += 16) {
                                for (int z = -radius; z <= radius; z += 16) {
                                    if (!gameWorld.loadChunk(x >> 4, z >> 4, true)) {
                                        continue;
                                    }
                                    chunksLoaded++;
                                    if (chunksLoaded % 25 == 0) {
                                        int percentage = (chunksLoaded * 100) / totalChunks;
                                        broadcast(ChatColor.GOLD + "Loading world: " + percentage + "%");
                                    }
                                }
                            }
                            
                            // Switch back to main thread for game setup
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    startGameSetup(gameWorld);
                                }
                            }.runTask(plugin);
                            
                        } catch (Exception e) {
                            broadcast(ChatColor.RED + "Error preparing game world!");
                            getLogger().severe("Error in world preparation: " + e.getMessage());
                            endGame();
                        }
                    }
                }.runTaskAsynchronously(this);
                
            } catch (Exception e) {
                getLogger().severe("Error starting game: " + e.getMessage());
                endGame();
            }
        }
    }
    
    private void startGameSetup(World gameWorld) {
        try {
            broadcast(ChatColor.GOLD + "World ready! Game starting in " + countdownSeconds + " seconds!");
            
            // Start bot AI
            botManager.startBotAI();
            
            spawnLocation = new Location(gameWorld, 0, 66, 0);
            
            // Setup world border with dynamic sizing based on player count
            WorldBorder border = gameWorld.getWorldBorder();
            border.setCenter(0, 0);
            int playerCount = players.size();
            int borderSize = Math.max(200, Math.min(400, playerCount * 50));
            border.setSize(borderSize);
            border.setDamageAmount(getConfig().getDouble("world.border.damage", 1.0));
            border.setDamageBuffer(0.0);
            border.setWarningDistance(10);
            
            // Get spawn locations and distribute players
            List<Location> spawnLocations = worldManager.getSpawnLocations(players.size());
            if (spawnLocations.isEmpty()) {
                broadcast(ChatColor.RED + "Error: Could not generate spawn platforms!");
                endGame();
                return;
            }
            
            // Teleport and prepare all players with retry
            List<Player> failedTeleports = new ArrayList<>();
            int index = 0;
            List<Player> activePlayers = new ArrayList<>(players);
            
            for (Player player : activePlayers) {
                if (!player.isOnline()) {
                    removePlayer(player);
                    continue;
                }
                
                Location spawnLoc = spawnLocations.get(index++ % spawnLocations.size());
                if (!safeTeleport(player, spawnLoc)) {
                    failedTeleports.add(player);
                    continue;
                }
                
                player.setGameMode(GameMode.ADVENTURE);
                player.setHealth(20.0);
                player.setFoodLevel(20);
                player.getInventory().clear();
                player.setExp(0.0f);
                player.setLevel(0);
                
                for (PotionEffect effect : player.getActivePotionEffects()) {
                    player.removePotionEffect(effect.getType());
                }
            }
            
            // Handle failed teleports
            for (Player player : failedTeleports) {
                removePlayer(player);
                player.sendMessage(ChatColor.RED + "Failed to teleport you to the game world!");
            }
            
            // Check if we still have enough players
            if (players.size() < gameManager.getMinPlayersToStart()) {
                broadcast(ChatColor.RED + "Not enough players remaining to start the game!");
                endGame();
                return;
            }
            
            new BukkitRunnable() {
                int countdown = countdownSeconds;
                
                @Override
                public void run() {
                    if (gameState != GameState.STARTING) {
                        cancel();
                        return;
                    }
                    
                    if (players.size() < gameManager.getMinPlayersToStart()) {
                        broadcast(ChatColor.RED + "Not enough players remaining!");
                        endGame();
                        cancel();
                        return;
                    }
                    
                    if (countdown <= 0) {
                        startMainGame();
                        cancel();
                        return;
                    }
                    
                    if (countdown <= 5 || countdown % 10 == 0) {
                        broadcast(ChatColor.GOLD + "Game starts in " + countdown + " seconds!");
                        for (Player player : players) {
                            if (player.isOnline()) {
                                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                            }
                        }
                    }
                    
                    countdown--;
                }
            }.runTaskTimer(this, 0L, 20L);
        } catch (Exception e) {
            getLogger().severe("Error in game setup: " + e.getMessage());
            endGame();
        }
    }
    
    private void startMainGame() {
        gameState = GameState.ACTIVE;
        graceEndTime = System.currentTimeMillis() + (gracePeriodSeconds * 1000);
        broadcast(ChatColor.GREEN + "The game has begun! Grace period: " + gracePeriodSeconds + " seconds!");
        
        // Simply change gamemode for all players
        for (Player player : players) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        
        // Start grace period
        new BukkitRunnable() {
            @Override
            public void run() {
                if (gameState != GameState.ACTIVE) return;
                
                broadcast(ChatColor.RED + "Grace period has ended! PvP is now enabled!");
                for (Player player : players) {
                    if (player.isOnline()) {
                        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);
                    }
                }
                startBorderShrink();
            }
        }.runTaskLater(this, gracePeriodSeconds * 20L);
    }
    
    private void startBorderShrink() {
        World world = worldManager.getGameWorld();
        if (world != null) {
            WorldBorder border = world.getWorldBorder();
            int playerCount = getAlivePlayers().size();
            
            // Dynamic border shrink based on player count
            int startSize = border.getSize();
            int endSize = Math.max(50, Math.min(100, playerCount * 25)); // 25 blocks per player, min 50, max 100
            int shrinkTime = Math.max(300, Math.min(900, playerCount * 60)); // 1-15 minutes based on player count
            
            border.setSize(endSize, shrinkTime);
            
            broadcast(ChatColor.RED + "The border has started shrinking!");
            for (Player player : players) {
                player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 1.0f, 1.0f);
            }
            
            // Schedule periodic warnings
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (gameState != GameState.ACTIVE) {
                        cancel();
                        return;
                    }
                    
                    double size = border.getSize();
                    if (size <= endSize) {
                        cancel();
                        return;
                    }
                    
                    broadcast(ChatColor.YELLOW + "Border size: " + (int)size + " blocks");
                }
            }.runTaskTimer(this, 1200L, 1200L); // Every minute
        }
    }
    
    public void eliminatePlayer(Player player) {
        if (playerStates.containsKey(player)) {
            playerStates.put(player, PlayerState.DEAD);
            spectatorManager.setSpectator(player);
            
            Player killer = player.getKiller();
            gameManager.handleDeath(player, killer);
            
            checkWinner();
        }
    }
    
    private void checkWinner() {
        List<Player> alivePlayers = getAlivePlayers();
        if (alivePlayers.size() == 1) {
            Player winner = alivePlayers.get(0);
            gameManager.handleWin(winner);
            endGame();
        }
    }
    
    public void endGame() {
        synchronized (gameStateLock) {
            if (gameState == GameState.WAITING) {
                return;
            }
            
            try {
                gameState = GameState.WAITING;
                saveGameState();
                
                // Stop bot AI
                botManager.stopBotAI();
                
                List<Player> gamePlayers = new ArrayList<>(players);
                for (Player player : gamePlayers) {
                    if (player.isOnline()) {
                        gameManager.restorePlayerState(player);
                    } else {
                        gameManager.handlePlayerQuit(player);
                    }
                }
                
                players.clear();
                playerStates.clear();
                graceEndTime = 0;
                gameManager.clearSavedStates();
                
                if (worldManager != null) {
                    worldManager.deleteGameWorld();
                }
                
                broadcast(ChatColor.GOLD + "Game has ended! Use /hg join to play again!");
            } catch (Exception e) {
                getLogger().severe("Error ending game: " + e.getMessage());
            } finally {
                // Ensure cleanup happens even if there's an error
                players.clear();
                playerStates.clear();
                graceEndTime = 0;
                gameManager.clearSavedStates();
                botManager.clearBots();
                if (worldManager != null) {
                    worldManager.deleteGameWorld();
                }
            }
        }
    }
    
    private void saveGameState() {
        try {
            File stateFile = new File(getDataFolder(), "gamestate.dat");
            YamlConfiguration state = new YamlConfiguration();
            state.set("game-in-progress", gameState != GameState.WAITING);
            state.set("game-state", gameState.name());
            state.set("grace-end-time", graceEndTime);
            state.save(stateFile);
        } catch (Exception e) {
            getLogger().warning("Failed to save game state: " + e.getMessage());
        }
    }
    
    public void addPlayer(Player player) {
        if (player == null || !player.isOnline()) return;
        
        synchronized (playerLock) {
            if (gameState != GameState.WAITING) {
                player.sendMessage(ChatColor.RED + "Cannot join while game is in progress!");
                return;
            }
            
            if (!players.contains(player)) {
                gameManager.savePlayerState(player);
                players.add(player);
                playerStates.put(player, PlayerState.ALIVE);
                player.sendMessage(ChatColor.GREEN + "You have joined the game!");
                broadcast(ChatColor.YELLOW + player.getName() + " has joined! (" + players.size() + " players)");
                gameManager.checkAutoStart();
            }
        }
    }
    
    public void removePlayer(Player player) {
        if (player == null) return;
        
        synchronized (playerLock) {
            if (players.remove(player)) {
                playerStates.remove(player);
                if (player.isOnline()) {
                    gameManager.restorePlayerState(player);
                    player.sendMessage(ChatColor.GREEN + "You have left the game!");
                } else {
                    gameManager.handlePlayerQuit(player);
                }
                broadcast(ChatColor.YELLOW + player.getName() + " has left! (" + players.size() + " players)");
                
                if (gameState == GameState.ACTIVE) {
                    checkWinner();
                } else if (gameState == GameState.WAITING) {
                    gameManager.checkAutoStart();
                }
            }
        }
    }
    
    public List<Player> getAlivePlayers() {
        List<Player> alivePlayers = new ArrayList<>();
        for (Map.Entry<Player, PlayerState> entry : playerStates.entrySet()) {
            if (entry.getValue() == PlayerState.ALIVE) {
                alivePlayers.add(entry.getKey());
            }
        }
        return alivePlayers;
    }
    
    public void broadcast(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }
    
    public GameState getGameState() {
        return gameState;
    }
    
    public List<Player> getPlayers() {
        return players;
    }
    
    public Map<Player, PlayerState> getPlayerStates() {
        return playerStates;
    }
    
    public World getGameWorld() {
        return worldManager.getGameWorld();
    }
    
    public GameManager getGameManager() {
        return gameManager;
    }
    
    public LootManager getLootManager() {
        return lootManager;
    }
    
    public SpectatorManager getSpectatorManager() {
        return spectatorManager;
    }
    
    public BotManager getBotManager() {
        return botManager;
    }
}

enum GameState {
    WAITING,
    STARTING,
    ACTIVE
}

enum PlayerState {
    ALIVE,
    DEAD
} 