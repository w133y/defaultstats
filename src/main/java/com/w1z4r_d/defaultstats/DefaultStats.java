package com.w1z4r_d.defaultstats;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class DefaultStats extends JavaPlugin implements Listener {

    private Database database;
    private StatCollector statCollector;
    private int syncIntervalSeconds;
    private boolean saveOnQuit;
    private int taskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        this.database = new Database(this);
        try {
            database.connect();
            database.createTables();
        } catch (Exception e) {
            getLogger().severe("Could not connect to database, disabling plugin: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.statCollector = new StatCollector(this);
        this.syncIntervalSeconds = getConfig().getInt("sync.interval-seconds", 300);
        this.saveOnQuit = getConfig().getBoolean("sync.save-on-quit", true);

        getServer().getPluginManager().registerEvents(this, this);
        startSyncTask();

        getLogger().info("DefaultStats enabled. Syncing every " + syncIntervalSeconds + " seconds.");
    }

    @Override
    public void onDisable() {
        stopSyncTask();
        // Save everyone one last time before shutdown
        if (database != null && statCollector != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                try {
                    database.saveStats(player.getUniqueId(), player.getName(), statCollector.collect(player));
                } catch (Exception e) {
                    getLogger().warning("Failed to save stats for " + player.getName() + " on shutdown: " + e.getMessage());
                }
            }
        }
        if (database != null) {
            database.close();
        }
    }

    private void startSyncTask() {
        Runnable syncAll = () -> {
            // Крок 1 (головний потік, швидко): збираємо значення статистик
            // з Bukkit API, поки гравці ще онлайн і дані валідні.
            java.util.Map<java.util.UUID, StatSnapshot> snapshots = new java.util.HashMap<>();
            for (Player player : getServer().getOnlinePlayers()) {
                snapshots.put(player.getUniqueId(),
                        new StatSnapshot(player.getName(), statCollector.collect(player)));
            }

            // Крок 2 (асинхронно): мережевий запис у БД винесений з головного
            // потоку, щоб НЕ лагав сервер під час звернення до віддаленого MySQL.
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                for (var entry : snapshots.entrySet()) {
                    try {
                        database.saveStats(entry.getKey(), entry.getValue().name(), entry.getValue().stats());
                    } catch (Exception e) {
                        getLogger().warning("Failed to save stats for " + entry.getValue().name() + ": " + e.getMessage());
                    }
                }
            });
        };

        long ticks = syncIntervalSeconds * 20L;
        this.taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, syncAll, ticks, ticks);
    }

    private record StatSnapshot(String name, java.util.Map<String, Long> stats) {}

    private void stopSyncTask() {
        if (taskId != -1) {
            getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!saveOnQuit) return;
        Player player = event.getPlayer();
        java.util.UUID uuid = player.getUniqueId();
        String name = player.getName();
        java.util.Map<String, Long> stats = statCollector.collect(player);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                database.saveStats(uuid, name, stats);
            } catch (Exception e) {
                getLogger().warning("Failed to save stats for " + name + " on quit: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("sync")) {
            java.util.Map<java.util.UUID, StatSnapshot> snapshots = new java.util.HashMap<>();
            for (Player player : getServer().getOnlinePlayers()) {
                snapshots.put(player.getUniqueId(), new StatSnapshot(player.getName(), statCollector.collect(player)));
            }
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                int count = 0;
                for (var entry : snapshots.entrySet()) {
                    try {
                        database.saveStats(entry.getKey(), entry.getValue().name(), entry.getValue().stats());
                        count++;
                    } catch (Exception e) {
                        getLogger().warning("Failed to save stats for " + entry.getValue().name() + ": " + e.getMessage());
                    }
                }
                final int synced = count;
                getServer().getScheduler().runTask(this, () ->
                        sender.sendMessage("[DefaultStats] Synced " + synced + " online players to the database."));
            });
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            stopSyncTask();
            reloadConfig();
            this.syncIntervalSeconds = getConfig().getInt("sync.interval-seconds", 300);
            this.saveOnQuit = getConfig().getBoolean("sync.save-on-quit", true);
            statCollector.reload();
            startSyncTask();
            sender.sendMessage("[DefaultStats] Config reloaded.");
            return true;
        }
        sender.sendMessage("[DefaultStats] Usage: /defaultstats <sync|reload>");
        return true;
    }
}
