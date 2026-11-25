package me.fix.susac;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.entity.Steerable;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Packet-accurate movement checks. Every PlayerMoveEvent is one client tick, so we can compare each step
 * against what vanilla physics allows instead of sampling once per server tick like SusAC 1.x did.
 * Covers: Timer, ClickTP/Blink, Speed (ground + air friction), Fly (gravity), Step, NoFall (spoof + no damage),
 * Jesus, NoSlow, InvMove, AirStuck, ElytraFly, FakeLag and vehicle cheats (BoatFly, BoatNoclip, EntityControl).
 */
final class MoveChecks implements Listener {

    static final class M {
        boolean has;
        double ph, pdy;
        boolean st1, st2, prevSpecial;
        long lastPkt, lastMoveMs;
        double balance;
        int pktTick, pktCount;
        double pktDist;
        double spG, spA, spHard, fly, step, jesus, noSlow, nfBuf, elBuf;
        int spoof, useTicks, invTicks;
        double fallDist;
        int landTick, dmgTick = -9999, anyDmgTick = -9999;
        double landDist;
        int glide, hoverGlide, lastBoost = -9999;
        double glideStartY;
        boolean stuckFlagged;
        int boatAir, noclip;
        double vehBuf, saddleBuf;
        long stallAt;
        int burst;
        final ArrayDeque<Long> stalls = new ArrayDeque<>();

        void reset() {
            has = false;
            st1 = st2 = prevSpecial = false;
            spG = spA = spHard = fly = step = jesus = noSlow = 0;
            useTicks = invTicks = spoof = 0;
            fallDist = 0;
            glide = 0;
        }
    }

    private final SusAC plugin;
    private final Core core;
    private final Map<UUID, M> data = new HashMap<>();

    MoveChecks(SusAC plugin, Core core) {
        this.plugin = plugin;
        this.core = core;
        core.addTickHook(this::tick);
    }

    private M st(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new M());
    }

    private boolean on(String c) { return core.on(c); }
    private double num(String path, double def) { return core.num(path, def); }

    private void flag(Player p, String check, String info) {
        plugin.flag(p, Category.MOVEMENT, check, info);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getItem() != null && e.getItem().getType() == Material.FIREWORK_ROCKET) st(e.getPlayer()).lastBoost = core.tick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        M m = st(p);
        m.anyDmgTick = core.tick;
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) m.dmgTick = core.tick;
    }

    // =====================================================================================================
    //  player movement
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        M m = st(p);
        long now = System.currentTimeMillis();
        boolean ex = core.exempt(p);
        boolean grace = core.inGrace(p);
        boolean lagging = core.tick < core.lagUntilTick;
        boolean first = m.lastPkt == 0;
        long gap = now - m.lastPkt;
        m.lastPkt = now;
        m.lastMoveMs = now;
        m.stuckFlagged = false;

        // ---------- Timer: the client may only send one movement packet per 50ms on average ----------
        if (first || ex || grace || lagging || p.isInsideVehicle()) {
            m.balance = 0;
            m.burst = 0;
            m.stallAt = 0;
        } else {
            if (on("timer")) {
                m.balance += 50 - gap;
                if (m.balance < -500) m.balance = -500;
                double thr = num("checks.timer.threshold-ms", 150);
                if (m.balance > thr) {
                    flag(p, "Timer", String.format("%.0fms ahead of the server clock (~%.2fx speed)", m.balance,
                            1 + m.balance / 1000.0));
                    m.balance = 0;
                }
                // ClickTP / Blink: many movement packets crammed into one server tick
                if (m.pktTick != core.tick) {
                    m.pktTick = core.tick;
                    m.pktCount = 0;
                    m.pktDist = 0;
                }
                m.pktCount++;
                m.pktDist += from.distance(to);
                if (m.pktCount >= 6 && m.pktDist > 12) {
                    flag(p, "Timer", m.pktCount + " movement packets (" + String.format("%.1f", m.pktDist) + " blocks) in one tick - ClickTP/Blink");
                    m.pktCount = 0;
                    m.pktDist = 0;
                }
            }
            // FakeLag: stall, then a burst of packets, again and again while fighting
            if (on("fakelag")) {
                if (gap >= 150 && gap < 1200) {
                    m.stallAt = now;
                    m.burst = 0;
                } else if (m.stallAt != 0 && now - m.stallAt <= 60) {
                    m.burst++;
                    if (m.burst == 3 && core.inCombat(p) && p.getPing() < num("checks.fakelag.max-ping", 150)) {
                        m.stalls.addLast(now);
                        while (!m.stalls.isEmpty() && now - m.stalls.peekFirst() > 20000) m.stalls.pollFirst();
                        if (m.stalls.size() >= (int) num("checks.fakelag.stalls", 6)) {
                            flag(p, "FakeLag", m.stalls.size() + " packet hold+release cycles in 20s while fighting");
                            m.stalls.clear();
                        }
                    }
                }
            }
        }

        // ---------- states where vanilla ground physics do not apply ----------
        if (p.isGliding() && !ex && !grace) {
            elytra(p, m, from, to);
            m.has = false;
            m.fallDist = 0;
            return;
        }
        m.glide = 0;
        if (ex || grace || p.getAllowFlight() || p.isFlying() || p.isRiptiding() || p.isInsideVehicle() || p.isDead()) {
            m.reset();
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx == 0 && dy == 0 && dz == 0) return; // rotation-only packet
        double h = Math.hypot(dx, dz);

        boolean st = core.standing(to);
        boolean special = core.special(p, to) || core.special(p, from);
        boolean ice = core.onIce(to) || core.onIce(from);
        boolean soul = core.soulBelow(to);

        PotionEffect sp = p.getPotionEffect(PotionEffectType.SPEED);
        double mult = (sp != null ? 1 + 0.2 * (sp.getAmplifier() + 1) : 1.0) * (p.getWalkSpeed() / 0.2);
        boolean jumpBoost = p.hasPotionEffect(PotionEffectType.JUMP_BOOST);

        // ---------- Speed ----------
        if (on("speed") && m.has && !special && !m.prevSpecial && !ice && !soul && !p.isSneaking()) {
            double buf = num("checks.speed.buffer", 6);
            if (st && m.st1) {
                // Grounded: momentum carried over from the last tick (x0.546 on ground, x0.91 right after landing)
                // plus one tick of sprint acceleration. Validated against vanilla bhop/landing/ledge simulations.
                double carry = m.st2 ? 0.546 : 0.91;
                double limit = m.ph * carry + 0.1274 * mult + 0.012;
                if (h > limit) m.spG += 1; else m.spG = Math.max(0, m.spG - 0.5);
                if (m.spG >= buf) {
                    flag(p, "Speed", String.format("ground %.3f b/tick (max %.3f)", h, limit));
                    m.spG = buf / 2;
                }
            } else if (!st && !m.st1 && !m.st2 && m.ph > 0.01) {
                // Airborne: each tick keeps at most 91% of the last speed plus a tiny strafe push
                double limit = m.ph * 0.91 + 0.026 + 0.012;
                if (h > limit) m.spA += 1; else m.spA = Math.max(0, m.spA - 0.5);
                if (m.spA >= 5) {
                    flag(p, "Speed", String.format("air %.3f b/tick (max %.3f) - strafe/bhop speed", h, limit));
                    m.spA = 2;
                }
            }
            double cap = num("checks.speed.max-per-tick", 0.65) * mult;
            if (h > cap) m.spHard += 1; else m.spHard = Math.max(0, m.spHard - 0.5);
            if (m.spHard >= 3) {
                flag(p, "Speed", String.format("%.2f b/tick (hard cap %.2f)", h, cap));
                m.spHard = 0;
            }
        }

        // ---------- Fly: airborne vertical motion must follow gravity ----------
        if (on("fly") && m.has) {
            boolean skip = st || m.st1 || special || m.prevSpecial || p.hasPotionEffect(PotionEffectType.LEVITATION)
                    || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);
            if (!skip) {
                double expected = (m.pdy - 0.08) * 0.98;
                if (dy - expected > 0.03) m.fly += 1; else m.fly = Math.max(0, m.fly - 0.25);
                if (m.fly >= num("checks.fly.buffer", 6)) {
                    if (core.entityBelow(p) || core.nearHoney(to)) {
                        m.fly = 0;
                    } else {
                        flag(p, "Fly", String.format("dy %.3f but gravity allows %.3f", dy, expected));
                        m.fly = 2;
                    }
                }
            } else {
                m.fly = Math.max(0, m.fly - 0.5);
            }
            // Step: a single move straight up more than a jump or half-block step can give
            if (on("step") && m.st1 && dy > 0.62 && !jumpBoost && !special && !core.bounceBelow(from)) {
                m.step += 1;
                if (m.step >= 2) {
                    flag(p, "Step", String.format("moved up %.2f blocks in one tick", dy));
                    m.step = 0;
                }
            } else {
                m.step = Math.max(0, m.step - 0.1);
            }
        }

        // ---------- NoFall ----------
        if (on("nofall")) {
            if (p.isOnGround() && !st && !m.st1 && core.airBelow(to)) m.spoof++; else m.spoof = 0;
            if (m.spoof >= 4) {
                if (core.entityBelow(p)) {
                    m.spoof = 0;
                } else {
                    flag(p, "NoFall", "claims to be on the ground while in mid-air");
                    m.spoof = 0;
                }
            }
            if (dy < 0 && !st && !special) m.fallDist += -dy;
            else if (dy > 0.01 && !st) m.fallDist = 0;
            if (special) m.fallDist = 0;
            if (st) {
                if (m.fallDist >= num("checks.nofall.min-fall", 8) && !core.bounceBelow(to)) {
                    m.landTick = core.tick;
                    m.landDist = m.fallDist;
                }
                m.fallDist = 0;
            }
        }

        // ---------- Jesus: standing on top of liquid ----------
        if (on("jesus")) {
            boolean topOfLiquid = !st && !to.getBlock().isLiquid() && to.clone().subtract(0, 0.1, 0).getBlock().isLiquid();
            if (topOfLiquid && Math.abs(dy) < 0.02 && h > 0.05) m.jesus += 1; else m.jesus = Math.max(0, m.jesus - 1);
            if (m.jesus >= 10) {
                if (core.entityBelow(p)) {
                    m.jesus = 0;
                } else {
                    flag(p, "Jesus", "walking on " + to.clone().subtract(0, 0.1, 0).getBlock().getType().name().toLowerCase());
                    m.jesus = 0;
                }
            }
        }

        // ---------- NoSlow: moving at full speed while using an item ----------
        if (on("noslow")) {
            if (p.isHandRaised()) m.useTicks++; else m.useTicks = 0;
            if (m.useTicks >= 10 && st && m.st1 && !special && !ice && !soul && h > 0.11 * mult) m.noSlow += 1;
            else m.noSlow = Math.max(0, m.noSlow - 0.5);
            if (m.noSlow >= 6) {
                flag(p, "NoSlow", String.format("%.3f b/tick while using an item", h));
                m.noSlow = 0;
            }
        }

        // ---------- InvMove: walking with a container open ----------
        if (on("invmove")) {
            InventoryType it = p.getOpenInventory().getType();
            if (it != InventoryType.CRAFTING && it != InventoryType.CREATIVE) m.invTicks++; else m.invTicks = 0;
            if (m.invTicks >= 10 && st && m.st1 && !special && !ice && h > 0.1) {
                flag(p, "InvMove", "moving at " + String.format("%.2f", h) + " b/tick with a " + it.name().toLowerCase() + " open");
                m.invTicks = 0;
            }
        }

        m.ph = h;
        m.pdy = dy;
        m.st2 = m.st1;
        m.st1 = st;
        m.prevSpecial = special;
        m.has = true;
    }

    // =====================================================================================================
    //  elytra
    // =====================================================================================================
    private void elytra(Player p, M m, Location from, Location to) {
        if (!on("elytra")) return;
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        double h = Math.hypot(dx, dz);
        if (m.glide == 0 || core.tick - m.lastBoost < 60 || p.isRiptiding()) {
            m.glide = 1;
            m.glideStartY = to.getY();
            m.hoverGlide = 0;
            return;
        }
        m.glide++;
        // 1) speed cap
        if (h > num("checks.elytra.max-horizontal", 2.8)) m.elBuf += 1; else m.elBuf = Math.max(0, m.elBuf - 0.5);
        if (m.elBuf >= 5) {
            flag(p, "ElytraFly", String.format("%.2f b/tick horizontally", h));
            m.elBuf = 0;
        }
        // 2) hovering while gliding
        if (h < 0.04 && Math.abs(dy) < 0.04) m.hoverGlide++; else m.hoverGlide = 0;
        if (m.hoverGlide >= 20) {
            flag(p, "ElytraFly", "hovering while gliding");
            m.hoverGlide = 0;
        }
        // 3) gliding for a long time without fireworks and without losing height (energy cannot be created)
        if (m.glide >= 240) {
            if (to.getY() >= m.glideStartY - 3 && h > 0.3) {
                flag(p, "ElytraFly", "glided 12s with no fireworks and no height loss");
            }
            m.glide = 1;
            m.glideStartY = to.getY();
        }
    }

    // =====================================================================================================
    //  vehicles
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicle(VehicleMoveEvent e) {
        Vehicle v = e.getVehicle();
        if (v.getPassengers().isEmpty() || !(v.getPassengers().get(0) instanceof Player p)) return;
        if (core.exempt(p) || core.inGrace(p)) return;
        Location f = e.getFrom(), t = e.getTo();
        if (f.getWorld() != t.getWorld()) return;
        M m = st(p);
        double dx = t.getX() - f.getX(), dy = t.getY() - f.getY(), dz = t.getZ() - f.getZ();
        double h = Math.hypot(dx, dz);
        boolean boat = v instanceof Boat;

        if (on("vehicle")) {
            boolean ice = core.onIce(t) || core.onIce(f);
            double lim = boat ? (ice ? 2.6 : num("checks.vehicle.max-boat", 0.9)) : num("checks.vehicle.max-mount", 1.1);
            if (h > lim) m.vehBuf += 1; else m.vehBuf = Math.max(0, m.vehBuf - 0.5);
            if (m.vehBuf >= 4) {
                flag(p, "VehicleSpeed", String.format("%s at %.2f b/tick (max %.2f)", v.getType().name().toLowerCase(), h, lim));
                m.vehBuf = 0;
            }
        }
        if (boat && on("boatfly")) {
            if (core.airBelow(t) && !core.supportedBelow(v) && dy > -0.02) m.boatAir++; else m.boatAir = 0;
            if (m.boatAir >= 12) {
                flag(p, "BoatFly", "boat flying through the air");
                m.boatAir = 0;
            }
            boolean inside = t.getBlock().getType().isOccluding() && t.clone().add(0, 0.3, 0).getBlock().getType().isOccluding();
            if (inside) m.noclip++; else m.noclip = 0;
            if (m.noclip >= 3) {
                flag(p, "BoatNoclip", "boat inside solid blocks");
                m.noclip = 0;
            }
        }
        if (on("entitycontrol")) {
            boolean unsaddled = false;
            if (v instanceof Steerable s && !s.hasSaddle()) unsaddled = true;
            else if (v instanceof AbstractHorse ah && ah.getInventory().getSaddle() == null) unsaddled = true;
            if (unsaddled && h > 0.35) m.saddleBuf += 1; else m.saddleBuf = Math.max(0, m.saddleBuf - 0.5);
            if (m.saddleBuf >= 4) {
                flag(p, "EntityControl", "steering a " + v.getType().name().toLowerCase() + " that has no saddle");
                m.saddleBuf = 0;
            }
        }
    }

    // =====================================================================================================
    //  per-tick: NoFall damage verdict + AirStuck
    // =====================================================================================================
    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            M m = data.get(p.getUniqueId());
            if (m == null || core.exempt(p)) continue;

            // NoFall: a long fall ended on the ground but we never saw fall damage
            if (m.landTick > 0 && core.tick - m.landTick >= 5 + p.getPing() / 50) {
                int landed = m.landTick;
                m.landTick = 0;
                World w = p.getWorld();
                Boolean fd = w.getGameRuleValue(GameRule.FALL_DAMAGE);
                boolean immune = (fd != null && !fd) || p.isInvulnerable() || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                        || p.isGliding() || p.isInsideVehicle() || core.inGrace(p) || landed - m.anyDmgTick <= 12;
                PotionEffect res = p.getPotionEffect(PotionEffectType.RESISTANCE);
                if (res != null && res.getAmplifier() >= 4) immune = true;
                Location l = p.getLocation();
                Material under = l.clone().subtract(0, 0.2, 0).getBlock().getType();
                if (under == Material.HAY_BLOCK || under == Material.SCAFFOLDING || core.special(p, l) || core.bounceBelow(l)) immune = true;
                if (!immune) {
                    if (m.dmgTick < landed - 2) {
                        m.nfBuf += 1;
                        if (m.nfBuf >= 2) {
                            flag(p, "NoFall", String.format("fell %.1f blocks and took no damage", m.landDist));
                            m.nfBuf = 0;
                        }
                    } else {
                        m.nfBuf = Math.max(0, m.nfBuf - 1);
                    }
                }
            }

            // AirStuck: no movement packets for >1.5s while hanging in mid-air, yet still doing things
            if (on("airstuck") && m.lastMoveMs != 0 && !m.stuckFlagged && now - m.lastMoveMs > 1500
                    && core.tick - core.lastActivity(p) <= 10 && !core.inGrace(p)
                    && !p.isInsideVehicle() && !p.isGliding() && !p.getAllowFlight() && !p.isDead()) {
                Location l = p.getLocation();
                if (!core.standing(l) && core.airBelow(l) && !core.special(p, l) && !core.entityBelow(p)) {
                    flag(p, "AirStuck", "frozen mid-air for " + ((now - m.lastMoveMs) / 1000) + "s while still acting");
                    m.stuckFlagged = true;
                }
            }
        }
    }
}
