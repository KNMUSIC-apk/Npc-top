package com.example.npctopkill;

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

/**
 * Task chạy định kỳ để cập nhật top kill.
 *
 * Luồng thực thi 3 pha:
 *   1. Main thread: lấy snapshot offline players (an toàn với Bukkit API)
 *   2. Async thread: tính toán (đọc file stats, sort) — phần nặng
 *   3. Main thread: cập nhật Citizens NPC + hologram + broadcast
 */
public class TopKillTask extends BukkitRunnable {

    private final NpcTopKill plugin;

    // State hiện tại của top (chỉ truy cập trên main thread)
    private final Map<Integer, String> currentTop = new HashMap<>();
    private final Map<Integer, Integer> currentKills = new HashMap<>();

    // FIX #3: Guard chống chồng lấn khi interval nhỏ + xử lý chậm
    private final AtomicBoolean running = new AtomicBoolean(false);

    // FIX #4: Cờ lần chạy đầu — tránh broadcast "soán ngôi" ảo khi plugin vừa bật
    private boolean firstRun = true;

    // FIX #8: Cache top 3 cho PlaceholderAPI đọc (volatile để đọc an toàn từ thread khác)
    private volatile List<TopEntry> cachedTop3 = Collections.emptyList();

    public TopKillTask(NpcTopKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        // FIX #3: Nếu lần chạy trước chưa xong thì skip
        if (!running.compareAndSet(false, true)) {
            return;
        }

        // Pha 1: Lấy snapshot trên main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            OfflinePlayer[] snapshot;
            try {
                snapshot = Bukkit.getOfflinePlayers();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Không lấy được danh sách offline players", t);
                running.set(false);
                return;
            }

            // Pha 2: Tính toán nặng trên async thread
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    List<TopEntry> top3 = computeTop3(snapshot);
                    cachedTop3 = top3; // FIX #8: cập nhật cache

                    // Pha 3: Cập nhật Citizens/hologram/broadcast — phải trên main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            processTopUpdate(top3);
                        } catch (Throwable t) {
                            plugin.getLogger().log(Level.SEVERE, "Lỗi khi cập nhật top", t);
                        } finally {
                            running.set(false);
                        }
                    });
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE, "Lỗi khi tính top kill", t);
                    running.set(false);
                }
            });
        });
    }

    /**
     * Tính top 3 kill — chạy trên async thread.
     * getStatistic() là I/O đọc file world/playerdata/*.dat nên đây là phần nặng.
     */
    private List<TopEntry> computeTop3(OfflinePlayer[] snapshot) {
        List<TopEntry> list = new ArrayList<>();
        for (OfflinePlayer op : snapshot) {
            try {
                String name = op.getName();
                if (name == null) continue; // bỏ qua player chưa từng join
                int kills = op.getStatistic(Statistic.PLAYER_KILLS);
                if (kills > 0) {
                    list.add(new TopEntry(name, kills));
                }
            } catch (Exception ignored) {
                // Player corrupt stats — bỏ qua an toàn
            }
        }
        list.sort((a, b) -> Integer.compare(b.kills, a.kills));
        return list.stream().limit(3).collect(Collectors.toList());
    }

    /**
     * Cập nhật NPC — PHẢI chạy trên main thread (Citizens API yêu cầu).
     */
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

            // Chỉ update khi có thay đổi (name hoặc kills) — tránh spam SkinTrait
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

        // FIX #4: Chỉ broadcast khi:
        //  - Không phải lần chạy đầu tiên
        //  - Có top 1 cũ + mới, và khác nhau
        if (!firstRun
                && oldTop1Name != null
                && newTop1Name != null
                && !oldTop1Name.equals(newTop1Name)) {
            broadcastTop1Change(oldTop1Name, newTop1Name, newTop1Kills);
        }

        firstRun = false;
    }

    /**
     * Broadcast thông báo soán ngôi Top 1 kèm âm thanh.
     */
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

    /** FIX #8: Expose cache cho PlaceholderAPI (tránh duyệt offline players lại). */
    public List<TopEntry> getCachedTop3() {
        return cachedTop3;
    }

    // ==================== INNER CLASS ====================

    /** Snapshot bất biến của một entry top — an toàn khi share giữa các thread. */
    public static class TopEntry {
        public final String name;
        public final int kills;

        public TopEntry(String name, int kills) {
            this.name = name;
            this.kills = kills;
        }
    }
}
