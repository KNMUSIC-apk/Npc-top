package com.example.npctopkill;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class NpcTopKill extends JavaPlugin {

    private static NpcTopKill instance;
    private NPCManager npcManager;
    private TopKillTask topKillTask;
    private ParticleTask particleTask;
    private CommandHandler commandHandler;
    private NpcTopKillExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Kiểm tra Citizens trước khi init
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

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderExpansion = new NpcTopKillExpansion(this);
            placeholderExpansion.register();
            getLogger().info("Đã đăng ký PlaceholderAPI expansion.");
        }

        startUpdateTask();
        startParticleTask();

        getLogger().info("NpcTopKill đã được bật!");
    }

    private void startUpdateTask() {
        if (topKillTask != null) topKillTask.cancel();
        long intervalTicks = Math.max(20L, getConfig().getLong("update-interval", 30L) * 20L);
        topKillTask = new TopKillTask(this);
        topKillTask.runTaskTimerAsynchronously(this, 100L, intervalTicks);
    }

    /**
     * Task spawn particle quanh NPC – chạy MAIN thread (spawnParticle yêu cầu).
     */
    private void startParticleTask() {
        if (particleTask != null) particleTask.cancel();
        long intervalTicks = Math.max(5L, getConfig().getLong("particles.interval-ticks", 10L));
        particleTask = new ParticleTask(this);
        particleTask.runTaskTimer(this, 40L, intervalTicks);
    }

    @Override
    public void onDisable() {
        if (topKillTask != null) topKillTask.cancel();
        if (particleTask != null) particleTask.cancel();
        if (npcManager != null) {
            npcManager.saveAllNPCs();
            npcManager.cleanupHolograms();
        }
        if (placeholderExpansion != null) placeholderExpansion.unregister();
        getLogger().info("NpcTopKill đã được tắt!");
    }

    public void reloadPlugin() {
        reloadConfig();
        if (npcManager != null) npcManager.loadAllNPCs();
        startUpdateTask();
        startParticleTask();
        getLogger().info("Config đã được reload!");
    }

    public static NpcTopKill getInstance() { return instance; }
    public NPCManager getNpcManager() { return npcManager; }
    public TopKillTask getTopKillTask() { return topKillTask; }
}
