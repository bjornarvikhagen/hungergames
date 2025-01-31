package com.mchg.plugin;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.util.*;

public class LootManager {
    private final HungerGames plugin;
    private final Map<String, List<WeightedItem>> lootTables;
    private final Random random;
    
    public LootManager(HungerGames plugin) {
        this.plugin = plugin;
        this.lootTables = new HashMap<>();
        this.random = new Random();
        loadLootTables();
    }
    
    private void loadLootTables() {
        // Create default loot tables if they don't exist
        File lootFile = new File(plugin.getDataFolder(), "loot.yml");
        if (!lootFile.exists()) {
            plugin.saveResource("loot.yml", false);
        }
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(lootFile);
        
        // Load each loot table
        for (String tableName : config.getKeys(false)) {
            ConfigurationSection tableSection = config.getConfigurationSection(tableName);
            if (tableSection == null) continue;
            
            List<WeightedItem> items = new ArrayList<>();
            
            for (String itemKey : tableSection.getKeys(false)) {
                ConfigurationSection itemSection = tableSection.getConfigurationSection(itemKey);
                if (itemSection == null) continue;
                
                try {
                    Material material = Material.valueOf(itemSection.getString("material", ""));
                    int weight = itemSection.getInt("weight", 1);
                    int minAmount = itemSection.getInt("min-amount", 1);
                    int maxAmount = itemSection.getInt("max-amount", 1);
                    
                    ItemStack item = new ItemStack(material);
                    
                    // Handle enchantments
                    if (itemSection.contains("enchantments")) {
                        ConfigurationSection enchants = itemSection.getConfigurationSection("enchantments");
                        if (enchants != null) {
                            for (String enchantName : enchants.getKeys(false)) {
                                Enchantment enchant = Enchantment.getByName(enchantName);
                                if (enchant != null) {
                                    item.addEnchantment(enchant, enchants.getInt(enchantName));
                                }
                            }
                        }
                    }
                    
                    // Handle potions
                    if (material == Material.POTION || material == Material.SPLASH_POTION) {
                        String potionType = itemSection.getString("potion-type");
                        if (potionType != null) {
                            PotionMeta meta = (PotionMeta) item.getItemMeta();
                            if (meta != null) {
                                meta.setBasePotionData(new PotionData(PotionType.valueOf(potionType)));
                                item.setItemMeta(meta);
                            }
                        }
                    }
                    
                    items.add(new WeightedItem(item, weight, minAmount, maxAmount));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load item " + itemKey + " in table " + tableName + ": " + e.getMessage());
                }
            }
            
            lootTables.put(tableName, items);
        }
    }
    
    public ItemStack getRandomItem(String tableName) {
        List<WeightedItem> items = lootTables.get(tableName);
        if (items == null || items.isEmpty()) return null;
        
        int totalWeight = items.stream().mapToInt(item -> item.weight).sum();
        int randomWeight = random.nextInt(totalWeight);
        
        int currentWeight = 0;
        for (WeightedItem item : items) {
            currentWeight += item.weight;
            if (randomWeight < currentWeight) {
                ItemStack result = item.item.clone();
                int amount = random.nextInt(item.maxAmount - item.minAmount + 1) + item.minAmount;
                result.setAmount(amount);
                return result;
            }
        }
        
        return null;
    }
    
    public List<ItemStack> getRandomItems(String tableName, int count) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ItemStack item = getRandomItem(tableName);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }
    
    private static class WeightedItem {
        final ItemStack item;
        final int weight;
        final int minAmount;
        final int maxAmount;
        
        WeightedItem(ItemStack item, int weight, int minAmount, int maxAmount) {
            this.item = item;
            this.weight = weight;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
        }
    }
} 