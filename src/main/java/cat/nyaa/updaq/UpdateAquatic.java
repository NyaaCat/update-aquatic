package cat.nyaa.updaq;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class UpdateAquatic extends JavaPlugin {

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
        if ("testspiral".equalsIgnoreCase(subcmd)) {
            if (true) {
                sender.sendMessage("This command now does nothing");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("Retry as player");
                return true;
            }
            Player p = (Player) sender;
            Location l = p.getLocation();
            // new ChunkUpdateTask(this, l.getWorld(), l.getBlockX(), l.getBlockZ(), 5);
        } else if ("chunk".equalsIgnoreCase(subcmd)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Retry as player");
                return true;
            }
            int radius = args.length > 1 ? (Integer.parseInt(args[1])) : 1;
            Location l = ((Player) sender).getLocation();
            World targetWorld = l.getWorld();
            World templateWorld = Bukkit.createWorld(new WorldCreator(targetWorld.getName() + "_template").copy(targetWorld));
            new ChunkUpdateTask(this, templateWorld, targetWorld, l.getChunk().getX(), l.getChunk().getZ(), radius);
        } else if ("removetemplateworld".equalsIgnoreCase(subcmd)) {
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
        } else {
            sender.sendMessage("Usage: /updaq <Chunk,TestSpiral,RemoveTemplateWorld> [...]");
        }
        return true;
    }
}
