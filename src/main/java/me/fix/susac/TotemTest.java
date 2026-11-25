package me.fix.susac;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /sus test <player>: takes the totem out of the player's hand and measures how fast a totem shows up again.
 * Someone using auto totem has a spare in their inventory and the hack puts it back in a couple of ticks.
 * This is a staff tool only. It reports to the sender and never flags anyone. The removed totem is always given back.
 */
final class TotemTest implements Listener {

    private static final class Session {
        Player target;
        CommandSender sender;
        ItemStack removed;
        boolean fromOff;
        int heldSlot;
        long startMs;
        int ticks;
        BukkitTask task;
    }

    private final SusAC plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    TotemTest(SusAC plugin) {
        this.plugin = plugin;
    }

    void start(CommandSender sender, Player t) {
        if (sessions.containsKey(t.getUniqueId())) {
            say(sender, "&cA test is already running on &f" + t.getName() + "&c.");
            return;
        }
        PlayerInventory inv = t.getInventory();
        boolean fromOff = inv.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
        boolean fromMain = !fromOff && inv.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING;
        if (!fromOff && !fromMain) {
            say(sender, "&f" + t.getName() + " &cisn't holding a totem in either hand.");
            return;
        }
        int held = inv.getHeldItemSlot();
        int spare = 0;
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            if (fromMain && i == held) continue;
            ItemStack it = storage[i];
            if (it != null && it.getType() == Material.TOTEM_OF_UNDYING) spare += it.getAmount();
        }
        if (spare == 0) {
            say(sender, "&f" + t.getName() + " &chas no spare totem in their inventory, so there is nothing for auto totem to swap in. Test not run.");
            return;
        }

        Session s = new Session();
        s.target = t;
        s.sender = sender;
        s.fromOff = fromOff;
        s.heldSlot = held;
        s.removed = (fromOff ? inv.getItemInOffHand() : inv.getItemInMainHand()).clone();
        if (fromOff) inv.setItemInOffHand(null);
        else inv.setItemInMainHand(null);
        s.startMs = System.currentTimeMillis();
        sessions.put(t.getUniqueId(), s);
        say(sender, "&7Removed a totem from &f" + t.getName() + "&7's " + (fromOff ? "offhand" : "main hand") + ". Watching...");

        s.task = new BukkitRunnable() {
            @Override
            public void run() {
                tick(s);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void tick(Session s) {
        Player t = s.target;
        if (!t.isOnline()) { // quit handler normally gets here first
            end(s);
            return;
        }
        s.ticks++;
        PlayerInventory inv = t.getInventory();
        boolean refilled = inv.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING
                || (!s.fromOff && inv.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING);
        if (refilled) {
            long ms = System.currentTimeMillis() - s.startMs;
            end(s);
            report(s, ms);
        } else if (s.ticks >= plugin.getConfig().getInt("test.window-ticks", 20)) {
            end(s);
            report(s, -1);
        }
    }

    /** Stops the session and gives the totem back. */
    private void end(Session s) {
        if (s.task != null) s.task.cancel();
        sessions.remove(s.target.getUniqueId());
        giveBack(s);
    }

    private void giveBack(Session s) {
        Player t = s.target;
        PlayerInventory inv = t.getInventory();
        if (s.fromOff && inv.getItemInOffHand().getType().isAir()) {
            inv.setItemInOffHand(s.removed);
            return;
        }
        if (!s.fromOff && inv.getHeldItemSlot() == s.heldSlot && inv.getItemInMainHand().getType().isAir()) {
            inv.setItemInMainHand(s.removed);
            return;
        }
        for (ItemStack left : inv.addItem(s.removed).values()) {
            t.getWorld().dropItemNaturally(t.getLocation(), left);
        }
    }

    private void report(Session s, long ms) {
        Player t = s.target;
        CommandSender to = s.sender;
        int window = plugin.getConfig().getInt("test.window-ticks", 20) * 50;
        String head = "&8[&cSUS test&8] &f" + t.getName() + "&7: ";
        if (ms < 0) {
            say(to, head + "&anothing was put back in " + window + "ms. No auto totem seen.");
            say(to, "&8A clean result is not proof - some clients only auto totem after a pop.");
            return;
        }
        long react = Math.max(0, ms - t.getPing());
        double min = plugin.getConfig().getDouble("checks.autototem.min-reaction-ms", 90);
        double slow = plugin.getConfig().getDouble("test.suspicious-ms", 250);
        String verdict;
        if (react < min) verdict = "&c&lLIKELY AUTO TOTEM &r&7(faster than a human can react)";
        else if (react < slow) verdict = "&6suspicious &7(quicker than most players)";
        else verdict = "&aslow, so probably done by hand";
        say(to, head + "totem back after &f" + react + "ms &8(ping " + t.getPing() + ") " + verdict);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        end(s);
        say(s.sender, "&f" + s.target.getName() + " &7left during the test. Totem returned, test cancelled.");
    }

    /** Called on plugin disable so nobody loses a totem. */
    void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) end(s);
    }

    private static void say(CommandSender to, String msg) {
        to.sendMessage(SusAC.color(msg));
    }
}
