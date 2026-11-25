package me.fix.susac;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared state for every SusAC+ check: the server tick counter, a short position history of every player
 * (for lag-compensated reach/hit checks), grace periods, "last action" timestamps and the world helpers
 * (ground / air / special-block detection) the movement checks need.
 */
final class Core implements Listener {

    static final class PS {
        int action = -9999;
        int swing = -9999;
        int attack = -9999;
        int activity = -9999;
        int grace = 0;
    }

    final SusAC plugin;
    int tick = 0;
    long lastTickMs = 0;
    int lagUntilTick = 0;

    private final Map<UUID, ArrayDeque<BoundingBox>> hist = new HashMap<>();
    private final Map<UUID, PS> ps = new HashMap<>();
    private final List<Runnable> tickHooks = new ArrayList<>();

    Core(SusAC plugin) {
        this.plugin = plugin;
    }

    void addTickHook(Runnable r) {
        tickHooks.add(r);
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::onTick, 1L, 1L);
    }

    private void onTick() {
        tick++;
        long now = System.currentTimeMillis();
        // If the server itself stalled, packets arrive in bursts. Timing checks must ignore that.
        if (lastTickMs != 0 && now - lastTickMs > 150) lagUntilTick = tick + 4;
        lastTickMs = now;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ArrayDeque<BoundingBox> q = hist.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            q.addLast(p.getBoundingBox().clone());
            while (q.size() > 12) q.pollFirst();
        }
        for (Runnable r : tickHooks) r.run();
    }

    // ---------- config helpers ----------
    boolean on(String check) {
        return plugin.getConfig().getBoolean("checks." + check + ".enabled", true);
    }

    double num(String path, double def) {
        return plugin.getConfig().getDouble(path, def);
    }

    boolean exempt(Player p) {
        GameMode gm = p.getGameMode();
        return p.hasPermission("sus.bypass") || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    // ---------- per player state ----------
    PS ps(Player p) {
        return ps.computeIfAbsent(p.getUniqueId(), k -> new PS());
    }

    int lastAction(Player p) { return ps(p).action; }
    int lastSwing(Player p) { return ps(p).swing; }
    int lastAttack(Player p) { return ps(p).attack; }
    int lastActivity(Player p) { return ps(p).activity; }

    boolean inCombat(Player p) {
        return tick - ps(p).attack < 100;
    }

    void grace(Player p, int ticks) {
        PS s = ps(p);
        s.grace = Math.max(s.grace, tick + ticks);
    }

    boolean inGrace(Player p) {
        return tick < ps(p).grace;
    }

    // ---------- history / reach ----------
    static double distToBox(Location eye, BoundingBox bb) {
        double cx = Math.max(bb.getMinX(), Math.min(eye.getX(), bb.getMaxX()));
        double cy = Math.max(bb.getMinY(), Math.min(eye.getY(), bb.getMaxY()));
        double cz = Math.max(bb.getMinZ(), Math.min(eye.getZ(), bb.getMaxZ()));
        double dx = eye.getX() - cx, dy = eye.getY() - cy, dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private List<BoundingBox> window(Player attacker, Entity t) {
        List<BoundingBox> out = new ArrayList<>();
        out.add(t.getBoundingBox());
        if (t instanceof Player tp) {
            ArrayDeque<BoundingBox> q = hist.get(tp.getUniqueId());
            if (q != null) {
                int back = Math.min(11, (int) Math.ceil(attacker.getPing() / 50.0) + 3);
                Iterator<BoundingBox> it = q.descendingIterator();
                while (it.hasNext() && back-- > 0) out.add(it.next());
            }
        }
        return out;
    }

    /** Smallest eye-to-hitbox distance over the time window the attacker could have been seeing (lag compensated). */
    double minReach(Player attacker, Entity t) {
        Location eye = attacker.getEyeLocation();
        double best = Double.MAX_VALUE;
        for (BoundingBox bb : window(attacker, t)) best = Math.min(best, distToBox(eye, bb));
        return best;
    }

    /** True if the attacker's crosshair ray touches the target hitbox (inflated by margin) at any recent moment. */
    boolean lookingAt(Player attacker, Entity t, double margin) {
        Location eye = attacker.getEyeLocation();
        Vector o = eye.toVector();
        Vector dir = eye.getDirection();
        for (BoundingBox bb : window(attacker, t)) {
            if (bb.clone().expand(margin).rayTrace(o, dir, 8.0) != null) return true;
        }
        return false;
    }

    // ---------- world helpers ----------
    boolean onIce(Location l) {
        World w = l.getWorld();
        for (int dy = 1; dy <= 2; dy++) {
            Material m = w.getBlockAt(l.getBlockX(), l.getBlockY() - dy, l.getBlockZ()).getType();
            if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE) return true;
        }
        return false;
    }

    boolean soulBelow(Location l) {
        World w = l.getWorld();
        for (int dy = 1; dy <= 2; dy++) {
            Material m = w.getBlockAt(l.getBlockX(), l.getBlockY() - dy, l.getBlockZ()).getType();
            if (m == Material.SOUL_SAND || m == Material.SOUL_SOIL || m == Material.HONEY_BLOCK || m == Material.SLIME_BLOCK) return true;
        }
        return false;
    }

    boolean bounceBelow(Location l) {
        World w = l.getWorld();
        for (int dy = 0; dy <= 2; dy++) {
            Material m = w.getBlockAt(l.getBlockX(), NumberConversions.floor(l.getY() - 0.2) - dy, l.getBlockZ()).getType();
            if (m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK || Tag.BEDS.isTagged(m)) return true;
        }
        return false;
    }

    /**
     * True if the player's feet box rests on something solid at this location. Uses the real collision shape
     * of the blocks, so slabs, fences, carpets, snow layers etc. are handled correctly.
     */
    boolean standing(Location l) {
        World w = l.getWorld();
        double x = l.getX(), y = l.getY(), z = l.getZ();
        int x0 = NumberConversions.floor(x - 0.3), x1 = NumberConversions.floor(x + 0.3);
        int z0 = NumberConversions.floor(z - 0.3), z1 = NumberConversions.floor(z + 0.3);
        int by = NumberConversions.floor(y - 0.01);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                for (int k = 0; k <= 1; k++) {
                    Block b = w.getBlockAt(bx, by - k, bz);
                    if (b.isPassable()) continue;
                    if (b.getType().isOccluding()) {
                        double d = y - (b.getY() + 1.0);
                        if (d >= -0.01 && d <= 0.03) return true;
                        continue;
                    }
                    for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                        if (bx + bb.getMaxX() <= x - 0.3 || bx + bb.getMinX() >= x + 0.3
                                || bz + bb.getMaxZ() <= z - 0.3 || bz + bb.getMinZ() >= z + 0.3) continue;
                        double d = y - (b.getY() + bb.getMaxY());
                        if (d >= -0.01 && d <= 0.03) return true;
                    }
                }
            }
        }
        return false;
    }

    /** True if the feet block and the two blocks below are all empty (really in the air). */
    boolean airBelow(Location l) {
        World w = l.getWorld();
        int y = NumberConversions.floor(l.getY());
        double[] offs = {-0.3, 0, 0.3};
        for (double ox : offs) {
            for (double oz : offs) {
                for (int dy = 0; dy <= 2; dy++) {
                    Block b = w.getBlockAt(NumberConversions.floor(l.getX() + ox), y - dy, NumberConversions.floor(l.getZ() + oz));
                    if (!b.isPassable() || b.isLiquid()) return false;
                }
            }
        }
        return true;
    }

    /**
     * True if something solid (or liquid) is under ANY part of the entity's hitbox. A boat is 1.375 wide, so
     * over a 1x1 hole it still rests on the edges of the hole even though its centre point has air below it.
     */
    boolean supportedBelow(Entity e) {
        BoundingBox bb = e.getBoundingBox();
        World w = e.getWorld();
        int x0 = NumberConversions.floor(bb.getMinX() + 0.001), x1 = NumberConversions.floor(bb.getMaxX() - 0.001);
        int z0 = NumberConversions.floor(bb.getMinZ() + 0.001), z1 = NumberConversions.floor(bb.getMaxZ() - 0.001);
        int y = NumberConversions.floor(bb.getMinY() - 0.05);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block b = w.getBlockAt(x, y - dy, z);
                    if (!b.isPassable() || b.isLiquid()) return true;
                }
            }
        }
        return false;
    }

    /** Places where vanilla physics does not apply (water, ladders, webs, ...). Movement checks skip these. */
    boolean special(Player p, Location l) {
        if (p.isInWater() || p.isClimbing() || p.isSwimming() || p.isRiptiding()) return true;
        World w = l.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        for (int dy = 0; dy <= 1; dy++) {
            Block b = w.getBlockAt(x, y + dy, z);
            Material m = b.getType();
            if (b.isLiquid() || Tag.CLIMBABLE.isTagged(m) || m == Material.COBWEB || m == Material.POWDER_SNOW
                    || m == Material.SWEET_BERRY_BUSH || m == Material.BUBBLE_COLUMN || m == Material.SCAFFOLDING
                    || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.KELP || m == Material.KELP_PLANT) {
                return true;
            }
        }
        return false;
    }

    /** Boats, minecarts and shulkers can be stood on; the block collision code cannot see them. */
    boolean entityBelow(Player p) {
        for (Entity en : p.getNearbyEntities(1.3, 2.2, 1.3)) {
            String t = en.getType().name();
            if (t.contains("BOAT") || t.contains("RAFT") || t.contains("MINECART") || t.equals("SHULKER")) return true;
        }
        return false;
    }

    /** Honey blocks slow falling when you slide on their sides, which looks exactly like gliding. */
    boolean nearHoney(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (w.getBlockAt(x + dx, y + dy, z + dz).getType() == Material.HONEY_BLOCK) return true;
                }
            }
        }
        return false;
    }

    boolean headBlocked(Location l) {
        World w = l.getWorld();
        for (double dy = 1.8; dy <= 2.6; dy += 0.4) {
            Block b = w.getBlockAt(l.getBlockX(), NumberConversions.floor(l.getY() + dy), l.getBlockZ());
            if (!b.isPassable()) return true;
        }
        return false;
    }

    static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    // ---------- shared events ----------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        grace(e.getPlayer(), 60);
        ps(e.getPlayer()).activity = tick;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        hist.remove(id);
        ps.remove(id);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        grace(e.getPlayer(), (int) (num("teleport-grace-ms", 2000) / 50));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        grace(e.getPlayer(), 60);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) grace(p, 30);
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        grace(e.getPlayer(), 30);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        PS s = ps(e.getPlayer());
        s.swing = tick;
        s.activity = tick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        PS s = ps(e.getPlayer());
        s.action = tick;
        s.activity = tick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        PS s = ps(p);
        s.action = tick;
        s.attack = tick;
        s.activity = tick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        PS s = ps(e.getPlayer());
        s.action = tick;
        s.activity = tick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        PS s = ps(e.getPlayer());
        s.action = tick;
        s.activity = tick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) ps(p).activity = tick;
    }
}
