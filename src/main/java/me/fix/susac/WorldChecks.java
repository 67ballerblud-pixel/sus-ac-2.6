package me.fix.susac;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * World / player-interaction detections: XRay (ore ratio + mining-path efficiency), PlaceRay, FastUse,
 * inventory spam, menu macros (AutoSell / AH sniper style bots) and the client-brand fingerprint.
 */
final class WorldChecks implements Listener {

    static final class W {
        final ArrayDeque<Boolean> xray = new ArrayDeque<>();
        final ArrayDeque<Long> recentBroken = new ArrayDeque<>();
        final Set<Long> recentSet = new HashSet<>();
        int lastRareX, lastRareY, lastRareZ, sinceRare;
        UUID lastRareWorld;
        long lastRareMs;
        double pathHits;
        double placeRay;
        final ArrayDeque<Long> uses = new ArrayDeque<>();
        final ArrayDeque<Long> clicks = new ArrayDeque<>();
        final ArrayDeque<Long> menuGaps = new ArrayDeque<>();
        long lastMenuClick;
    }

    private final SusAC plugin;
    private final Core core;
    private final Map<UUID, W> data = new HashMap<>();

    WorldChecks(SusAC plugin, Core core) {
        this.plugin = plugin;
        this.core = core;
    }

    private W st(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new W());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    // =====================================================================================================
    //  XRay
    // =====================================================================================================
    private static boolean isRare(Material m) {
        return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE || m == Material.ANCIENT_DEBRIS;
    }

    private static boolean isFiller(Material m) {
        return m == Material.STONE || m == Material.DEEPSLATE || m == Material.TUFF || m == Material.GRANITE
                || m == Material.DIORITE || m == Material.ANDESITE || m == Material.NETHERRACK
                || m == Material.BASALT || m == Material.BLACKSTONE;
    }

    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (core.exempt(p) || !core.on("xray")) return;
        W w = st(p);
        Block b = e.getBlock();
        Material m = b.getType();
        boolean rare = isRare(m);
        boolean deep = b.getWorld().getEnvironment() == World.Environment.NETHER || b.getY() < 20;
        long key = b.getBlockKey();

        // Was this ore visible before the player's own digging? (any neighbour that is air and was NOT mined by them)
        boolean hidden = rare;
        if (rare) {
            for (BlockFace f : FACES) {
                Block n = b.getRelative(f);
                if ((n.isPassable() || n.isLiquid()) && !w.recentSet.contains(n.getBlockKey())) {
                    hidden = false;
                    break;
                }
            }
        }

        if (deep && (rare || isFiller(m))) {
            int window = (int) core.num("checks.xray.window", 150);
            w.xray.addLast(rare);
            while (w.xray.size() > window) w.xray.pollFirst();
            if (w.xray.size() >= window) {
                int count = 0;
                for (boolean x : w.xray) if (x) count++;
                if (count >= (int) core.num("checks.xray.rare-ores", 8)) {
                    plugin.flag(p, Category.WORLD, "XRay", count + " diamond/debris in last " + window + " mined blocks");
                }
                w.xray.clear();
            }
        }

        // Path efficiency: X-rayers dig (almost) straight from ore to ore
        long now = System.currentTimeMillis();
        w.sinceRare++;
        if (hidden && deep) {
            UUID wid = b.getWorld().getUID();
            if (w.lastRareWorld != null && w.lastRareWorld.equals(wid) && now - w.lastRareMs < 180000) {
                int dist = Math.abs(b.getX() - w.lastRareX) + Math.abs(b.getY() - w.lastRareY) + Math.abs(b.getZ() - w.lastRareZ);
                if (dist >= 6) {
                    double eff = dist / (double) Math.max(1, w.sinceRare);
                    if (eff >= core.num("checks.xray.path-efficiency", 0.45)) w.pathHits += 1;
                    else w.pathHits = Math.max(0, w.pathHits - 1);
                    if (w.pathHits >= core.num("checks.xray.path-hits", 4)) {
                        plugin.flag(p, Category.WORLD, "XRayPath", String.format("dug almost straight between %d hidden ores (%.0f%% path efficiency)", (int) w.pathHits + 1, eff * 100));
                        w.pathHits = 0;
                    }
                }
            }
            w.lastRareWorld = wid;
            w.lastRareX = b.getX();
            w.lastRareY = b.getY();
            w.lastRareZ = b.getZ();
            w.lastRareMs = now;
            w.sinceRare = 0;
        }
        w.recentBroken.addLast(key);
        w.recentSet.add(key);
        while (w.recentBroken.size() > 64) w.recentSet.remove(w.recentBroken.pollFirst());
    }

    // =====================================================================================================
    //  PlaceRay + FastUse
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (core.exempt(p) || !core.on("placeray") || core.inGrace(p)) return;
        W w = st(p);
        BoundingBox bb = e.getBlockAgainst().getBoundingBox();
        if (bb.getVolume() <= 0) return;
        Location eye = p.getEyeLocation();
        boolean ok = bb.clone().expand(core.num("checks.placeray.margin", 0.6)).rayTrace(eye.toVector(), eye.getDirection(), 8.0) != null;
        if (!ok) w.placeRay += 1; else w.placeRay = Math.max(0, w.placeRay - 0.5);
        if (w.placeRay >= 5) {
            plugin.flag(p, Category.WORLD, "PlaceRay", "placed a block against something it was not looking at");
            w.placeRay = 2;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (core.exempt(p) || !core.on("fastuse")) return;
        W w = st(p);
        long now = System.currentTimeMillis();
        w.uses.addLast(now);
        while (!w.uses.isEmpty() && now - w.uses.peekFirst() > 1000) w.uses.pollFirst();
        if (w.uses.size() > (int) core.num("checks.fastuse.max-per-second", 16)) {
            plugin.flag(p, Category.WORLD, "FastUse", w.uses.size() + " item uses/sec");
            w.uses.clear();
        }
    }

    // =====================================================================================================
    //  inventory: InvSpam + MenuMacro
    // =====================================================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || core.exempt(p)) return;
        W w = st(p);
        long now = System.currentTimeMillis();

        if (core.on("invspam")) {
            w.clicks.addLast(now);
            while (!w.clicks.isEmpty() && now - w.clicks.peekFirst() > 1000) w.clicks.pollFirst();
            if (w.clicks.size() > (int) core.num("checks.invspam.max-per-second", 25)) {
                plugin.flag(p, Category.PLAYER, "InvSpam", w.clicks.size() + " inventory clicks/sec");
                w.clicks.clear();
            }
        }

        if (core.on("menumacro") && e.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
            if (w.lastMenuClick != 0 && now - w.lastMenuClick <= 1500) {
                w.menuGaps.addLast(now - w.lastMenuClick);
                int need = (int) core.num("checks.menumacro.samples", 40);
                while (w.menuGaps.size() > need) w.menuGaps.pollFirst();
                if (w.menuGaps.size() >= need) {
                    double mean = 0;
                    for (long g : w.menuGaps) mean += g;
                    mean /= w.menuGaps.size();
                    double var = 0;
                    for (long g : w.menuGaps) var += (g - mean) * (g - mean);
                    double std = Math.sqrt(var / w.menuGaps.size());
                    if (mean < 350 && std < core.num("checks.menumacro.max-deviation-ms", 35)) {
                        plugin.flag(p, Category.PLAYER, "MenuMacro", String.format("%d menu clicks every %.0fms (+-%.0f)", need, mean, std));
                        w.menuGaps.clear();
                    }
                }
            } else {
                w.menuGaps.clear();
            }
            w.lastMenuClick = now;
        }
    }

    // =====================================================================================================
    //  client brand fingerprint
    // =====================================================================================================
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> brand(p), 40L);
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> brand(p), 200L);
    }

    private void brand(Player p) {
        if (!p.isOnline() || !core.on("clientbrand") || p.hasPermission("sus.bypass")) return;
        String b = p.getClientBrandName();
        if (b == null) return;
        String lower = b.toLowerCase(Locale.ROOT);
        for (String bad : plugin.getConfig().getStringList("checks.clientbrand.blacklist")) {
            String x = bad.toLowerCase(Locale.ROOT).trim();
            if (x.isEmpty()) continue;
            for (String tok : lower.split("[^a-z0-9]+")) {
                if (tok.equals(x)) {
                    plugin.flag(p, Category.PLAYER, "ClientBrand", "client brand is '" + b + "'");
                    return;
                }
            }
        }
        if (plugin.getConfig().getBoolean("checks.clientbrand.log-brands", false)) {
            plugin.getLogger().info(p.getName() + " joined with client brand '" + b + "'");
        }
    }
}
