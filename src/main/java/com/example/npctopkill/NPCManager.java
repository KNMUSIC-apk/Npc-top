package com.example.npctopkill;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Quản lý NPC và hologram của plugin.
 *  - Tạo/xóa NPC thuộc top 1/2/3
 *  - Đổi tên + skin theo người chơi đang giữ top
 *  - Tạo/quản lý hologram (ArmorStand) nổi trên đầu NPC
 *  - Lưu/đọc dữ liệu vào config
 */
public class NPCManager {

    private final NpcTopKill plugin;
    private final NPCRegistry registry;

    /** top (1/2/3) -> NPC */
    private final Map<Integer, NPC> topNPCs = new HashMap<>();

    // FIX #5: Lưu TẤT CẢ UUID hologram của mỗi top (không chỉ line 1)
    // để tránh rò rỉ entity khi update/center/reload.
    private final Map<Integer, List<UUID>> hologramUUIDs = new HashMap<>();

    public NPCManager(NpcTopKill plugin) {
        this.plugin = plugin;
        this.registry = CitizensAPI.getNPCRegistry();
    }

    // ==================== CREATE ====================

    /**
     * Tạo NPC cho top chỉ định tại vị trí người chơi đang đứng.
     * NPC dùng EntityType.PLAYER để có thể đổi skin.
     */
    public boolean createNPC(Player player, int top) {
        if (top < 1 || top > 3) return false;

        Location loc = player.getLocation();
        // Dịch chuyển về giữa block: X.5, Y, Z.5
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        loc.setYaw(0);
        loc.setPitch(0);

        // Xóa NPC + hologram cũ nếu đã tồn tại cho top này
        removeNPC(top);

        NPC npc = registry.createNPC(EntityType.PLAYER, "Top " + top);
        if (!npc.spawn(loc)) {
            plugin.getLogger().warning("Không spawn được NPC top " + top);
            registry.deregister(npc);
            return false;
        }
        npc.setProtected(true);
        // Thêm SkinTrait để có thể đổi skin sau này
        npc.getOrAddTrait(SkinTrait.class);

        topNPCs.put(top, npc);

        if (plugin.getConfig().getBoolean("hologram.enabled", true)) {
            createHologram(top, loc);
        }

        saveNPCData(top, npc);
        return true;
    }

    /**
     * Xóa NPC của top chỉ định (bao gồm cả hologram).
     */
    public void removeNPC(int top) {
        NPC old = topNPCs.remove(top);
        if (old != null) {
            old.destroy();
        }
        // FIX #5: Xóa TẤT CẢ line hologram, không chỉ line 1
        removeAllHologramLines(top);
    }

    // ==================== HOLOGRAM ====================

    /**
     * FIX #5 + #6: Tạo hologram và theo dõi chính xác cả 3 ArmorStand bằng UUID.
     * Không dùng proximity lookup để tránh nhầm khi 2 NPC đứng gần nhau.
     */
    private void createHologram(int top, Location npcLoc) {
        // Dọn line cũ trước (nếu có) để tránh rò rỉ
        removeAllHologramLines(top);

        double heightOffset = plugin.getConfig().getDouble("hologram.height-offset", 2.3);
        Location base = npcLoc.clone().add(0, heightOffset, 0);

        List<UUID> uuids = new ArrayList<>(3);
        ArmorStand l1 = spawnHologramLine(base.clone().add(0, 0.3, 0));
        ArmorStand l2 = spawnHologramLine(base);
        ArmorStand l3 = spawnHologramLine(base.clone().add(0, -0.3, 0));
        if (l1 != null) uuids.add(l1.getUniqueId());
        if (l2 != null) uuids.add(l2.getUniqueId());
        if (l3 != null) uuids.add(l3.getUniqueId());
        hologramUUIDs.put(top, uuids);

        // Cập nhật nội dung ngay sau khi tạo
        updateHologram(top, null, 0);
    }

    /** Spawn 1 ArmorStand tàng hình làm 1 dòng hologram. */
    private ArmorStand spawnHologramLine(Location loc) {
        if (loc.getWorld() == null) return null;
        ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        as.setVisible(false);
        as.setGravity(false);
        as.setCanPickupItems(false);
        as.setCustomNameVisible(true);
        as.setMarker(true);
        as.setInvulnerable(true);
        as.setBasePlate(false);
        as.setArms(false);
        as.setCustomName(" ");
        return as;
    }

    /** FIX #5: Xóa mọi ArmorStand hologram của top dựa trên UUID đã lưu. */
    private void removeAllHologramLines(int top) {
        List<UUID> uuids = hologramUUIDs.remove(top);
        if (uuids == null) return;
        for (UUID id : uuids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) {
                e.remove();
            }
        }
    }

    /** FIX #11: Dọn dẹp mọi hologram đang được theo dõi (khi tắt/reload plugin). */
    public void cleanupHolograms() {
        for (int top : new ArrayList<>(hologramUUIDs.keySet())) {
            removeAllHologramLines(top);
        }
    }

    /**
     * Cập nhật nội dung hologram cho top chỉ định.
     * Đọc format từ config và thay placeholder.
     */
    public void updateHologram(int top, String playerName, int kills) {
        List<UUID> uuids = hologramUUIDs.get(top);
        if (uuids == null || uuids.size() < 3) return;

        String name = playerName != null ? playerName : "Chưa có";
        String killsStr = String.valueOf(kills);

        String l1 = applyPlaceholders(plugin.getConfig().getString("hologram.line-1",
                "&6&l🔥 TOP %top% SÁT THỦ 🔥"), top, name, killsStr);
        String l2 = applyPlaceholders(plugin.getConfig().getString("hologram.line-2",
                "&eTên: &f%player_name%"), top, name, killsStr);
        String l3 = applyPlaceholders(plugin.getConfig().getString("hologram.line-3",
                "&cSố Kill: &f%kills%"), top, name, killsStr);

        // FIX #5 + #6: Lấy chính xác ArmorStand theo UUID, không tìm theo vùng
        ArmorStand as1 = asArmorStand(uuids.get(0));
        ArmorStand as2 = asArmorStand(uuids.get(1));
        ArmorStand as3 = asArmorStand(uuids.get(2));
        if (as1 != null) as1.setCustomName(color(l1));
        if (as2 != null) as2.setCustomName(color(l2));
        if (as3 != null) as3.setCustomName(color(l3));
    }

    private ArmorStand asArmorStand(UUID id) {
        Entity e = Bukkit.getEntity(id);
        return e instanceof ArmorStand ? (ArmorStand) e : null;
    }

    private String applyPlaceholders(String text, int top, String playerName, String kills) {
        return text.replace("%top%", String.valueOf(top))
                   .replace("%player_name%", playerName)
                   .replace("%kills%", kills);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ==================== UPDATE NPC ====================

    /**
     * Cập nhật tên + skin + hologram cho NPC của top chỉ định.
     * PHẢI gọi trên main thread.
     */
    public void updateNPC(int top, String playerName, int kills) {
        NPC npc = topNPCs.get(top);
        if (npc == null) return;

        npc.setName(playerName != null ? playerName : "Chưa có");

        if (playerName != null && npc.isSpawned()) {
            updateSkin(npc, playerName);
        }

        if (plugin.getConfig().getBoolean("hologram.enabled", true)) {
            updateHologram(top, playerName, kills);
        }
    }

    /**
     * FIX #7: Bỏ qua nếu skin không đổi — tránh spam Mojang API mỗi chu kỳ update.
     * SkinTrait.setSkinName() là blocking call → phải chạy trên main thread.
     */
    private void updateSkin(NPC npc, String playerName) {
        try {
            SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
            // Nếu skin đã đúng thì không gọi lại API
            if (playerName.equalsIgnoreCase(trait.getSkinName())) return;
            trait.setSkinName(playerName);
        } catch (Exception e) {
            plugin.getLogger().warning("Không đổi được skin cho NPC top: " + e.getMessage());
        }
    }

    // ==================== CENTER ====================

    /**
     * Dịch chuyển tất cả NPC về chính giữa block (X.5, Y, Z.5)
     * và chỉnh góc nhìn hướng thẳng (yaw=0, pitch=0).
     */
    public void centerAllNPCs() {
        for (Map.Entry<Integer, NPC> entry : topNPCs.entrySet()) {
            NPC npc = entry.getValue();
            if (npc == null || !npc.isSpawned()) continue;

            Location loc = npc.getEntity().getLocation();
            loc.setX(loc.getBlockX() + 0.5);
            loc.setZ(loc.getBlockZ() + 0.5);
            loc.setYaw(0);
            loc.setPitch(0);

            npc.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);

            // FIX #5: Tạo lại hologram bằng cơ chế UUID an toàn (không rò rỉ)
            if (plugin.getConfig().getBoolean("hologram.enabled", true)) {
                createHologram(entry.getKey(), loc);
            }
        }
    }

    // ==================== SAVE / LOAD ====================

    /** Lưu dữ liệu NPC hiện tại vào config để khôi phục sau khi restart. */
    private void saveNPCData(int top, NPC npc) {
        if (!npc.isSpawned()) return;
        Location loc = npc.getEntity().getLocation();
        String path = "npcs." + top;
        plugin.getConfig().set(path + ".world", loc.getWorld().getName());
        plugin.getConfig().set(path + ".x", loc.getX());
        plugin.getConfig().set(path + ".y", loc.getY());
        plugin.getConfig().set(path + ".z", loc.getZ());
        plugin.getConfig().set(path + ".yaw", loc.getYaw());
        plugin.getConfig().set(path + ".pitch", loc.getPitch());
        plugin.getConfig().set(path + ".citizens-id", npc.getId());
        plugin.saveConfig();
    }

    /** Lưu tất cả NPC khi plugin tắt. */
    public void saveAllNPCs() {
        for (Map.Entry<Integer, NPC> e : topNPCs.entrySet()) {
            saveNPCData(e.getKey(), e.getValue());
        }
    }

    /**
     * Đọc dữ liệu NPC từ config và khôi phục sau khi restart.
     * FIX #11: Xóa hologram cũ còn sót trước khi recreate.
     */
    public void loadAllNPCs() {
        // Dọn ArmorStand cũ có thể còn mồ côi trong bộ nhớ
        cleanupHolograms();
        topNPCs.clear();

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("npcs");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            try {
                int top = Integer.parseInt(key);
                int id = sec.getInt(key + ".citizens-id", -1);
                if (id < 0) continue;

                NPC npc = registry.getById(id);
                if (npc == null) {
                    plugin.getLogger().warning("Không tìm thấy NPC id " + id + " cho top " + top);
                    continue;
                }
                topNPCs.put(top, npc);

                // FIX #11: Recreate hologram tại vị trí NPC (vì ArmorStand cũ đã bị remove)
                if (npc.isSpawned() && plugin.getConfig().getBoolean("hologram.enabled", true)) {
                    createHologram(top, npc.getEntity().getLocation());
                }
            } catch (NumberFormatException ignored) {
            }
        }
        plugin.getLogger().info("Đã load " + topNPCs.size() + " NPC.");
    }

    // ==================== GETTERS ====================

    public NPC getNPC(int top) {
        return topNPCs.get(top);
    }
}
