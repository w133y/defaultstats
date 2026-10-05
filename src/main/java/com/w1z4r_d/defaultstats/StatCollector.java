package com.w1z4r_d.defaultstats;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads default (vanilla) Minecraft statistics for a player using the
 * Bukkit Statistic API and returns them as a flat map of
 * statKey -> value, ready to be written to the database.
 */
public class StatCollector {

    private final DefaultStats plugin;
    private List<Material> trackedMaterials;
    private List<EntityType> trackedEntities;

    public StatCollector(DefaultStats plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        trackedMaterials = plugin.getConfig().getStringList("track-materials").stream()
                .map(this::parseMaterial)
                .filter(m -> m != null)
                .toList();

        trackedEntities = plugin.getConfig().getStringList("track-entities").stream()
                .map(this::parseEntityType)
                .filter(e -> e != null)
                .toList();
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown material in track-materials: " + name);
            return null;
        }
    }

    private EntityType parseEntityType(String name) {
        try {
            return EntityType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown entity type in track-entities: " + name);
            return null;
        }
    }

    /**
     * Collects every UNTYPED vanilla statistic (deaths, kills, distances,
     * playtime, etc.) plus the configured per-material and per-entity
     * statistics for the given player.
     */
    public Map<String, Long> collect(Player player) {
        Map<String, Long> stats = new LinkedHashMap<>();

        for (Statistic statistic : Statistic.values()) {
            try {
                switch (statistic.getType()) {
                    case UNTYPED -> {
                        long value = player.getStatistic(statistic);
                        stats.put(keyFor(statistic), value);
                    }
                    case BLOCK, ITEM -> {
                        for (Material material : trackedMaterials) {
                            try {
                                long value = player.getStatistic(statistic, material);
                                stats.put(keyFor(statistic) + "_" + material.name(), value);
                            } catch (IllegalArgumentException ignored) {
                                // This statistic doesn't apply to this material - skip.
                            }
                        }
                    }
                    case ENTITY -> {
                        for (EntityType entityType : trackedEntities) {
                            try {
                                long value = player.getStatistic(statistic, entityType);
                                stats.put(keyFor(statistic) + "_" + entityType.name(), value);
                            } catch (IllegalArgumentException ignored) {
                                // This statistic doesn't apply to this entity - skip.
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Be defensive: some statistics can throw on certain server versions.
                plugin.getLogger().fine("Skipped statistic " + statistic + ": " + e.getMessage());
            }
        }

        return stats;
    }

    private String keyFor(Statistic statistic) {
        return statistic.name().toLowerCase();
    }
}
