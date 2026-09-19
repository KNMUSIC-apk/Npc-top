package com.example.npctopkill;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * FIX #1: Đổi tên từ PlaceholderExpansion → NpcTopKillExpansion
 * để tránh cyclic inheritance (class trùng tên superclass).
 *
 * FIX #8: Không tự duyệt offline players nữa — đọc từ cache của TopKillTask.
 */
public class NpcTopKillExpansion extends PlaceholderExpansion {

    private final NpcTopKill plugin;

    public NpcTopKillExpansion(NpcTopKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "npctopkill";
    }

    @Override
    public @NotNull String getAuthor() {
        return "YourName";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Giữ expansion đăng ký sau khi /papi reload
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String lower = params.toLowerCase();

        // %npctopkill_top_<rank>% → tên người chơi
        if (lower.startsWith("top_")) {
            int rank = parseIntSafe(lower.substring(4));
            if (rank < 1 || rank > 3) return "N/A";
            // FIX #8: đọc từ cache (O(1)), không duyệt offline players
            var cached = plugin.getTopKillTask() != null
                    ? plugin.getTopKillTask().getCachedTop3()
                    : java.util.Collections.<TopKillTask.TopEntry>emptyList();
            return rank <= cached.size() ? cached.get(rank - 1).name : "N/A";
        }

        // %npctopkill_kills_top_<rank>% → số kill
        if (lower.startsWith("kills_top_")) {
            int rank = parseIntSafe(lower.substring(10));
            if (rank < 1 || rank > 3) return "0";
            var cached = plugin.getTopKillTask() != null
                    ? plugin.getTopKillTask().getCachedTop3()
                    : java.util.Collections.<TopKillTask.TopEntry>emptyList();
            return rank <= cached.size() ? String.valueOf(cached.get(rank - 1).kills) : "0";
        }

        return null; // Placeholder không hợp lệ
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
