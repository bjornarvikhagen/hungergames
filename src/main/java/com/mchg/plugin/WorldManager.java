package com.mchg.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.io.File;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class WorldManager {
    private final HungerGames plugin;
    private World gameWorld;
    private final List<ItemStack> possibleItems;
    private final List<Location> spawnPlatforms = new ArrayList<>();
    private static final int SPAWN_PLATFORM_RADIUS = 50; // Distance from center
    private static final int MAX_SPAWN_PLATFORMS = 24;
    
    private final Object worldLock = new Object();
    
    private static final int WORLD_CREATE_TIMEOUT_SECONDS = 30;
    private static final int CHUNK_LOAD_RETRIES = 3;
    private static final int CHUNK_LOAD_RETRY_DELAY_TICKS = 20;
    private static final int MIN_PLATFORM_Y = 45; // Minimum Y level for spawn platforms
    private static final int MAX_PLATFORM_Y = 100; // Maximum Y level for spawn platforms
    
    public WorldManager(HungerGames plugin) {
        this.plugin = plugin;
        this.possibleItems = initializePossibleItems();
    }
    
    public World createNewWorld() {
        synchronized (worldLock) {
            String worldName = "hg_world_" + System.currentTimeMillis();
            WorldCreator creator = new WorldCreator(worldName);
            creator.environment(World.Environment.NORMAL);
            creator.type(WorldType.NORMAL);
            creator.generateStructures(false);  // Disable structures for cleaner arena
            
            try {
                if (gameWorld != null) {
                    deleteGameWorld();
                }
                
                CompletableFuture<World> future = CompletableFuture.supplyAsync(() -> {
                    return creator.createWorld();
                });
                
                try {
                    gameWorld = future.get(WORLD_CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    plugin.getLogger().severe("World creation timed out after " + WORLD_CREATE_TIMEOUT_SECONDS + " seconds");
                    return null;
                } catch (InterruptedException | ExecutionException e) {
                    plugin.getLogger().severe("World creation failed: " + e.getMessage());
                    return null;
                }
                
                if (gameWorld != null) {
                    // Basic world setup
                    gameWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                    gameWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                    gameWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                    gameWorld.setGameRule(GameRule.DO_FIRE_TICK, false);
                    gameWorld.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
                    gameWorld.setGameRule(GameRule.DISABLE_RAIDS, true);
                    gameWorld.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
                    gameWorld.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
                    gameWorld.setDifficulty(Difficulty.NORMAL);
                    gameWorld.setTime(6000);
                    gameWorld.setStorm(false);
                    
                    // Set up world border
                    WorldBorder border = gameWorld.getWorldBorder();
                    border.setCenter(0, 0);
                    border.setSize(500); // Initial size
                    border.setDamageAmount(1.0);
                    border.setDamageBuffer(5.0);
                    border.setWarningDistance(25);
                    border.setWarningTime(15);
                    
                    if (!ensureChunkLoaded(0, 0)) {
                        plugin.getLogger().severe("Failed to load spawn chunk after " + CHUNK_LOAD_RETRIES + " attempts");
                        deleteGameWorld();
                        return null;
                    }
                    
                    generateCornucopia();
                    populateChests();
                    generateSpawnPlatforms();
                    
                    return gameWorld;
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to create game world: " + e.getMessage());
                if (gameWorld != null) {
                    deleteGameWorld();
                }
            }
            return null;
        }
    }
    
    private boolean ensureChunkLoaded(int x, int z) {
        for (int attempt = 0; attempt < CHUNK_LOAD_RETRIES; attempt++) {
            try {
                if (gameWorld.loadChunk(x, z, true)) {
                    return true;
                }
                if (attempt < CHUNK_LOAD_RETRIES - 1) {
                    Thread.sleep(CHUNK_LOAD_RETRY_DELAY_TICKS * 50); // Convert ticks to ms
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Chunk load attempt " + (attempt + 1) + " failed: " + e.getMessage());
            }
        }
        return false;
    }
    
    private boolean isValidSpawnLocation(Location loc) {
        if (loc.getY() < MIN_PLATFORM_Y || loc.getY() > MAX_PLATFORM_Y) {
            return false;
        }
        
        // Check for water/lava
        Block block = loc.getBlock();
        Block below = block.getRelative(BlockFace.DOWN);
        Block above = block.getRelative(BlockFace.UP);
        
        return !block.isLiquid() && 
               !below.isLiquid() && 
               !above.isLiquid() && 
               below.getType().isSolid();
    }
    
    private void generateCornucopia() {
        if (gameWorld == null) return;
        
        // Create central platform
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                Location loc = new Location(gameWorld, x, 64, z);
                if (x*x + z*z <= 100) { // Circular platform
                    loc.getBlock().setType(Material.STONE_BRICKS);
                    // Clear space above
                    for (int y = 65; y <= 70; y++) {
                        new Location(gameWorld, x, y, z).getBlock().setType(Material.AIR);
                    }
                }
            }
        }
        
        // Create cornucopia structure
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 0; y <= 3; y++) {
                    if (x*x + z*z <= 9) {
                        Location loc = new Location(gameWorld, x, 65+y, z);
                        if (y == 0) {
                            loc.getBlock().setType(Material.GOLD_BLOCK);
                        } else if (y == 3) {
                            if (x*x + z*z <= 4) {
                                loc.getBlock().setType(Material.GOLD_BLOCK);
                            }
                        } else {
                            if (x == -3 || x == 3 || z == -3 || z == 3) {
                                loc.getBlock().setType(Material.GOLD_BLOCK);
                            }
                        }
                    }
                }
            }
        }
        
        // Place central chests
        placeChest(new Location(gameWorld, 0, 65, 0));
        placeChest(new Location(gameWorld, 2, 65, 0));
        placeChest(new Location(gameWorld, -2, 65, 0));
        placeChest(new Location(gameWorld, 0, 65, 2));
        placeChest(new Location(gameWorld, 0, 65, -2));
    }
    
    private void populateChests() {
        if (gameWorld == null) return;
        
        // Populate cornucopia chests with high-tier loot
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                Block block = gameWorld.getBlockAt(x, 65, z);
                if (block.getType() == Material.CHEST) {
                    fillChest((Chest) block.getState(), true);
                }
            }
        }
        
        // Generate and populate random chests around the map
        Random random = new Random();
        for (int i = 0; i < 20; i++) {
            int x = random.nextInt(201) - 100;
            int z = random.nextInt(201) - 100;
            int y = gameWorld.getHighestBlockYAt(x, z);
            
            Location chestLoc = new Location(gameWorld, x, y + 1, z);
            placeChest(chestLoc);
            Block block = chestLoc.getBlock();
            if (block.getType() == Material.CHEST) {
                fillChest((Chest) block.getState(), false);
            }
        }
    }
    
    private void placeChest(Location location) {
        location.getBlock().setType(Material.CHEST);
    }
    
    private void fillChest(Chest chest, boolean isHighTier) {
        Random random = new Random();
        int itemCount = isHighTier ? 
            random.nextInt(5) + 3 : // 3-7 items for high-tier
            random.nextInt(4) + 1;  // 1-4 items for normal chests
        
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(i);
        Collections.shuffle(slots);
        
        for (int i = 0; i < itemCount; i++) {
            ItemStack item = getRandomItem(isHighTier);
            if (item != null) {
                chest.getInventory().setItem(slots.get(i), item);
            }
        }
        chest.update();
    }
    
    private List<ItemStack> initializePossibleItems() {
        List<ItemStack> items = new ArrayList<>();
        
        // Weapons
        items.add(new ItemStack(Material.WOODEN_SWORD));
        items.add(new ItemStack(Material.STONE_SWORD));
        items.add(new ItemStack(Material.IRON_SWORD));
        items.add(new ItemStack(Material.DIAMOND_SWORD));
        items.add(new ItemStack(Material.BOW));
        items.add(new ItemStack(Material.ARROW, 16));
        
        // Armor
        items.add(new ItemStack(Material.LEATHER_HELMET));
        items.add(new ItemStack(Material.LEATHER_CHESTPLATE));
        items.add(new ItemStack(Material.LEATHER_LEGGINGS));
        items.add(new ItemStack(Material.LEATHER_BOOTS));
        items.add(new ItemStack(Material.CHAINMAIL_HELMET));
        items.add(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        items.add(new ItemStack(Material.CHAINMAIL_LEGGINGS));
        items.add(new ItemStack(Material.CHAINMAIL_BOOTS));
        items.add(new ItemStack(Material.IRON_HELMET));
        items.add(new ItemStack(Material.IRON_CHESTPLATE));
        items.add(new ItemStack(Material.IRON_LEGGINGS));
        items.add(new ItemStack(Material.IRON_BOOTS));
        
        // Food
        items.add(new ItemStack(Material.BREAD, 3));
        items.add(new ItemStack(Material.COOKED_BEEF, 2));
        items.add(new ItemStack(Material.APPLE, 3));
        items.add(new ItemStack(Material.GOLDEN_APPLE));
        
        // Utilities
        items.add(new ItemStack(Material.FISHING_ROD));
        items.add(new ItemStack(Material.FLINT_AND_STEEL));
        items.add(new ItemStack(Material.COMPASS));
        items.add(new ItemStack(Material.SHIELD));
        
        return items;
    }
    
    private ItemStack getRandomItem(boolean isHighTier) {
        Random random = new Random();
        ItemStack item = possibleItems.get(random.nextInt(possibleItems.size()));
        
        if (isHighTier) {
            // Higher chance of better items
            if (item.getType().name().contains("WOODEN") || 
                item.getType().name().contains("LEATHER")) {
                return getRandomItem(true); // Reroll for better item
            }
        }
        
        return item.clone(); // Return a clone to avoid modifying the original
    }
    
    public World getGameWorld() {
        return gameWorld;
    }
    
    public void deleteGameWorld() {
        synchronized (worldLock) {
            if (gameWorld != null) {
                try {
                    String worldName = gameWorld.getName();
                    World defaultWorld = Bukkit.getWorlds().get(0);
                    
                    // Teleport all players out
                    for (Player player : gameWorld.getPlayers()) {
                        if (player != null && player.isOnline()) {
                            player.teleport(defaultWorld.getSpawnLocation());
                        }
                    }
                    
                    // Remove all entities and force unload chunks
                    gameWorld.getEntities().stream()
                        .filter(entity -> !(entity instanceof Player))
                        .forEach(Entity::remove);
                    
                    for (Chunk chunk : gameWorld.getLoadedChunks()) {
                        chunk.unload(true);
                    }
                    
                    // Unload and delete world
                    if (Bukkit.unloadWorld(gameWorld, false)) {
                        gameWorld = null;
                        
                        // Delete world folder with retries
                        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
                        int maxRetries = 5;
                        for (int i = 0; i < maxRetries; i++) {
                            try {
                                if (worldFolder.exists()) {
                                    FileUtils.deleteDirectory(worldFolder);
                                }
                                if (!worldFolder.exists()) {
                                    break;
                                }
                                if (i < maxRetries - 1) {
                                    Thread.sleep(1000);
                                }
                            } catch (Exception e) {
                                if (i == maxRetries - 1) {
                                    plugin.getLogger().severe("Failed to delete world folder after " + maxRetries + " attempts: " + e.getMessage());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Error during world deletion: " + e.getMessage());
                }
            }
        }
    }
    
    private void generateSpawnPlatforms() {
        if (gameWorld == null) return;
        spawnPlatforms.clear();
        
        try {
            double angleStep = 2 * Math.PI / MAX_SPAWN_PLATFORMS;
            
            for (int i = 0; i < MAX_SPAWN_PLATFORMS; i++) {
                double angle = angleStep * i;
                int x = (int) (Math.cos(angle) * SPAWN_PLATFORM_RADIUS);
                int z = (int) (Math.sin(angle) * SPAWN_PLATFORM_RADIUS);
                
                // Ensure chunk is loaded
                gameWorld.getChunkAt(x >> 4, z >> 4).load(true);
                
                // Find surface height
                int y = gameWorld.getHighestBlockYAt(x, z);
                
                // Create platform
                createSpawnPlatform(x, y, z, angle);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to generate spawn platforms: " + e.getMessage());
        }
    }
    
    private void createSpawnPlatform(int x, int y, int z, double angle) {
        if (gameWorld == null) return;
        
        try {
            // Find safe Y level
            int safeY = y;
            if (!isValidSpawnLocation(new Location(gameWorld, x, y, z))) {
                // Search up and down for a safe location
                for (int offset = 1; offset < 20; offset++) {
                    // Check above
                    if (isValidSpawnLocation(new Location(gameWorld, x, y + offset, z))) {
                        safeY = y + offset;
                        break;
                    }
                    // Check below
                    if (isValidSpawnLocation(new Location(gameWorld, x, y - offset, z))) {
                        safeY = y - offset;
                        break;
                    }
                }
            }
            
            // Create a 3x3 platform
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Location loc = new Location(gameWorld, x + dx, safeY, z + dz);
                    // Base platform
                    loc.getBlock().setType(Material.SMOOTH_STONE);
                    // Glass walls
                    loc.clone().add(0, 1, 0).getBlock().setType(Material.GLASS);
                    loc.clone().add(0, 2, 0).getBlock().setType(Material.GLASS);
                    // Clear above
                    loc.clone().add(0, 3, 0).getBlock().setType(Material.AIR);
                    loc.clone().add(0, 4, 0).getBlock().setType(Material.AIR);
                }
            }
            
            // Store center location with rotation facing cornucopia
            Location spawnLoc = new Location(gameWorld, x, safeY + 1, z, 
                (float)(angle * 180/Math.PI + 90), 0);
            spawnPlatforms.add(spawnLoc);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create spawn platform at " + x + "," + y + "," + z + ": " + e.getMessage());
        }
    }
    
    public List<Location> getSpawnLocations(int playerCount) {
        if (spawnPlatforms.isEmpty() || playerCount == 0 || gameWorld == null) {
            return new ArrayList<>();
        }
        
        try {
            // If we have exactly the right number of platforms, use them all
            if (spawnPlatforms.size() == playerCount) {
                return new ArrayList<>(spawnPlatforms);
            }
            
            // Otherwise, evenly distribute players among available platforms
            List<Location> selectedPlatforms = new ArrayList<>();
            int step = Math.max(1, MAX_SPAWN_PLATFORMS / playerCount);
            
            for (int i = 0; i < playerCount && i * step < spawnPlatforms.size(); i++) {
                selectedPlatforms.add(spawnPlatforms.get(i * step));
            }
            
            return selectedPlatforms;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to get spawn locations: " + e.getMessage());
            return new ArrayList<>();
        }
    }
} 