package me.fix.susac;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Combat detections aimed at modern cheat clients that hide behind "humanized" timings:
 * silent rotations (SilentAim), aim smoothing (AimAssist), hits that miss the crosshair (HitRay),
 * attacks without a swing (NoSwing), crystal / anchor automation, AutoTotem, AutoSwitch and anti-knockback.
 */
final class CombatAdv implements Listener {

    static final class C {
        // rotation history (tick, dyaw, dpitch)
        final ArrayDeque<float[]> rot = new ArrayDeque<>();
        final ArrayDeque<Integer> flicks = new ArrayDeque<>();
        final ArrayDeque<Double> ratios = new ArrayDeque<>();
        double hitRay;
        int pendAttack = -1;
        double swingBuf;
        // crystals
        long crystalPlaceMs;
        int cx, cy, cz;
        double crystalBuf;
        final ArrayDeque<Long> breaks = new ArrayDeque<>();
        // anchors
        long anchorKey;
        long anchorPlaced, anchorCharged;
        // totem
        boolean waiting;
        long popMs;
        int popTick;
        final ArrayDeque<Long> reacts = new ArrayDeque<>();
        // switching
        int lastSwapTick = -9999;
        final ArrayDeque<Integer> switches = new ArrayDeque<>();
        // knockback
        boolean kbWait;
        int kbTick;
        double kbY;
        double kbMiss;
    }

    private final SusAC plugin;
    private final Core core;
    private final Map<UUID, C> data = new HashMap<>();

    CombatAdv(SusAC plugin, Core core) {
        this.plugin = plugin;
        this.core = core;
        core.addTickHook(this::tick);
    }

    private C st(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new C());
    }

    private void flag(Player p, String check, String info) {
        plugin.flag(p, Category.COMBAT, check, info);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    // =====================================================================================================
    //  rotation: SilentAim + AimAssist
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRotate(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || core.exempt(p)) return;
        C c = st(p);
        if (core.inGrace(p)) {
            c.rot.clear();
            return;
        }
        float dyaw = Core.wrap(to.getYaw() - from.getYaw());
        float dpitch = to.getPitch() - from.getPitch();
        if (Math.abs(dyaw) < 0.001f && Math.abs(dpitch) < 0.001f) return;

        // ---- SilentAim: rotation leaves the camera heading, an action happens, rotation returns ----
        if (core.on("silentaim")) {
            c.rot.addLast(new float[]{core.tick, dyaw, dpitch});
            while (c.rot.size() > 10 || (!c.rot.isEmpty() && core.tick - c.rot.peekFirst()[0] > 8)) c.rot.pollFirst();
            float[][] ev = c.rot.toArray(new float[0][]);
            double minPeak = core.num("checks.silentaim.min-angle", 30);
            boolean hit = false;
            for (int s = 0; s < ev.length - 1 && !hit; s++) {
                float cy = 0, cp = 0, peak = 0;
                for (int k = s; k < ev.length; k++) {
                    cy += ev[k][1];
                    cp += ev[k][2];
                    peak = Math.max(peak, (float) Math.hypot(cy, cp));
                }
                // returned to where it started (within 4 degrees) after a big excursion, with an action in between
                if (peak >= minPeak && Math.hypot(cy, cp) <= 4.0 && core.lastAction(p) >= (int) ev[s][0] - 1) hit = true;
            }
            if (hit) {
                c.flicks.addLast(core.tick);
                while (!c.flicks.isEmpty() && core.tick - c.flicks.peekFirst() > 200) c.flicks.pollFirst();
                if (c.flicks.size() >= (int) core.num("checks.silentaim.count", 3)) {
                    flag(p, "SilentAim", "view snapped away and back around an attack/place " + c.flicks.size() + "x in 10s");
                    c.flicks.clear();
                }
                c.rot.clear();
            }
        }

        // ---- AimAssist: while fighting, the crosshair closes on the nearest player with a constant easing ratio ----
        if (core.on("aimassist") && core.tick - core.lastAttack(p) <= 60) {
            Player tgt = null;
            double best = 36;
            for (Entity en : p.getNearbyEntities(6, 6, 6)) {
                if (!(en instanceof Player o) || o.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                double d = o.getLocation().distanceSquared(from);
                if (d < best && d > 2) {
                    best = d;
                    tgt = o;
                }
            }
            if (tgt != null) {
                Location tl = tgt.getLocation();
                double yawTo = Math.toDegrees(Math.atan2(-(tl.getX() - from.getX()), tl.getZ() - from.getZ()));
                float err = Core.wrap((float) yawTo - from.getYaw());
                if (Math.abs(err) >= 0.8f && Math.abs(err) <= 25f && Math.abs(dyaw) > 0.05f) {
                    double ratio = dyaw / err;
                    if (ratio > 0 && ratio < 1.5) {
                        c.ratios.addLast(ratio);
                        if (c.ratios.size() > 24) c.ratios.pollFirst();
                        if (c.ratios.size() == 24) {
                            double mean = 0;
                            for (double r : c.ratios) mean += r;
                            mean /= 24;
                            double var = 0;
                            for (double r : c.ratios) var += (r - mean) * (r - mean);
                            double cv = Math.sqrt(var / 24) / mean;
                            if (mean > 0.03 && mean < 0.6 && cv < core.num("checks.aimassist.max-variation", 0.2)) {
                                flag(p, "AimAssist", String.format("crosshair eases onto the nearest player at a constant %.0f%% per tick", mean * 100));
                                c.ratios.clear();
                            }
                        }
                    } else {
                        c.ratios.clear();
                    }
                }
            }
        }
    }

    // =====================================================================================================
    //  hits: HitRay, NoSwing, InvAction, CrystalAura
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || core.exempt(p)) return;
        Entity t = e.getEntity();
        C c = st(p);
        c.pendAttack = core.tick;

        if (core.on("invaction")) {
            InventoryType it = p.getOpenInventory().getType();
            if (it != InventoryType.CRAFTING && it != InventoryType.CREATIVE) {
                flag(p, "InvAction", "attacked while a " + it.name().toLowerCase() + " was open");
            }
        }

        if (core.on("hitray") && !core.inGrace(p)) {
            if (!core.lookingAt(p, t, core.num("checks.hitray.margin", 0.35))) c.hitRay += 1;
            else c.hitRay = Math.max(0, c.hitRay - 0.5);
            if (c.hitRay >= core.num("checks.hitray.buffer", 4)) {
                flag(p, "HitRay", "hit " + t.getType().name().toLowerCase() + " without the crosshair touching it");
                c.hitRay = 1;
            }
        }

        if (t instanceof EnderCrystal && core.on("crystalaura")) {
            long now = System.currentTimeMillis();
            c.breaks.addLast(now);
            while (!c.breaks.isEmpty() && now - c.breaks.peekFirst() > 1000) c.breaks.pollFirst();
            if (c.breaks.size() > (int) core.num("checks.crystalaura.max-per-second", 10)) {
                flag(p, "CrystalAura", c.breaks.size() + " crystals broken in one second");
                c.breaks.clear();
            }
            Location cl = t.getLocation();
            if (c.crystalPlaceMs > 0 && cl.getBlockX() == c.cx && cl.getBlockY() == c.cy && cl.getBlockZ() == c.cz) {
                long react = (now - c.crystalPlaceMs) - p.getPing();
                c.crystalPlaceMs = 0;
                if (react < core.num("checks.crystalaura.min-reaction-ms", 60)) c.crystalBuf += 1;
                else c.crystalBuf = Math.max(0, c.crystalBuf - 0.5);
                if (c.crystalBuf >= 4) {
                    flag(p, "CrystalAura", "broke its own crystal " + Math.max(0, react) + "ms after placing it (ping " + p.getPing() + ")");
                    c.crystalBuf = 0;
                }
            }
        }
    }

    /** NoSwing: a legit client always sends an arm swing with every attack. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            C c = data.get(p.getUniqueId());
            if (c == null || core.exempt(p)) continue;

            if (c.pendAttack >= 0 && core.tick - c.pendAttack >= 2) {
                if (core.on("noswing")) {
                    if (core.lastSwing(p) < c.pendAttack - 1) c.swingBuf += 1; else c.swingBuf = Math.max(0, c.swingBuf - 0.5);
                    if (c.swingBuf >= 4) {
                        flag(p, "NoSwing", "attacked without swinging an arm");
                        c.swingBuf = 0;
                    }
                }
                c.pendAttack = -1;
            }

            // AutoTotem: how fast is the offhand totem back after a pop?
            if (c.waiting) {
                if (now - c.popMs > 3000) {
                    c.waiting = false;
                } else if (core.tick > c.popTick && p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) {
                    c.waiting = false;
                    long react = Math.max(0, (now - c.popMs) - p.getPing());
                    double min = core.num("checks.autototem.min-reaction-ms", 90);
                    if (react < min) {
                        flag(p, "AutoTotem", "refilled offhand totem " + react + "ms after popping (ping " + p.getPing() + ")");
                    }
                    c.reacts.addLast(react);
                    if (c.reacts.size() > 6) c.reacts.pollFirst();
                    if (c.reacts.size() == 6) {
                        double mean = 0;
                        for (long r : c.reacts) mean += r;
                        mean /= 6;
                        double var = 0;
                        for (long r : c.reacts) var += (r - mean) * (r - mean);
                        double std = Math.sqrt(var / 6);
                        if (std < core.num("checks.autototem.max-deviation-ms", 20) && mean < 450) {
                            flag(p, "AutoTotem", String.format("last 6 totem refills were all ~%.0fms (+-%.0f) - too consistent", mean, std));
                            c.reacts.clear();
                        }
                    }
                }
            }

            // Velocity: knockback that lifts you must lift you
            if (c.kbWait) {
                int wait = 6 + p.getPing() / 50;
                double y = p.getLocation().getY();
                if (y - c.kbY > 0.08) {
                    c.kbWait = false;
                    c.kbMiss = Math.max(0, c.kbMiss - 1);
                } else if (core.tick - c.kbTick >= wait) {
                    c.kbWait = false;
                    Location l = p.getLocation();
                    boolean excused = p.isDead() || p.isInsideVehicle() || p.isGliding() || p.getAllowFlight()
                            || core.special(p, l) || core.headBlocked(l);
                    if (!excused) {
                        c.kbMiss += 1;
                        if (c.kbMiss >= core.num("checks.velocity.buffer", 4)) {
                            flag(p, "Velocity", "ignored or delayed upward knockback " + (int) c.kbMiss + " times");
                            c.kbMiss = 0;
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        Player p = e.getPlayer();
        if (!core.on("velocity") || core.exempt(p)) return;
        if (e.getVelocity().getY() > 0.25) {
            C c = st(p);
            c.kbWait = true;
            c.kbTick = core.tick;
            c.kbY = p.getLocation().getY();
        }
    }

    // =====================================================================================================
    //  AutoTotem (pop event)
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPop(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p) || !core.on("autototem") || core.exempt(p)) return;
        C c = st(p);
        c.waiting = true;
        c.popMs = System.currentTimeMillis();
        c.popTick = core.tick;
    }

    // =====================================================================================================
    //  crystals / anchors (placement side)
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        Block clicked = e.getClickedBlock();
        if (clicked == null || core.exempt(p)) return;
        C c = st(p);
        Material item = e.getItem() == null ? Material.AIR : e.getItem().getType();

        if (item == Material.END_CRYSTAL) {
            c.crystalPlaceMs = System.currentTimeMillis();
            c.cx = clicked.getX();
            c.cy = clicked.getY() + 1;
            c.cz = clicked.getZ();
        }

        if (core.on("anchormacro") && clicked.getType() == Material.RESPAWN_ANCHOR && c.anchorKey == clicked.getBlockKey()) {
            long now = System.currentTimeMillis();
            if (item == Material.GLOWSTONE) {
                if (c.anchorCharged == 0) c.anchorCharged = now;
            } else if (c.anchorCharged != 0) {
                long total = now - c.anchorPlaced;
                if (total < core.num("checks.anchormacro.min-sequence-ms", 250)) {
                    flag(p, "AnchorMacro", "placed, charged and detonated an anchor in " + total + "ms");
                }
                c.anchorKey = 0;
                c.anchorCharged = 0;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.RESPAWN_ANCHOR) return;
        C c = st(e.getPlayer());
        c.anchorKey = e.getBlockPlaced().getBlockKey();
        c.anchorPlaced = System.currentTimeMillis();
        c.anchorCharged = 0;
    }

    // =====================================================================================================
    //  AutoSwitch: slot swap -> action -> swap back, all within a tick or two (KeyThrow, AutoSwap, macros)
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!core.on("autoswitch") || core.exempt(p)) return;
        C c = st(p);
        int s1 = c.lastSwapTick;
        int a = core.lastAction(p);
        if (s1 > 0 && a >= s1 && a - s1 <= 1 && core.tick - a <= 1) {
            c.switches.addLast(core.tick);
            while (!c.switches.isEmpty() && core.tick - c.switches.peekFirst() > 200) c.switches.pollFirst();
            if (c.switches.size() >= (int) core.num("checks.autoswitch.count", 5)) {
                flag(p, "AutoSwitch", "swapped slot, acted and swapped back within 2 ticks " + c.switches.size() + "x");
                c.switches.clear();
            }
        }
        c.lastSwapTick = core.tick;
    }
}
