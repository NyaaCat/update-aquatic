package cat.nyaa.updaq;

import net.minecraft.server.v1_13_R2.BlockPosition;
import net.minecraft.server.v1_13_R2.NBTTagCompound;
import net.minecraft.server.v1_13_R2.TileEntity;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.v1_13_R2.CraftChunk;
import org.bukkit.craftbukkit.v1_13_R2.CraftWorld;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.bukkit.block.Biome.*;

public class ChunkUpdateTask extends BukkitRunnable {
    public static final long MAX_LOADED_CHUNK = 32 * 32 * 32;

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

        add(DESERT_LAKES);
    }};

    public final Plugin plugin;
    public final CraftWorld targetWorld;
    public final CraftWorld templateWorld;
    public final int startChunkX;
    public final int startChunkZ;
    public final int radius;
    public final int levelSeaSurface;
    public final int levelDeepestSea;
    public final int levelHighestIceberg;
    public final FileWriter logWriter;

    private boolean firstChunkVisited = false;
    private int currentRadius = 0;
    private int currentOffsetX = 0;
    private int currentOffsetZ = 0;

    private long stat_total_chunk;
    private long stat_complete_chunk = 0;
    private long stat_skipped_chunk = 0;
    private double stat_mavg_chunk_time_ms = 50.0;

    public ChunkUpdateTask(Plugin plugin, String targetWorldName,
                           int startChunkX, int startChunkZ, int radius,
                           boolean firstChunkVisited, int currentOffsetX, int currentOffsetZ,
                           long completeChunk, long skippedChunk) {
        this.plugin = plugin;
        this.targetWorld = (CraftWorld) Bukkit.getWorld(targetWorldName);
        this.templateWorld = (CraftWorld) Bukkit.createWorld(new WorldCreator(targetWorld.getName() + "_template").copy(targetWorld));
        if (targetWorld == null || templateWorld == null) throw new RuntimeException("Cannot find world");
        if (targetWorld.getSeaLevel() != templateWorld.getSeaLevel())
            throw new RuntimeException("Target/Template world sea level not match");

        this.startChunkX = startChunkX;
        this.startChunkZ = startChunkZ;
        this.radius = radius;
        this.levelSeaSurface = templateWorld.getSeaLevel() - 1;
        this.levelDeepestSea = Math.max(levelSeaSurface - 42, 0);
        this.levelHighestIceberg = Math.min(levelSeaSurface + 42, 255);

        this.firstChunkVisited = firstChunkVisited;
        this.currentRadius = Math.max(Math.abs(currentOffsetX), Math.abs(currentOffsetZ));
        this.currentOffsetX = currentOffsetX;
        this.currentOffsetZ = currentOffsetZ;

        this.stat_total_chunk = (radius * 2 - 1) * (radius * 2 - 1);
        this.stat_complete_chunk = completeChunk;
        this.stat_skipped_chunk = skippedChunk;

        try {
            this.logWriter = new FileWriter(new File(plugin.getDataFolder(), "chunk_update.log"), true);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to open log file", ex);
        }
    }

    public ChunkUpdateTask(Plugin plugin, World templateWorld, World targetWorld, int startChunkX, int startChunkZ, int radius) {
        if (targetWorld.getSeaLevel() != templateWorld.getSeaLevel())
            throw new IllegalArgumentException("Different sea level");
        this.targetWorld = (CraftWorld) targetWorld;
        this.templateWorld = (CraftWorld) templateWorld;
        this.startChunkX = startChunkX;
        this.startChunkZ = startChunkZ;
        this.radius = radius;
        this.plugin = plugin;
        this.stat_total_chunk = (radius * 2 - 1) * (radius * 2 - 1);
        this.levelSeaSurface = templateWorld.getSeaLevel() - 1;
        this.levelDeepestSea = Math.max(levelSeaSurface - 42, 0);
        this.levelHighestIceberg = Math.min(levelSeaSurface + 42, 255);

        try {
            this.logWriter = new FileWriter(new File(plugin.getDataFolder(), "chunk_update.log"), true);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to open log file", ex);
        }
    }

    public static ChunkUpdateTask resumeFromConfigSection(Plugin plugin, ConfigurationSection cfg) {
        if (!cfg.isString("world_name")) throw new RuntimeException("Missing world_name");
        if (!cfg.isInt("start_chunk_x")) throw new RuntimeException("Missing start_chunk_x");
        if (!cfg.isInt("start_chunk_z")) throw new RuntimeException("Missing start_chunk_z");
        if (!cfg.isInt("radius")) throw new RuntimeException("Missing radius");
        if (!cfg.isBoolean("first_chunk_visited")) throw new RuntimeException("Missing first_chunk_visited");
        if (!cfg.isInt("current_offset_x")) throw new RuntimeException("Missing current_offset_x");
        if (!cfg.isInt("current_offset_z")) throw new RuntimeException("Missing current_offset_z");
        if (!cfg.isInt("stat_complete_chunk")) throw new RuntimeException("Missing stat_complete_chunk");
        if (!cfg.isInt("stat_skipped_chunk")) throw new RuntimeException("Missing stat_skipped_chunk");

        return new ChunkUpdateTask(
                plugin,
                cfg.getString("world_name"),
                cfg.getInt("start_chunk_x"),
                cfg.getInt("start_chunk_z"),
                cfg.getInt("radius"),
                cfg.getBoolean("first_chunk_visited"),
                cfg.getInt("current_offset_x"),
                cfg.getInt("current_offset_z"),
                cfg.getLong("stat_complete_chunk"),
                cfg.getLong("stat_skipped_chunk")
        );
    }

    public void pauseToConfigSection(ConfigurationSection cfg) {
        cfg.set("world_name", targetWorld.getName());
        cfg.set("start_chunk_x", startChunkX);
        cfg.set("start_chunk_z", startChunkZ);
        cfg.set("radius", radius);
        cfg.set("first_chunk_visited", firstChunkVisited);
        cfg.set("current_offset_x", currentOffsetX);
        cfg.set("current_offset_z", currentOffsetZ);
        cfg.set("stat_complete_chunk", (int) stat_complete_chunk);
        cfg.set("stat_skipped_chunk", (int) stat_skipped_chunk);
    }

    @Override
    public synchronized void cancel() throws IllegalStateException {
        super.cancel();
        logB("[Cancelled]");
        try {
            logWriter.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void log(String format, Object... objs) {
        try {
            logWriter.write("[" + ZonedDateTime.now().toString() + "]");
            logWriter.write(String.format(format + "\n", objs));
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    // log both (log file and console)
    public void logB(String format, Object... objs) {
        log(format, objs);
        plugin.getLogger().info(String.format(format, objs));
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
        log("[ChunkCoordStep] (%d,%d,%d)->(%d,%d,%d)", x, z, r, currentOffsetX, currentOffsetZ, currentRadius);
        return currentRadius < radius;
    }

    @Override
    public void run() {
        // skip round if too many chunks loaded
        if (targetWorld.getHandle().getChunkProvider().chunks.size() >= MAX_LOADED_CHUNK) {
            log("[TooManyLoadedChunks]");
            return;
        }

        boolean inRange = stepChunkCoord();
        if (!inRange) {
            logB("Task complete.");
            cancel();
            return;
        }

        int x = startChunkX + currentOffsetX;
        int z = startChunkZ + currentOffsetZ;
        if (targetWorld.isChunkGenerated(x, z)) { // only update generated trunks
            Instant stat_time_start = Instant.now();

            updateChunk(x, z);

            Instant stat_time_end = Instant.now();
            double time_ms = Duration.between(stat_time_start, stat_time_end).toNanos();
            time_ms = time_ms / 1000000D;
            stat_mavg_chunk_time_ms = 0.1 * time_ms + 0.9 * stat_mavg_chunk_time_ms;
            stat_complete_chunk++;
            double progress = (double) (stat_complete_chunk + stat_skipped_chunk) / (double) stat_total_chunk * 100.0;
            logB("[Processed] (%d,%d), %d/%d(%.2f%%) avg%.2fms/chunk",
                    x, z,
                    stat_complete_chunk + stat_skipped_chunk, stat_total_chunk, progress,
                    stat_mavg_chunk_time_ms);
        } else {
            log("[SkipNotGenerated] x:%d, z:%d", x, z);
            stat_skipped_chunk++;
        }
    }

    public void updateChunk(int chunkX, int chunkZ) {
        int stat_biome_not_match = 0;
        int stat_biome_skipped = 0;
        int stat_biome_changed = 0;
        int stat_block_changed = 0;

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

                // update in-water blocks
                for (int y = 20; y <= levelSeaSurface; y++) {
                    Block target_block = targetChunk.getBlock(col_x, y, col_z);
                    Block template_block = templateChunk.getBlock(col_x, y, col_z);
                    if (target_block.getType() == Material.WATER && template_block.getType() != Material.WATER) {
                        copyBlock(template_block, target_block);
                        stat_block_changed++;
                    }
                }

                // update above-sealevel blocks for icebergs
                if (template_biome == FROZEN_OCEAN || template_biome == DEEP_FROZEN_OCEAN) {
                    for (int y = levelSeaSurface + 1; y < levelHighestIceberg; y++) {
                        Block target_block = targetChunk.getBlock(col_x, y, col_z);
                        Block template_block = templateChunk.getBlock(col_x, y, col_z);
                        if (target_block.getType() == Material.AIR) {
                            if (template_block.getType() != Material.AIR) {
                                copyBlock(template_block, target_block);
                                stat_block_changed++;
                            }
                        } else {
                            break;
                        }
                    }
                }

                if (target_biome != template_biome) {
                    targetChunk.getWorld().setBiome(world_x, world_z, template_biome);
                    stat_biome_changed++;
                }
            }
        }

        targetWorld.unloadChunkRequest(chunkX, chunkZ);
        templateWorld.unloadChunkRequest(chunkX, chunkZ);

        log("[ProcessDetail] (%s,%d,%d) biome_not_match:%d, biome_skipped:%d, biome_changed:%d, block_changed:%d",
                targetWorld.getName(), chunkX, chunkZ,
                stat_biome_not_match,
                stat_biome_skipped,
                stat_biome_changed,
                stat_block_changed
        );

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
