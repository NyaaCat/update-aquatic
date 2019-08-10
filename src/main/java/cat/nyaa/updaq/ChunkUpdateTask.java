package cat.nyaa.updaq;

import net.minecraft.server.v1_13_R2.BlockPosition;
import net.minecraft.server.v1_13_R2.NBTTagCompound;
import net.minecraft.server.v1_13_R2.TileEntity;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_13_R2.CraftChunk;
import org.bukkit.craftbukkit.v1_13_R2.CraftWorld;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.bukkit.block.Biome.*;

public class ChunkUpdateTask extends BukkitRunnable {
    public static final Set<Biome> AQUATIC_BIOMES = new HashSet<Biome>() {{
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

        add(RIVER);
        add(FROZEN_RIVER);

        add(BEACH);
        add(SNOWY_BEACH);
        add(STONE_SHORE);
    }};

    public final Plugin plugin;
    public final CraftWorld targetWorld;
    public final CraftWorld templateWorld;
    public final int startChunkX;
    public final int startChunkZ;
    public final int radius;

    private boolean firstChunkVisited = false;
    private int currentRadius = 0;
    private int currentOffsetX = 0;
    private int currentOffsetZ = 0;

    public ChunkUpdateTask(Plugin plugin, World templateWorld, World targetWorld, int startChunkX, int startChunkZ, int radius) {
        this.targetWorld = (CraftWorld) targetWorld;
        this.templateWorld = (CraftWorld) templateWorld;
        this.startChunkX = startChunkX;
        this.startChunkZ = startChunkZ;
        this.radius = radius;
        this.plugin = plugin;
        runTaskTimer(plugin, 20L, 5L);
    }

    private boolean stepChunkCoord() {
        int r = currentRadius, x = currentOffsetX, z = currentOffsetZ;
        if (!firstChunkVisited) {
            currentRadius = 0;
            currentOffsetX = 0;
            currentOffsetZ = 0;
            firstChunkVisited = true;
        } else if (currentOffsetX == currentRadius) {
            if (currentOffsetZ == 0) {
                currentOffsetZ++;
                currentOffsetX++;
                currentRadius++;
            } else if (currentOffsetZ == currentRadius) {
                currentOffsetX--;
            } else {
                currentOffsetZ++;
            }
        } else if (currentOffsetX == -currentRadius) {
            if (currentOffsetZ == -currentRadius) {
                currentOffsetX++;
            } else {
                currentOffsetZ--;
            }
        } else if (currentOffsetZ == currentRadius) {
            currentOffsetX--;
        } else if (currentOffsetZ == -currentRadius) {
            currentOffsetX++;
        } else {
            throw new RuntimeException("Invalid offset");
        }
        // System.out.println(String.format("(%d,%d,%d)->(%d,%d,%d)", x, z, r, currentOffsetX, currentOffsetZ, currentRadius));
        return currentRadius < radius;
    }

    @Override
    public void run() {
        boolean inRange = stepChunkCoord();
        if (!inRange) {
            cancel();
            return;
        }
        int x = startChunkX + currentOffsetX;
        int z = startChunkZ + currentOffsetZ;
        if (targetWorld.isChunkGenerated(x, z)) {
            updateChunk(x, z);
        }
    }

    public void updateChunk(int chunkX, int chunkZ) {
        int stat_biome_not_match = 0;
        int stat_biome_skipped = 0;
        int stat_biome_changed = 0;
        int stat_block_changed = 0;
        Instant stat_time_start = Instant.now();

        CraftChunk targetChunk = (CraftChunk) targetWorld.getChunkAt(chunkX, chunkZ);
        CraftChunk templateChunk = (CraftChunk) templateWorld.getChunkAt(chunkX, chunkZ);

        for (int col_x = 0; col_x < 16; col_x += 1) {
            for (int col_z = 0; col_z < 16; col_z += 1) {
                int world_x = chunkX * 16 + col_x;
                int world_z = chunkZ * 16 + col_z;
                Biome target_biome = targetWorld.getBiome(world_x, world_z);
                Biome template_biome = templateWorld.getBiome(world_x, world_z);

                if (AQUATIC_BIOMES.contains(target_biome) != AQUATIC_BIOMES.contains(template_biome)) {
                    stat_biome_not_match++;
                    continue;
                }

                if (!AQUATIC_BIOMES.contains(target_biome)) {
                    stat_biome_skipped++;
                    continue;
                }

                for (int y = 20; y < 64; y++) {
                    Block target_block = targetChunk.getBlock(col_x, y, col_z);
                    Block template_block = templateChunk.getBlock(col_x, y, col_z);
                    if (target_block.getType() == Material.WATER && template_block.getType() != Material.WATER) {
                        copyBlock(template_block, target_block);
                        stat_block_changed++;
                    }
                }

                if (target_biome != template_biome) {
                    targetChunk.getWorld().setBiome(world_x, world_z, template_biome);
                    stat_biome_changed++;
                }
            }
        }

        Instant stat_time_end = Instant.now();
        Duration stat_time_duration = Duration.between(stat_time_start, stat_time_end);

        plugin.getLogger().info(String.format(
                "Updated chunk at (%s,%d,%d).\n" +
                        " Completed in %.2fms\n" +
                        " Biome not match: %d\n" +
                        " Biome skipped: %d\n" +
                        " Biome changed: %d\n" +
                        " Block changed: %d",
                targetWorld.getName(), chunkX, chunkZ,
                (double) (stat_time_duration.toNanos()) / 1000000D,
                stat_biome_not_match,
                stat_biome_skipped,
                stat_biome_changed,
                stat_block_changed
        ));

    }

    private void copyBlock(Block from, Block to) {
        to.setType(from.getType(), false);
        to.setBlockData(from.getBlockData().clone(), false);

        CraftWorld worldFrom = (CraftWorld) from.getWorld();
        BlockPosition positionFrom = new BlockPosition(from.getX(), from.getY(), from.getZ());
        TileEntity tileEntityFrom = worldFrom.getHandle().getTileEntity(positionFrom);
        if (tileEntityFrom != null) {

            NBTTagCompound tag = new NBTTagCompound();
            tileEntityFrom.save(tag);
            TileEntity tileEntityTo = TileEntity.create(tag);
            ((CraftWorld) to.getWorld()).getHandle().setTileEntity(new BlockPosition(to.getX(), to.getY(), to.getZ()), tileEntityTo);
            tileEntityTo.update();
        } else {
            to.getState().update(true);
        }
    }
}
