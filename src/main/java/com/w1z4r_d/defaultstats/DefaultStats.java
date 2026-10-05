package com.w1z4r_d.defaultstats;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class DefaultStats extends JavaPlugin implements Listener {

    // Minimum allowed sync interval, protects against 0/negative values in config.
    private static final int MIN_INTERVAL_SECONDS = 10;

    private Database database;
    private StatCollector statCollector;
    private int syncIntervalSeconds;
    private boolean saveOnQuit;
    private ScheduledTask syncTask;

    // A single dedicated thread for ALL database writes. JDBC connections are
    // not thread-safe, so every write goes through this one queue, in order.
    private ExecutorService dbExecutor;

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

        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "DefaultStats-DB");
            thread.setDaemon(true);
            return thread;
        });

        this.statCollector = new StatCollector(this);
        loadSyncSettings();

        getServer().getPluginManager().registerEvents(this, this);
        startSyncTask();

        getLogger().info("DefaultStats enabled. Syncing every " + syncIntervalSeconds + " seconds.");
    }

    @Override
    public void onDisable() {
        stopSyncTask();

        // Let already queued writes finish before the final save.
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().warning("Some database writes did not finish in time.");
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Save everyone one last time before shutdown (runs on the current thread,
        // the executor is already stopped, so there is no concurrent access).
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

    private void loadSyncSettings() {
        int configured = getConfig().getInt("sync.interval-seconds", 300);
        if (configured < MIN_INTERVAL_SECONDS) {
            getLogger().warning("sync.interval-seconds is too low (" + configured + "), using " + MIN_INTERVAL_SECONDS + ".");
            configured = MIN_INTERVAL_SECONDS;
        }
        this.syncIntervalSeconds = configured;
        this.saveOnQuit = getConfig().getBoolean("sync.save-on-quit", true);
    }

    /**
     * Uses Paper's region-aware schedulers instead of the legacy BukkitScheduler,
     * so the same code works on both Paper and Folia.
     */
    private void startSyncTask() {
        long ticks = syncIntervalSeconds * 20L;
        this.syncTask = getServer().getGlobalRegionScheduler()
                .runAtFixedRate(this, task -> syncAllOnline(), ticks, ticks);
    }

    private void stopSyncTask() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    /**
     * Collects stats for every online player and queues the database writes.
     * Returns how many players were queued.
     */
    private int syncAllOnline() {
        List<Player> players = new ArrayList<>(getServer().getOnlinePlayers());
        for (Player player : players) {
            // Step 1: read the statistics on the thread that owns this player
            // (main thread on Paper, the player's region thread on Folia).
            player.getScheduler().run(this, task -> {
                Map<String, Long> stats = statCollector.collect(player);
                // Step 2: hand the snapshot to the DB thread, so the slow network
                // write to a remote MySQL never blocks the server tick.
                queueSave(player.getUniqueId(), player.getName(), stats);
            }, null);
        }
        return players.size();
    }

    private void queueSave(UUID uuid, String name, Map<String, Long> stats) {
        if (dbExecutor == null || dbExecutor.isShutdown()) return;
        dbExecutor.execute(() -> {
            try {
                database.saveStats(uuid, name, stats);
            } catch (Exception e) {
                getLogger().warning("Failed to save stats for " + name + ": " + e.getMessage());
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!saveOnQuit) return;
        Player player = event.getPlayer();
        // The quit event already runs on the thread that owns the player.
        queueSave(player.getUniqueId(), player.getName(), statCollector.collect(player));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("sync")) {
            int queued = syncAllOnline();
            sender.sendMessage("[DefaultStats] Queued a sync for " + queued + " online players.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            stopSyncTask();
            reloadConfig();
            loadSyncSettings();
            statCollector.reload();
            startSyncTask();
            sender.sendMessage("[DefaultStats] Config reloaded. Database connection settings require a server restart.");
            return true;
        }
        sender.sendMessage("[DefaultStats] Usage: /defaultstats <sync|reload>");
        return true;
    }
}
