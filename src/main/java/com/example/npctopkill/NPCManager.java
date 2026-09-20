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
 * Quản lý NPC (Citizens) và hologram (ArmorStand).
 *
 * FIXES:
 *  - Giữ nguyên yaw/pitch của người tạo khi spawn NPC
 *  - Ẩn nametag Citizens để không đè hologram
 *  - Tăng khoảng cách hologram (height-offset + line-spacing)
 *  - Khi không có người giữ top → dùng skin "MHF_Question" + tên mặc định
 */
public class NPCManager {

    private final NpcTopKill plugin;
    private final NPCRegistry registry;

    /** top (1/2/3) -> NPC Citizens */
    private final Map<Integer, NPC> topNPCs = new HashMap<>();

    /** top -> danh sách UUID 3 dòng hologram */
    private final Map<Integer, List<UUID>> hologramUUIDs = new HashMap<>();

    public NPCManager(NpcTopKill plugin) {
        this.plugin = plugin;
        this.registry = CitizensAPI.getNPCRegistry();
    }

    // ==================== CREATE ====================

    /**
     * FIX: Giữ nguyên yaw/pitch của người chơi (trước đây ép yaw=0, pitch=0).
     */
    public boolean createNPC(Player player, int top) {
        if (top < 1 || top > 3) return false;

        Location loc = player.getLocation().clone();
        // Chỉ căn giữa block, KHÔNG đổi yaw/pitch → NPC nhìn theo người tạo
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);

        removeNPC(top);

        NPC npc = registry.createNPC(EntityType.PLAYER, "Top " + top);
        if (!npc.spawn(loc)) {
            plugin.getLogger().warning("Không spawn được NPC top " + top);
            registry.deregister(npc);
            return false;
        }
        npc.setProtected(true);
        npc.getOrAddTrait(SkinTrait.class);

        // FIX: Ẩn nametag mặc định của Citizens (tránh chữ "NPC" đè hologram)
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);

        topNPCs.put(top, npc);

        if (plugin.getConfig().getBoolean("hologram.enabled", true)) {
            createHologram(top, loc);
        }

        saveNPCData(top, npc);
        return true;
    }

    public void removeNPC(int top) {
        NPC old = topNPCs.remove(top);
        if (old != null) old.destroy();
        removeAllHologramLines(top);
    }

    // ==================== HOLOGRAM ====================

    /**
     * FIX: Tăng khoảng cách dòng + chiều cao → hologram thoáng hơn khi nhìn gần.
     */
    private void createHologram(int top, Location npcLoc) {
        removeAllHologramLines(top);

        double heightOffset = plugin.getConfig().getDouble("hologram.height-offset", 2.6);
        double lineSpacing  = plugin.getConfig().getDouble("hologram.line-spacing", 0.32);

        Location base = npcLoc.clone().add(0, heightOffset, 0);

        List<UUID> uuids = new ArrayList<>(3);
        ArmorStand l1 = spawnHologramLine(base.clone().add(0,  lineSpacing, 0));
        ArmorStand l2 = spawnHologramLine(base);
        ArmorStand l3 = spawnHologramLine(base.clone().add(0, -lineSpacing, 0));
        if (l1 != null) uuids.add(l1.getUniqueId());
        if (l2 != null) uuids.add(l2.getUniqueId());
        if (l3 != null) uuids.add(l3.getUniqueId());
        hologramUUIDs.put(top, uuids);

        updateHologram(top, null, 0);
    }

    /**
     * Marker ArmorStand: hitbox=0, không đẩy player, không nhận damage.
     */
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
        as.setSilent(true);
        as.setCollidable(false);
        as.setPersistent(true);
        as.setRemoveWhenFarAway(false);
        as.setCustomName(" ");
        return as;
    }

    private void removeAllHologramLines(int top) {
        List<UUID> uuids = hologramUUIDs.remove(top);
        if (uuids == null) return;
        for (UUID id : uuids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) e.remove();
        }
    }

    public void cleanupHolograms() {
        for (int top : new ArrayList<>(hologramUUIDs.keySet())) {
            removeAllHologramLines(top);
        }
    }

    public void updateHologram(int top, String playerName, int kills) {
        List<UUID> uuids = hologramUUIDs.get(top);
        if (uuids == null || uuids.size() < 3) return;

        String emptyName = plugin.getConfig().getString("hologram.empty-name", "&7Đang cập nhật...");
        String name = playerName != null ? playerName : emptyName;
        String killsStr = String.valueOf(kills);

        String l1 = applyPH(plugin.getConfig().getString("hologram.line-1",
                "&6&l🔥 TOP %top% SÁT THỦ 🔥"), top, name, killsStr);
        String l2 = applyPH(plugin.getConfig().getString("hologram.line-2",
                "&eTên: &f%player_name%"), top, name, killsStr);
        String l3 = applyPH(plugin.getConfig().getString("hologram.line-3",
                "&cSố Kill: &f%kills%"), top, name, killsStr);

        setAsName(uuids.get(0), color(l1));
        setAsName(uuids.get(1), color(l2));
        setAsName(uuids.get(2), color(l3));
    }

    private void setAsName(UUID id, String name) {
        Entity e = Bukkit.getEntity(id);
        if (e instanceof ArmorStand as) {
            as.setCustomName(name);
        }
    }

    private String applyPH(String text, int top, String name, String kills) {
        return text.replace("%top%", String.valueOf(top))
                   .replace("%player_name%", name)
                   .replace("%kills%", kills);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ==================== UPDATE NPC ====================

    /**
     * Cập nhật NPC: tên + skin + hologram. PHẢI gọi trên MAIN thread.
     */
    public void updateNPC(int top, String playerName, int kills) {
        NPC npc = topNPCs.get(top);
        if (npc == null) return;

        String emptyName = plugin.getConfig().getString("hologram.empty-name", "&7Đang cập nhật...");
        String displayName = playerName != null ? playerName : emptyName;
        npc.setName(displayName);

        if (npc.isSpawned()) {
            // FIX: Nếu chưa có người → dùng skin mặc định MHF_Question
            String skinTarget = playerName != null ? playerName : "MHF_Question";
            updateSkin(npc, skinTarget);
        }

        if (plugin.getConfig().getBoolean("hologram.enabled", true)) {
            updateHologram(top, playerName, kills);
        }
    }

    /**
     * Đổi skin — bỏ qua nếu không đổi (tránh spam Mojang API).
     */
    private void updateSkin(NPC npc, String playerName) {
        try {
            SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
            if (playerName.equalsIgnoreCase(trait.getSkinName())) return;
            trait.setSkinName(playerName);
        } catch (Exception e) {
            plugin.getLogger().warning("Không đổi được skin cho NPC top: " + e.getMessage());
        }
    }

    // ==================== CENTER ====================

    /**
     * Dịch chuyển NPC về giữa block. FIX: KHÔNG đổi yaw/pitch.
     */
    public void centerAllNPCs() {
        for (Map.Entry<Integer, NPC> entry : topNPCs.entrySet()) {
            NPC npc = entry.getValue();
            if (npc == null || !npc.isSpawned()) continue;

            Location loc = npc.getEntity().getLocation().clone();
            loc.setX(loc.getBlockX() + 0.5);
            loc.setZ(loc.getBlockZ() + 0.5);
            // Không đổi yaw/pitch → NPC giữ nguyên hướng nhìn
            npc.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);

            if (plugin.getConfig().getBoolean("hologram.enabled", true)) {
                createHologram(entry.getKey(), loc);
            }
        }
    }

    // ==================== SAVE / LOAD ====================

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

    public void saveAllNPCs() {
        for (Map.Entry<Integer, NPC> e : topNPCs.entrySet()) {
            saveNPCData(e.getKey(), e.getValue());
        }
    }

    public void loadAllNPCs() {
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

                // FIX: Ẩn nametag Citizens khi load lại
                npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);

                if (npc.isSpawned() && plugin.getConfig().getBoolean("hologram.enabled", true)) {
                    createHologram(top, npc.getEntity().getLocation());
                }
            } catch (NumberFormatException ignored) {
            }
        }
        plugin.getLogger().info("Đã load " + topNPCs.size() + " NPC.");
    }

    // ==================== GETTERS ====================

    public Map<Integer, NPC> getTopNPCs() { return topNPCs; }
    public NPC getNPC(int top) { return topNPCs.get(top); }
}
