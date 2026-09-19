package com.example.npctopkill;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main class của plugin NpcTopKill.
 * Chịu trách nhiệm khởi tạo các manager, task, command và expansion.
 */
public class NpcTopKill extends JavaPlugin {

    private static NpcTopKill instance;
    private NPCManager npcManager;
    private TopKillTask topKillTask;
    private CommandHandler commandHandler;
    private NpcTopKillExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // FIX #10: Kiểm tra Citizens đã enable chưa trước khi khởi tạo NPCManager
        var citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        if (citizens == null || !citizens.isEnabled()) {
            getLogger().severe("Citizens chưa được cài hoặc chưa bật! Plugin sẽ tự tắt.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        npcManager = new NPCManager(this);
        npcManager.loadAllNPCs();

        commandHandler = new CommandHandler(this);
        if (getCommand("npctopkill") != null) {
            getCommand("npctopkill").setExecutor(commandHandler);
            getCommand("npctopkill").setTabCompleter(commandHandler);
        }

        // FIX #1: Đổi tên class expansion để tránh cyclic inheritance
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderExpansion = new NpcTopKillExpansion(this);
            placeholderExpansion.register();
            getLogger().info("Đã đăng ký PlaceholderAPI expansion.");
        }

        // FIX #9: Tách riêng startUpdateTask để có thể restart khi reload
        startUpdateTask();

        getLogger().info("NpcTopKill đã được bật!");
    }

    /**
     * FIX #9: Khởi động (hoặc restart) task cập nhật với interval mới nhất từ config.
     */
    private void startUpdateTask() {
        if (topKillTask != null) {
            topKillTask.cancel();
        }
        long intervalTicks = Math.max(20L, getConfig().getLong("update-interval", 30L) * 20L);
        topKillTask = new TopKillTask(this);
        topKillTask.runTaskTimerAsynchronously(this, 100L, intervalTicks);
    }

    @Override
    public void onDisable() {
        if (topKillTask != null) topKillTask.cancel();
        if (npcManager != null) {
            npcManager.saveAllNPCs();
            // FIX #11: Dọn hologram khi tắt plugin để tránh entity mồ côi
            npcManager.cleanupHolograms();
        }
        if (placeholderExpansion != null) placeholderExpansion.unregister();
        getLogger().info("NpcTopKill đã được tắt!");
    }

    public void reloadPlugin() {
        reloadConfig();
        if (npcManager != null) npcManager.loadAllNPCs();
        startUpdateTask(); // FIX #9: restart task để áp dụng interval mới
        getLogger().info("Config đã được reload!");
    }

    // ==================== GETTERS ====================
    public static NpcTopKill getInstance() { return instance; }
    public NPCManager getNpcManager() { return npcManager; }
    public TopKillTask getTopKillTask() { return topKillTask; }
}
