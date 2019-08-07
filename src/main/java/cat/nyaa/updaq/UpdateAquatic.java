package cat.nyaa.updaq;

import net.minecraft.server.v1_13_R2.BlockPosition;
import net.minecraft.server.v1_13_R2.NBTTagCompound;
import net.minecraft.server.v1_13_R2.TileEntity;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_13_R2.CraftChunk;
import org.bukkit.craftbukkit.v1_13_R2.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.bukkit.block.Biome.*;

public class UpdateAquatic extends JavaPlugin {

    public static final Set<Biome> BIOME_OCEANS = new HashSet<Biome>() {{
        add(OCEAN);
        add(FROZEN_OCEAN);
        add(DEEP_OCEAN);
        add(WARM_OCEAN);
        add(LUKEWARM_OCEAN);
        add(COLD_OCEAN);
        add(DEEP_WARM_OCEAN);
        add(DEEP_LUKEWARM_OCEAN);
        add(DEEP_COLD_OCEAN);
        add(DEEP_FROZEN_OCEAN);
    }};

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
        if (!(sender instanceof Player)) {
            sender.sendMessage("Retry as player");
            return true;
        }
        Player p = (Player) sender;
        if (args.length < 1) return false;
        String world_name = args[0];
        String options = args.length >= 2 ? args[1] : "";
        if (world_name == null) return false;
        World w = Bukkit.getWorld(world_name);
        if (w == null) {
            sender.sendMessage("Available worlds:");
            for (World ww : Bukkit.getWorlds()) {
                sender.sendMessage("- " + ww.getName());
            }
            sender.sendMessage(String.format("World \"%s\" does not exists.", world_name));
            return true;
        }

        if (options.contains("RemoveExistingTemplateWorld")) {
            World tmpw = Bukkit.getWorld(world_name + "_template");
            if (tmpw != null) {
                File folder = tmpw.getWorldFolder();
                Bukkit.unloadWorld(tmpw, false);
                deleteDirectory(folder);
            }
        }

        World template_world = Bukkit.createWorld(new WorldCreator(world_name + "_template").copy(w));
        int chunkX = p.getLocation().getChunk().getX();
        int chunkZ = p.getLocation().getChunk().getZ();

        updateColumn(w.getChunkAt(chunkX, chunkZ), template_world.getChunkAt(chunkX, chunkZ), sender);
        return true;
    }

    /* *
     * Be care of the terminology difference:
     * Bukkit call the 16*256*16 area "chunk" but wiki.vg calls it "column"
     * And wiki.vg calls the 16*16*16 area "chunk".
     */
    private void updateColumn(Chunk colTarget, Chunk colTemplate, CommandSender logger) {
        int stat_biome_not_match = 0;
        int stat_biome_skipped = 0;
        int stat_biome_changed = 0;
        int stat_block_changed = 0;
        int stat_block_tileentity = 0;
        Instant stat_time_start = Instant.now();

        CraftWorld nms_world_target = (CraftWorld) colTarget.getWorld();
        CraftWorld nms_world_template = (CraftWorld) colTemplate.getWorld();
        CraftChunk nms_col_target = (CraftChunk) colTarget;
        CraftChunk nms_col_template = (CraftChunk) colTemplate;

        for (int col_x = 0; col_x < 16; col_x += 1) {
            for (int col_z = 0; col_z < 16; col_z += 1) {
                int world_x = colTarget.getX() * 16 + col_x;
                int world_z = colTarget.getZ() * 16 + col_z;
                Biome target_biome = colTarget.getWorld().getBiome(world_x, world_z);
                Biome template_biome = colTemplate.getWorld().getBiome(world_x, world_z);

                if (BIOME_OCEANS.contains(target_biome) != BIOME_OCEANS.contains(template_biome)) {
                    stat_biome_not_match++;
                    continue;
                }

                if (!BIOME_OCEANS.contains(target_biome)) {
                    stat_biome_skipped++;
                    continue;
                }

                for (int y = 20; y < 64; y++) {
                    Block target_block = colTarget.getBlock(col_x, y, col_z);
                    Block template_block = colTemplate.getBlock(col_x, y, col_z);
                    if (target_block.getType() == Material.WATER && template_block.getType() != Material.WATER) {
                        target_block.setType(template_block.getType(), false);
                        target_block.setBlockData(template_block.getBlockData(), false);

                        BlockPosition block_pos = new BlockPosition(world_x, y, world_z);
                        TileEntity te = nms_world_template.getHandle().getTileEntity(block_pos);
                        if (te != null) {
                            setClonedTileEntity(te, nms_world_target, block_pos);
                            stat_block_tileentity++;
                        } else {
                            target_block.getState().update(true);
                        }
                        stat_block_changed++;
                    }
                }

                if (target_biome != template_biome) {
                    colTarget.getWorld().setBiome(world_x, world_z, template_biome);
                    stat_biome_changed++;
                }
            }
        }

        Instant stat_time_end = Instant.now();
        Duration stat_time_duration = Duration.between(stat_time_start, stat_time_end);

        if (logger != null) {
            logger.sendMessage(String.format(
                    "Updated 1 column.\n" +
                            "Completed in %.2fms\n" +
                            "Biome not match: %d\n" +
                            "Biome skipped: %d\n" +
                            "Biome changed: %d\n" +
                            "Block changed: %d\n" +
                            "TileEntity changed: %d",
                    (double) (stat_time_duration.toNanos()) / 1000000D,
                    stat_biome_not_match,
                    stat_biome_skipped,
                    stat_biome_changed,
                    stat_block_changed,
                    stat_block_tileentity
            ));
        }
    }

    private void setClonedTileEntity(TileEntity from, CraftWorld to_world, BlockPosition to_position) {
        NBTTagCompound tag = new NBTTagCompound();
        from.save(tag);
        TileEntity cloned = TileEntity.create(tag);
        to_world.getHandle().setTileEntity(to_position, cloned);
        cloned.update();
    }

    @Override
    public void onLoad() {
        super.onLoad();
    }

    @Override
    public void onEnable() {
        super.onEnable();
    }
}
