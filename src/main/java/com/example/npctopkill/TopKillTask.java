package com.example.npctopkill;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class TopKillTask extends BukkitRunnable {

    private final NpcTopKill plugin;
    private final Map<Integer, String> currentTop = new HashMap<>();
    private final Map<Integer, Integer> currentKills = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private boolean firstRun = true;
    private volatile List<TopEntry> cachedTop3 = Collections.emptyList();

    public TopKillTask(NpcTopKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            OfflinePlayer[] snapshot;
            try {
                snapshot = Bukkit.getOfflinePlayers();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Không lấy được offline players", t);
                running.set(false);
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    List<TopEntry> top3 = computeTop3(snapshot);
                    cachedTop3 = top3;

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            processTopUpdate(top3);
                        } catch (Throwable t) {
                            plugin.getLogger().log(Level.SEVERE, "Lỗi cập nhật top", t);
                        } finally {
                            running.set(false);
                        }
                    });
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE, "Lỗi tính top kill", t);
                    running.set(false);
                }
            });
        });
    }

    /**
     * Tính top 3 kill — chạy async.
     *
     * MỚI: Bỏ qua người chơi đang bị ban (không xuất hiện trên BXH).
     */
    private List<TopEntry> computeTop3(OfflinePlayer[] snapshot) {
        List<TopEntry> list = new ArrayList<>();
        for (OfflinePlayer op : snapshot) {
            try {
                String name = op.getName();
                if (name == null) continue;

                // === LỌC NGƯỜI CHƠI BỊ BAN ===
                if (isPlayerBanned(op, name)) continue;

                int kills = op.getStatistic(Statistic.PLAYER_KILLS);
                if (kills > 0) {
                    list.add(new TopEntry(name, kills));
                }
            } catch (Exception ignored) {
                // Player corrupt stats hoặc lỗi đọc file → bỏ qua an toàn
            }
        }
        list.sort((a, b) -> Integer.compare(b.kills, a.kills));
        return list.stream().limit(3).collect(Collectors.toList());
    }

    /**
     * Kiểm tra player có bị ban không.
     *
     * Cách 1 (ưu tiên): OfflinePlayer.isBanned() — có sẵn trên Paper/Spigot.
     * Cách 2 (fallback): check trực tiếp qua BanList nếu method throw exception.
     *
     * @return true nếu player bị ban (cần skip khỏi BXH)
     */
    private boolean isPlayerBanned(OfflinePlayer op, String name) {
        // Cách 1: dùng API có sẵn
        try {
            if (op.isBanned()) return true;
        } catch (Throwable ignored) {
            // Một số server phiên bản cũ có thể không hỗ trợ → fallback
        }

        // Cách 2: fallback check qua BanList
        try {
            BanList banList = Bukkit.getBanList(BanList.Type.NAME);
            if (banList != null && banList.isBanned(name)) return true;
        } catch (Throwable ignored) {
        }

        return false;
    }

    private void processTopUpdate(List<TopEntry> top3) {
        String oldTop1Name = currentTop.get(1);
        String newTop1Name = null;
        int newTop1Kills = 0;

        for (int i = 0; i < 3; i++) {
            int rank = i + 1;
            TopEntry data = i < top3.size() ? top3.get(i) : null;

            String oldName = currentTop.get(rank);
            String newName = data != null ? data.name : null;
            int newKills = data != null ? data.kills : 0;
            Integer oldKills = currentKills.get(rank);

            boolean nameChanged = !Objects.equals(oldName, newName);
            boolean killsChanged = (oldKills == null) || (oldKills != newKills);

            if (nameChanged || killsChanged) {
                currentTop.put(rank, newName);
                currentKills.put(rank, newKills);
                plugin.getNpcManager().updateNPC(rank, newName, newKills);
            }

            if (rank == 1 && nameChanged) {
                newTop1Name = newName;
                newTop1Kills = newKills;
            }
        }

        if (!firstRun && oldTop1Name != null && newTop1Name != null
                && !oldTop1Name.equals(newTop1Name)) {
            broadcastTop1Change(oldTop1Name, newTop1Name, newTop1Kills);
        }
        firstRun = false;
    }

    private void broadcastTop1Change(String oldPlayer, String newPlayer, int kills) {
        if (!plugin.getConfig().getBoolean("broadcast.enabled", true)) return;

        String msg = plugin.getConfig().getString("broadcast.message",
                "&c&l[SOÁN NGÔI] &e%new_player% &fđã vượt mặt &c%old_player% &fđể trở thành &6&lTOP 1 SÁT THỦ &f với &c%kills% &fkills!");
        msg = msg.replace("%old_player%", oldPlayer)
                 .replace("%new_player%", newPlayer)
                 .replace("%kills%", String.valueOf(kills));
        msg = ChatColor.translateAlternateColorCodes('&', msg);

        Bukkit.broadcastMessage(msg);

        String soundName = plugin.getConfig().getString("broadcast.sound", "ENTITY_ENDER_DRAGON_GROWL");
        try {
            Sound sound = Sound.valueOf(soundName);
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
            }
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Sound không hợp lệ trong config: " + soundName);
        }
    }

    public List<TopEntry> getCachedTop3() {
        return cachedTop3;
    }

    public static class TopEntry {
        public final String name;
        public final int kills;
        public TopEntry(String name, int kills) {
            this.name = name;
            this.kills = kills;
        }
    }
}
