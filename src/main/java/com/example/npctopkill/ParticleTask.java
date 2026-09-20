package com.example.npctopkill;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;

/**
 * Task spawn particle xoay quanh NPC theo từng top.
 *  - Top 1: vòng FLAME + cột END_ROD + halo FLAME trên đầu
 *  - Top 2: vòng VILLAGER_HAPPY
 *  - Top 3: vòng ENCHANTMENT_TABLE
 *
 * Chạy trên MAIN thread (spawnParticle yêu cầu).
 * Interval mặc định = 10 ticks (0.5s) — nhẹ, không ảnh hưởng TPS.
 */
public class ParticleTask extends BukkitRunnable {

    private final NpcTopKill plugin;
    private int tickCounter = 0;

    public ParticleTask(NpcTopKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.getConfig().getBoolean("particles.enabled", true)) return;
        tickCounter++;

        for (Map.Entry<Integer, NPC> entry : plugin.getNpcManager().getTopNPCs().entrySet()) {
            int top = entry.getKey();
            NPC npc = entry.getValue();
            if (npc == null || !npc.isSpawned()) continue;

            String particleName = plugin.getConfig().getString("particles.top-" + top);
            if (particleName == null) continue;

            Particle particle;
            try {
                particle = Particle.valueOf(particleName);
            } catch (IllegalArgumentException e) {
                continue; // particle name sai → bỏ qua
            }

            Location center = npc.getEntity().getLocation();
            spawnRing(center, particle, top);

            // Hiệu ứng đặc biệt cho Top 1
            if (top == 1) spawnTop1Effects(center);
        }
    }

    /**
     * Spawn vòng particle xoay quanh NPC.
     * Góc xoay dựa vào tickCounter → tạo hiệu ứng chuyển động.
     */
    private void spawnRing(Location center, Particle particle, int top) {
        World world = center.getWorld();
        if (world == null) return;

        double radius = top == 1 ? 0.9 : 0.7;
        int points    = top == 1 ? 16 : 12;
        double angleOffset = tickCounter * 0.12;

        for (int i = 0; i < points; i++) {
            double angle = (2 * Math.PI / points) * i + angleOffset;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location spawn = center.clone().add(x, 0.15, z);
            // count=1, offset=0 → mỗi điểm 1 particle chính xác
            world.spawnParticle(particle, spawn, 1, 0, 0, 0, 0);
        }
    }

    /**
     * Hiệu ứng đặc biệt cho Top 1:
     *  - Cột END_ROD từ chân NPC lên trên (như cột sáng)
     *  - Halo FLAME nhỏ lơ lửng trên đầu hologram
     */
    private void spawnTop1Effects(Location center) {
        World world = center.getWorld();
        if (world == null) return;

        // Cột sáng: END_ROD từ Y+0.2 đến Y+2.4
        for (double y = 0.2; y <= 2.4; y += 0.25) {
            Location spawn = center.clone().add(0, y, 0);
            world.spawnParticle(Particle.END_ROD, spawn, 1, 0.05, 0, 0.05, 0);
        }

        // Halo: cứ 2 tick mới spawn 1 lần để đỡ rối
        if (tickCounter % 2 == 0) {
            world.spawnParticle(Particle.FLAME,
                    center.clone().add(0, 2.9, 0),
                    6, 0.35, 0.05, 0.35, 0.005);
        }
    }
}
