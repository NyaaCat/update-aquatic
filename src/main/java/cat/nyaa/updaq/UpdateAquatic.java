package cat.nyaa.updaq;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class UpdateAquatic extends JavaPlugin {

    ChunkUpdateTask inProgressTask = null; // if cancelled, treat as null

    // ref: https://www.baeldung.com/java-delete-directory
    public static boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"updateaquatic".equals(command.getName())) return false;
        if (args.length < 1) return false;

        String subcmd = args[0];
        if ("chunk".equalsIgnoreCase(subcmd)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Retry as player");
                return true;
            }

            if (inProgressTask != null && !inProgressTask.isCancelled()) {
                sender.sendMessage("Another task is running.");
                return true;
            }

            if (getConfig().isConfigurationSection("paused_task")) {
                sender.sendMessage("Another task is paused, try \"/updaq remove_paused\"");
                return true;
            }

            int radius = args.length > 1 ? (Integer.parseInt(args[1])) : 1;

            Location l = ((Player) sender).getLocation();
            String worldName = l.getWorld().getName();
            int x = l.getChunk().getX();
            int z = l.getChunk().getZ();
            int r = radius;
            long tick = 7;
            World targetWorld = Bukkit.getWorld(worldName);
            if (targetWorld == null) {
                sender.sendMessage(String.format("World %s not found", worldName));
                return true;
            }
            World templateWorld = Bukkit.createWorld(new WorldCreator(targetWorld.getName() + "_template").copy(targetWorld));

            inProgressTask = new ChunkUpdateTask(this, templateWorld, targetWorld, x, z, r);
            inProgressTask.logB("[Start] Update a chunk every %d ticks", tick);
            inProgressTask.runTaskTimer(this, 20L, tick);
        } else if ("remove_template_world".equalsIgnoreCase(subcmd)) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /updaq RemoveTemplateWorld <world_name>");
                return true;
            }
            String worldName = args[1] + "_template";
            World tmpw = Bukkit.getWorld(worldName);
            if (tmpw != null) {
                File folder = tmpw.getWorldFolder();
                boolean unloadSuccess = Bukkit.unloadWorld(tmpw, false);
                if (!unloadSuccess) {
                    sender.sendMessage(String.format("Failed to unload world %s", worldName));
                    return true;
                }
                deleteDirectory(folder);
                sender.sendMessage(String.format("World %s removed", worldName));
            } else {
                sender.sendMessage(String.format("World %s does not exists", worldName));
            }
        } else if ("start".equalsIgnoreCase(subcmd)) {
            if (args.length != 6) {
                sender.sendMessage("Usage: /updaq start <world_name> <center_x> <center_z> <radius> <tick_per_run>");
                return true;
            }

            if (inProgressTask != null && !inProgressTask.isCancelled()) {
                sender.sendMessage("Another task is running.");
                return true;
            }

            if (getConfig().isConfigurationSection("paused_task")) {
                sender.sendMessage("Another task is paused, try \"/updaq remove_paused\"");
                return true;
            }

            String worldName = args[1];
            int x = Integer.parseInt(args[2]);
            int z = Integer.parseInt(args[3]);
            int r = Integer.parseInt(args[4]);
            long tick = Integer.parseInt(args[5]);
            World targetWorld = Bukkit.getWorld(worldName);
            if (targetWorld == null) {
                sender.sendMessage(String.format("World %s not found", worldName));
                return true;
            }
            World templateWorld = Bukkit.createWorld(new WorldCreator(targetWorld.getName() + "_template").copy(targetWorld));

            inProgressTask = new ChunkUpdateTask(this, templateWorld, targetWorld, x, z, r);
            inProgressTask.logB("[Start] Update a chunk every %d ticks", tick);
            inProgressTask.runTaskTimer(this, 20L, tick);
        } else if ("pause".equalsIgnoreCase(subcmd)) {
            if (inProgressTask != null && !inProgressTask.isCancelled()) {
                inProgressTask.logB("[Paused]");
                inProgressTask.cancel();
                inProgressTask.pauseToConfigSection(getConfig().createSection("paused_task"));
                saveConfig();
                inProgressTask = null;
            } else {
                sender.sendMessage("No running task");
            }
        } else if ("resume".equalsIgnoreCase(subcmd)) {
            if (args.length != 2) {
                sender.sendMessage("Usage: /updaq resume <tick_per_run>");
                return true;
            }

            if (!getConfig().isConfigurationSection("paused_task")) {
                sender.sendMessage("No paused task.");
                return true;
            }

            if (inProgressTask != null && !inProgressTask.isCancelled()) {
                sender.sendMessage("Another task is running.");
                return true;
            }

            long tick = Integer.parseInt(args[1]);
            try {
                inProgressTask = ChunkUpdateTask.resumeFromConfigSection(this, getConfig().getConfigurationSection("paused_task"));
            } catch (RuntimeException ex) {
                ex.printStackTrace();
                sender.sendMessage(String.format("Cannot resume task: %s", ex.getMessage()));
                return true;
            }
            inProgressTask.logB("[Resume] Update a chunk every %d ticks", tick);
            inProgressTask.runTaskTimer(this, 20L, tick);
        } else if ("remove_paused".equalsIgnoreCase(subcmd)) {
            if (!getConfig().isConfigurationSection("paused_task")) {
                sender.sendMessage("No paused task.");
            } else {
                getConfig().set("paused_task", null);
                sender.sendMessage("Paused task cleared");
            }
        } else {
            sender.sendMessage("Usage: /updaq [...]\nCheck README.md");
        }
        return true;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void playerTeleport(PlayerTeleportEvent ev) {
                if (ev.getTo().getWorld().getName().endsWith("_template")) {
                    ev.setCancelled(true);
                    getLogger().warning(String.format("Player %s tried to moved to world %s, but rejected", ev.getPlayer().getName(), ev.getTo().getWorld().getName()));
                }
            }
        }, this);
    }

    @Override
    public void onDisable() {
        if (inProgressTask != null && !inProgressTask.isCancelled()) {
            inProgressTask.logB("[Paused]");
            inProgressTask.cancel();
            inProgressTask.pauseToConfigSection(getConfig().createSection("paused_task"));
            saveConfig();
            inProgressTask = null;
        }
    }
}
