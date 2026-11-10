package me.server.multiworlds;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Minimal Multiverse-style world manager.
 * It never touches player inventories or ender chests, so they stay shared across every world.
 */
public class MultiWorlds extends JavaPlugin implements TabExecutor {

    private static final String P = "§8[§bMultiWorlds§8] §f";
    private static final List<String> KINDS = List.of("overworld", "nether", "end", "flat", "amplified", "large");
    private static final List<String> SUBS = List.of("create", "import", "load", "unload", "remove", "delete",
            "list", "tp", "setspawn", "info");

    private File file;
    private YamlConfiguration data;
    private final Set<String> pendingDelete = new HashSet<>();

    // ------------------------------------------------------------ lifecycle
    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        file = new File(getDataFolder(), "worlds.yml");
        data = YamlConfiguration.loadConfiguration(file);
        getCommand("mw").setExecutor(this);
        getCommand("mw").setTabCompleter(this);

        ConfigurationSection ws = data.getConfigurationSection("worlds");
        if (ws != null) {
            for (String name : ws.getKeys(false)) {
                if (!ws.getBoolean(name + ".autoload", true)) continue;
                try {
                    World w = build(name, ws.getString(name + ".kind", "overworld"), null);
                    getLogger().info("Loaded world '" + name + "'" + (w == null ? " - FAILED" : ""));
                } catch (Exception e) {
                    getLogger().warning("Could not load world '" + name + "': " + e);
                }
            }
        }
    }

    private void save() {
        try { data.save(file); } catch (IOException e) { e.printStackTrace(); }
    }

    // ------------------------------------------------------------ world helpers
    private World.Environment envOf(String kind) {
        return switch (kind) {
            case "nether" -> World.Environment.NETHER;
            case "end" -> World.Environment.THE_END;
            default -> World.Environment.NORMAL;
        };
    }

    private WorldType typeOf(String kind) {
        return switch (kind) {
            case "flat" -> WorldType.FLAT;
            case "amplified" -> WorldType.AMPLIFIED;
            case "large" -> WorldType.LARGE_BIOMES;
            default -> WorldType.NORMAL;
        };
    }

    /** Creates a new world or loads an existing folder. */
    private World build(String name, String kind, Long seed) {
        WorldCreator wc = new WorldCreator(name);
        wc.environment(envOf(kind));
        wc.type(typeOf(kind));
        if (seed != null) wc.seed(seed);
        return wc.createWorld();
    }

    private boolean isMain(World w) { return w.equals(Bukkit.getWorlds().get(0)); }

    private boolean safe(Location l) {
        Block f = l.getBlock();
        Block fl = f.getRelative(0, -1, 0);
        Material m = fl.getType();
        return f.isEmpty() && f.getRelative(0, 1, 0).isEmpty() && m.isSolid()
                && m != Material.MAGMA_BLOCK && m != Material.CACTUS;
    }

    private Location netherSpot(World w, int cx, int cz) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 80; i++) {
            int x = i == 0 ? cx : cx + r.nextInt(-64, 65);
            int z = i == 0 ? cz : cz + r.nextInt(-64, 65);
            for (int y = r.nextInt(40, 111); y > 32; y--) {
                Block f = w.getBlockAt(x, y, z);
                if (!f.isEmpty() || !f.getRelative(0, 1, 0).isEmpty()) continue;
                Material fl = f.getRelative(0, -1, 0).getType();
                if (fl.isSolid() && fl != Material.MAGMA_BLOCK) return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return new Location(w, cx + 0.5, 64, cz + 0.5);
    }

    private Location surface(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }

    /** Finds a safe place to stand for the world's type. */
    private Location safeSpawn(World w) {
        Location s = w.getSpawnLocation();
        return switch (w.getEnvironment()) {
            case NETHER -> netherSpot(w, s.getBlockX(), s.getBlockZ());
            case THE_END -> surface(w, 0, 0);
            default -> surface(w, s.getBlockX(), s.getBlockZ());
        };
    }

    private Location spawnFor(World w) {
        w.getChunkAt(w.getSpawnLocation());
        Location s = w.getSpawnLocation().clone().add(0.5, 0, 0.5);
        return safe(s) ? s : safeSpawn(w);
    }

    private String kindOf(World w) {
        String k = data.getString("worlds." + w.getName() + ".kind");
        if (k != null) return k;
        return switch (w.getEnvironment()) {
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "overworld";
        };
    }

    private void evacuate(World w) {
        World main = Bukkit.getWorlds().get(0);
        for (Player p : new ArrayList<>(w.getPlayers())) {
            p.teleport(spawnFor(main));
            p.sendMessage(P + "The world you were in was unloaded.");
        }
    }

    private void deleteDir(File dir) throws IOException {
        if (!dir.exists()) return;
        try (Stream<java.nio.file.Path> s = Files.walk(dir.toPath())) {
            for (java.nio.file.Path p : s.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
                Files.deleteIfExists(p);
        }
    }

    private boolean validName(String n) { return n.matches("[A-Za-z0-9_\\-]{1,40}"); }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("multiworlds.admin")) { s.sendMessage(P + "§cNo permission."); return true; }
        if (a.length == 0) { help(s); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(s, a);
            case "import" -> importWorld(s, a);
            case "load" -> load(s, a);
            case "unload" -> unload(s, a, false);
            case "remove" -> unload(s, a, true);
            case "delete" -> delete(s, a);
            case "list" -> list(s);
            case "tp" -> tp(s, a);
            case "setspawn" -> setSpawn(s);
            case "info" -> info(s, a);
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(P + "§bCommands:");
        s.sendMessage("§7/mw create <name> <overworld|nether|end|flat|amplified|large> [seed]");
        s.sendMessage("§7/mw import <name> <overworld|nether|end>   §8(load an existing world folder)");
        s.sendMessage("§7/mw load|unload|remove <name>   §8(remove = forget, keep files)");
        s.sendMessage("§7/mw delete <name> confirm   §8(deletes the world folder!)");
        s.sendMessage("§7/mw list  /mw info [world]  /mw tp <world> [player]  /mw setspawn");
    }

    private void create(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage(P + "§cUsage: /mw create <name> <overworld|nether|end|flat|amplified|large> [seed]"); return; }
        String name = a[1], kind = a[2].toLowerCase(Locale.ROOT);
        if (!validName(name)) { s.sendMessage(P + "§cInvalid name (letters, numbers, _ and - only)."); return; }
        if (!KINDS.contains(kind)) { s.sendMessage(P + "§cType must be: " + String.join(", ", KINDS)); return; }
        if (Bukkit.getWorld(name) != null) { s.sendMessage(P + "§cThat world already exists."); return; }
        if (new File(Bukkit.getWorldContainer(), name).exists()) {
            s.sendMessage(P + "§cA folder with that name exists. Use §e/mw import " + name + " <type>§c.");
            return;
        }
        Long seed = null;
        if (a.length > 3) {
            try { seed = Long.parseLong(a[3]); } catch (NumberFormatException e) { seed = (long) a[3].hashCode(); }
        }
        s.sendMessage(P + "Creating world §b" + name + "§f (" + kind + ")... the server may lag for a moment.");
        World w;
        try { w = build(name, kind, seed); } catch (Exception e) { w = null; getLogger().warning(e.toString()); }
        if (w == null) { s.sendMessage(P + "§cFailed to create the world. Check the console."); return; }
        w.setSpawnLocation(safeSpawn(w));
        data.set("worlds." + name + ".kind", kind);
        data.set("worlds." + name + ".seed", w.getSeed());
        data.set("worlds." + name + ".autoload", true);
        save();
        s.sendMessage(P + "§aCreated §b" + name + "§a. Use §e/mw tp " + name + "§a to go there.");
    }

    private void importWorld(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage(P + "§cUsage: /mw import <name> <overworld|nether|end>"); return; }
        String name = a[1], kind = a[2].toLowerCase(Locale.ROOT);
        if (!KINDS.contains(kind)) { s.sendMessage(P + "§cType must be: " + String.join(", ", KINDS)); return; }
        if (Bukkit.getWorld(name) != null) { s.sendMessage(P + "§cThat world is already loaded."); return; }
        if (!new File(new File(Bukkit.getWorldContainer(), name), "level.dat").exists()) {
            s.sendMessage(P + "§cNo world folder named '" + name + "' with a level.dat was found.");
            return;
        }
        World w;
        try { w = build(name, kind, null); } catch (Exception e) { w = null; getLogger().warning(e.toString()); }
        if (w == null) { s.sendMessage(P + "§cFailed to import the world."); return; }
        data.set("worlds." + name + ".kind", kind);
        data.set("worlds." + name + ".autoload", true);
        save();
        s.sendMessage(P + "§aImported §b" + name + "§a.");
    }

    private void load(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage(P + "§cUsage: /mw load <name>"); return; }
        String name = a[1];
        if (Bukkit.getWorld(name) != null) { s.sendMessage(P + "§cAlready loaded."); return; }
        if (!data.contains("worlds." + name)) { s.sendMessage(P + "§cUnknown world. Use /mw import first."); return; }
        World w;
        try { w = build(name, data.getString("worlds." + name + ".kind", "overworld"), null); }
        catch (Exception e) { w = null; getLogger().warning(e.toString()); }
        if (w == null) { s.sendMessage(P + "§cFailed to load the world."); return; }
        data.set("worlds." + name + ".autoload", true);
        save();
        s.sendMessage(P + "§aLoaded §b" + name + "§a.");
    }

    private void unload(CommandSender s, String[] a, boolean forget) {
        if (a.length < 2) { s.sendMessage(P + "§cUsage: /mw " + a[0] + " <name>"); return; }
        World w = Bukkit.getWorld(a[1]);
        if (w == null) { s.sendMessage(P + "§cThat world is not loaded."); return; }
        if (isMain(w)) { s.sendMessage(P + "§cYou can't unload the main world."); return; }
        evacuate(w);
        if (!Bukkit.unloadWorld(w, true)) { s.sendMessage(P + "§cThe server refused to unload that world."); return; }
        if (forget) data.set("worlds." + w.getName(), null);
        else data.set("worlds." + w.getName() + ".autoload", false);
        save();
        s.sendMessage(P + (forget ? "§aRemoved §b" : "§aUnloaded §b") + w.getName() + "§a.");
    }

    private void delete(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage(P + "§cUsage: /mw delete <name> confirm"); return; }
        String name = a[1];
        World w = Bukkit.getWorld(name);
        if (w != null && isMain(w)) { s.sendMessage(P + "§cYou can't delete the main world."); return; }
        if (!data.contains("worlds." + name) && w == null) { s.sendMessage(P + "§cUnknown world."); return; }
        if (a.length < 3 || !a[2].equalsIgnoreCase("confirm")) {
            s.sendMessage(P + "§cThis permanently deletes the world folder. Run §e/mw delete " + name + " confirm§c to continue.");
            return;
        }
        if (w != null) {
            evacuate(w);
            if (!Bukkit.unloadWorld(w, false)) { s.sendMessage(P + "§cCouldn't unload the world."); return; }
        }
        try { deleteDir(new File(Bukkit.getWorldContainer(), name)); }
        catch (IOException e) { s.sendMessage(P + "§cCouldn't delete all files: " + e.getMessage()); }
        data.set("worlds." + name, null);
        save();
        s.sendMessage(P + "§aDeleted §b" + name + "§a.");
    }

    private void list(CommandSender s) {
        s.sendMessage(P + "§bWorlds:");
        for (World w : Bukkit.getWorlds()) {
            s.sendMessage("§7- §f" + w.getName() + " §8(" + w.getEnvironment().name().toLowerCase(Locale.ROOT)
                    + ", " + w.getPlayers().size() + " players)" + (isMain(w) ? " §e[main]" : ""));
        }
        ConfigurationSection ws = data.getConfigurationSection("worlds");
        if (ws != null) for (String n : ws.getKeys(false))
            if (Bukkit.getWorld(n) == null) s.sendMessage("§7- §8" + n + " (unloaded)");
    }

    private void tp(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage(P + "§cUsage: /mw tp <world> [player]"); return; }
        World w = Bukkit.getWorld(a[1]);
        if (w == null) { s.sendMessage(P + "§cThat world is not loaded."); return; }
        Player target;
        if (a.length > 2) {
            target = Bukkit.getPlayerExact(a[2]);
            if (target == null) { s.sendMessage(P + "§cPlayer not found."); return; }
        } else if (s instanceof Player p) target = p;
        else { s.sendMessage(P + "§cSpecify a player."); return; }
        boolean ok = target.teleport(spawnFor(w));
        if (ok) s.sendMessage(P + "§aTeleported §b" + target.getName() + "§a to §b" + w.getName() + "§a.");
        else s.sendMessage(P + "§cThe teleport was cancelled by another plugin.");
    }

    private void setSpawn(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage(P + "§cPlayers only."); return; }
        Location l = p.getLocation();
        p.getWorld().setSpawnLocation(l);
        s.sendMessage(P + "§aSpawn of §b" + p.getWorld().getName() + "§a set to your position.");
    }

    private void info(CommandSender s, String[] a) {
        World w = a.length > 1 ? Bukkit.getWorld(a[1]) : (s instanceof Player p ? p.getWorld() : null);
        if (w == null) { s.sendMessage(P + "§cWorld not found."); return; }
        Location sp = w.getSpawnLocation();
        s.sendMessage(P + "§b" + w.getName());
        s.sendMessage("§7Type: §f" + kindOf(w) + "  §7Environment: §f" + w.getEnvironment());
        s.sendMessage("§7Seed: §f" + w.getSeed() + "  §7Players: §f" + w.getPlayers().size());
        s.sendMessage("§7Spawn: §f" + sp.getBlockX() + ", " + sp.getBlockY() + ", " + sp.getBlockZ());
    }

    // ------------------------------------------------------------ tab complete
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("multiworlds.admin")) return Collections.emptyList();
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(SUBS);
        else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "tp", "unload", "remove", "delete", "info", "setspawn" ->
                        Bukkit.getWorlds().forEach(w -> out.add(w.getName()));
                case "load" -> {
                    ConfigurationSection ws = data.getConfigurationSection("worlds");
                    if (ws != null) for (String n : ws.getKeys(false)) if (Bukkit.getWorld(n) == null) out.add(n);
                }
                case "import" -> {
                    File[] dirs = Bukkit.getWorldContainer().listFiles(File::isDirectory);
                    if (dirs != null) for (File d : dirs)
                        if (new File(d, "level.dat").exists() && Bukkit.getWorld(d.getName()) == null) out.add(d.getName());
                }
                default -> { }
            }
        } else if (a.length == 3) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "create", "import" -> out.addAll(KINDS);
                case "delete" -> out.add("confirm");
                case "tp" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                default -> { }
            }
        }
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }
}
