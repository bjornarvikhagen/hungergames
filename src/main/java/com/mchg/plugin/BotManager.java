package com.mchg.plugin;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.inventory.ItemStack;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.v1_20_R1.CraftServer;
import org.bukkit.craftbukkit.v1_20_R1.CraftWorld;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BotManager {
    private final HungerGames plugin;
    private final Map<UUID, BotPlayer> bots;
    private final Random random;
    private BukkitRunnable updateTask;
    
    private static final String[] BOT_NAMES = {
        "Bot_Alpha", "Bot_Beta", "Bot_Charlie", "Bot_Delta", "Bot_Echo",
        "Bot_Foxtrot", "Bot_Golf", "Bot_Hotel", "Bot_India", "Bot_Juliet"
    };
    
    public BotManager(HungerGames plugin) {
        this.plugin = plugin;
        this.bots = new ConcurrentHashMap<>();
        this.random = new Random();
    }
    
    public void addBot() {
        String name = BOT_NAMES[bots.size() % BOT_NAMES.length];
        UUID uuid = UUID.randomUUID();
        
        // Create NMS bot player
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) plugin.getGameWorld()).getHandle();
        GameProfile profile = new GameProfile(uuid, name);
        
        // Add random skin
        addRandomSkin(profile);
        
        ServerPlayer nmsPlayer = new ServerPlayer(server, level, profile);
        Player botPlayer = nmsPlayer.getBukkitEntity();
        
        // Set initial location
        Location spawn = plugin.getGameWorld().getSpawnLocation();
        botPlayer.teleport(spawn);
        
        // Create bot instance
        BotPlayer bot = new BotPlayer(botPlayer);
        bots.put(uuid, bot);
        
        // Add to game
        plugin.addPlayer(botPlayer);
        
        // Broadcast join message
        plugin.broadcast(ChatColor.YELLOW + name + " joined the game!");
    }
    
    private void addRandomSkin(GameProfile profile) {
        // Add a random steve/alex skin variant
        String[] textures = {
            "ewogICJ0aW1lc3RhbXAiIDogMTYxNzMyMjM3NjU4MywKICAicHJvZmlsZUlkIiA6ICIxNzU2NDNhNjUwNDg0YTQyOGI1ZTJiNjA4MzliZjNiMiIsCiAgInByb2ZpbGVOYW1lIiA6ICJTdGV2ZSIsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8xYTRhZjcxODQ1NWQ0YWFiNTI4ZTdhNjFmODZmYTI1ZTZhMzY5ZDE3NjhkY2IxM2Y3ZGYzMTlhNzEzZWI4MTBiIgogICAgfQogIH0KfQ==",
            "ewogICJ0aW1lc3RhbXAiIDogMTYxNzMyMjM3NjU4MywKICAicHJvZmlsZUlkIiA6ICIxNzU2NDNhNjUwNDg0YTQyOGI1ZTJiNjA4MzliZjNiMiIsCiAgInByb2ZpbGVOYW1lIiA6ICJBbGV4IiwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzgzY2VlMjQyMmQyMjZiMWRkMmY2MzY4OTNiYWM3MWE5YTM4N2JmNzljMzgyY2VhZjY3YTQ5OWQ3MzJ"
        };
        
        String texture = textures[random.nextInt(textures.length)];
        profile.getProperties().put("textures", new Property("textures", texture));
    }
    
    public void removeBot(UUID uuid) {
        BotPlayer bot = bots.remove(uuid);
        if (bot != null) {
            plugin.removePlayer(bot.getPlayer());
        }
    }
    
    public void startBotAI() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.getGameState() != GameState.ACTIVE) return;
                
                for (BotPlayer bot : bots.values()) {
                    try {
                        updateBot(bot);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error updating bot " + bot.getPlayer().getName() + ": " + e.getMessage());
                    }
                }
            }
        };
        
        updateTask.runTaskTimer(plugin, 0L, 5L); // Update every 1/4 second
    }
    
    private void updateBot(BotPlayer bot) {
        Player player = bot.getPlayer();
        if (!player.isOnline() || player.isDead()) return;
        
        // Update state machine
        switch (bot.getState()) {
            case LOOTING:
                updateLooting(bot);
                break;
            case FIGHTING:
                updateFighting(bot);
                break;
            case EXPLORING:
                updateExploring(bot);
                break;
        }
        
        // Check for nearby players and switch to combat if needed
        Player nearestPlayer = findNearestPlayer(player);
        if (nearestPlayer != null && player.getLocation().distance(nearestPlayer.getLocation()) < 10) {
            bot.setState(BotState.FIGHTING);
            bot.setTarget(nearestPlayer);
        }
    }
    
    private void updateLooting(BotPlayer bot) {
        Player player = bot.getPlayer();
        
        // Simple looting behavior - look for nearby chests
        Location target = findNearestChest(player);
        if (target != null) {
            moveTowards(player, target);
        } else {
            bot.setState(BotState.EXPLORING);
        }
    }
    
    private void updateFighting(BotPlayer bot) {
        Player player = bot.getPlayer();
        Player target = bot.getTarget();
        
        if (target == null || !target.isOnline() || target.isDead() || 
            plugin.getPlayerStates().get(target) == PlayerState.DEAD) {
            bot.setState(BotState.EXPLORING);
            return;
        }
        
        // Combat behavior
        double distance = player.getLocation().distance(target.getLocation());
        
        // Switch to best weapon
        selectBestWeapon(player);
        
        if (distance > 15) {
            // Too far, go back to exploring
            bot.setState(BotState.EXPLORING);
        } else if (distance > 3) {
            // Chase target
            moveTowards(player, target.getLocation());
        } else {
            // Attack
            player.attack(target);
        }
    }
    
    private void updateExploring(BotPlayer bot) {
        Player player = bot.getPlayer();
        Location target = bot.getExploreTarget();
        
        if (target == null || player.getLocation().distance(target) < 2) {
            // Pick new random location within border
            WorldBorder border = plugin.getGameWorld().getWorldBorder();
            double size = border.getSize() / 2;
            double x = random.nextDouble() * size * 2 - size;
            double z = random.nextDouble() * size * 2 - size;
            target = new Location(plugin.getGameWorld(), x, 
                plugin.getGameWorld().getHighestBlockYAt((int)x, (int)z), z);
            bot.setExploreTarget(target);
        }
        
        moveTowards(player, target);
        
        // Occasionally switch to looting
        if (random.nextInt(100) < 5) {
            bot.setState(BotState.LOOTING);
        }
    }
    
    private Player findNearestPlayer(Player bot) {
        Player nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (Player player : plugin.getAlivePlayers()) {
            if (player.equals(bot) || plugin.getPlayerStates().get(player) != PlayerState.ALIVE) continue;
            
            double distance = bot.getLocation().distance(player.getLocation());
            if (distance < minDistance) {
                minDistance = distance;
                nearest = player;
            }
        }
        
        return nearest;
    }
    
    private Location findNearestChest(Player player) {
        Location nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (int x = -10; x <= 10; x++) {
            for (int y = -10; y <= 10; y++) {
                for (int z = -10; z <= 10; z++) {
                    Location loc = player.getLocation().add(x, y, z);
                    if (loc.getBlock().getType() == Material.CHEST) {
                        double distance = player.getLocation().distance(loc);
                        if (distance < minDistance) {
                            minDistance = distance;
                            nearest = loc;
                        }
                    }
                }
            }
        }
        
        return nearest;
    }
    
    private void moveTowards(Player player, Location target) {
        Location loc = player.getLocation();
        Vector direction = target.toVector().subtract(loc.toVector()).normalize();
        
        // Look at target
        loc.setDirection(direction);
        player.teleport(loc);
        
        // Move towards target
        player.setVelocity(direction.multiply(0.4));
    }
    
    private void selectBestWeapon(Player player) {
        ItemStack bestWeapon = null;
        double bestDamage = 0;
        
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            
            double damage = getWeaponDamage(item);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestWeapon = item;
            }
        }
        
        if (bestWeapon != null) {
            player.getInventory().setItemInMainHand(bestWeapon);
        }
    }
    
    private double getWeaponDamage(ItemStack item) {
        switch (item.getType()) {
            case DIAMOND_SWORD: return 7;
            case IRON_SWORD: return 6;
            case STONE_SWORD: return 5;
            case WOODEN_SWORD: return 4;
            case BOW: return 6;
            default: return 1;
        }
    }
    
    public void stopBotAI() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }
    
    public void clearBots() {
        for (UUID uuid : new ArrayList<>(bots.keySet())) {
            removeBot(uuid);
        }
        bots.clear();
    }
    
    private static class BotPlayer {
        private final Player player;
        private BotState state;
        private Player target;
        private Location exploreTarget;
        
        public BotPlayer(Player player) {
            this.player = player;
            this.state = BotState.EXPLORING;
        }
        
        public Player getPlayer() {
            return player;
        }
        
        public BotState getState() {
            return state;
        }
        
        public void setState(BotState state) {
            this.state = state;
        }
        
        public Player getTarget() {
            return target;
        }
        
        public void setTarget(Player target) {
            this.target = target;
        }
        
        public Location getExploreTarget() {
            return exploreTarget;
        }
        
        public void setExploreTarget(Location target) {
            this.exploreTarget = target;
        }
    }
    
    private enum BotState {
        LOOTING,
        FIGHTING,
        EXPLORING
    }
} 