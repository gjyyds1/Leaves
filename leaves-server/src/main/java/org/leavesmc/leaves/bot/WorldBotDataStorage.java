package org.leavesmc.leaves.bot;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.LeavesConfig;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class WorldBotDataStorage implements IPlayerDataStorage, Listener {

    private static final LevelResource BOT_DATA_DIR = new LevelResource("fakeplayerdata");
    private static final LevelResource BOT_LIST_FILE = new LevelResource("fakeplayer.dat");
    private static final Logger LOGGER = LogUtils.getLogger();

    private final LevelStorageSource.LevelStorageAccess session;
    private final BotList botList;
    private final Map<UUID, WorldStorage> worldStorages = new HashMap<>();

    public WorldBotDataStorage(LevelStorageSource.@NotNull LevelStorageAccess session, BotList botList) {
        this.session = session;
        this.botList = botList;
        
        // 初始化所有已加载世界的存储
        for (World world : Bukkit.getWorlds()) {
            this.initWorldStorage(world);
        }
        
        // 注册世界加载事件监听器
        Bukkit.getPluginManager().registerEvents(this, Bukkit.getPluginManager().getPlugin("Leaves"));
    }

    private WorldStorage initWorldStorage(World world) {
        UUID worldUUID = world.getUID();
        WorldStorage storage = new WorldStorage(world);
        worldStorages.put(worldUUID, storage);
        return storage;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        WorldStorage storage = initWorldStorage(world);
        
        // 加载该世界的假人
        if (LeavesConfig.modify.fakeplayer.enable && LeavesConfig.modify.fakeplayer.canResident) {
            CompoundTag savedBotList = storage.getSavedBotList().copy();
            for (String realName : savedBotList.getAllKeys()) {
                CompoundTag nbt = savedBotList.getCompound(realName);
                if (nbt.getBoolean("resume")) {
                    this.botList.loadNewBot(realName, this);
                }
            }
        }
    }

    @Override
    public void save(Player player) {
        if (!(player instanceof ServerBot bot)) {
            return;
        }

        // 获取玩家所在世界的存储
        UUID worldUUID = bot.getBukkitEntity().getWorld().getUID();
        WorldStorage storage = worldStorages.get(worldUUID);
        if (storage == null) {
            LOGGER.warn("Failed to save fakeplayer data for {}: world storage not found", player.getScoreboardName());
            return;
        }

        boolean flag = true;
        try {
            CompoundTag nbt = player.saveWithoutId(new CompoundTag());
            // 保存世界UUID信息
            nbt.putLong("WorldUUIDMost", worldUUID.getMostSignificantBits());
            nbt.putLong("WorldUUIDLeast", worldUUID.getLeastSignificantBits());
            
            File file = new File(storage.botDir, player.getStringUUID() + ".dat");

            if (file.exists() && file.isFile()) {
                if (!file.delete()) {
                    throw new IOException("Failed to delete file: " + file);
                }
            }
            if (!file.createNewFile()) {
                throw new IOException("Failed to create nbt file: " + file);
            }
            NbtIo.writeCompressed(nbt, file.toPath());
        } catch (Exception exception) {
            LOGGER.warn("Failed to save fakeplayer data for {}", player.getScoreboardName(), exception);
            flag = false;
        }

        if (flag) {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("name", bot.createState.name());
            nbt.putUUID("uuid", bot.getUUID());
            nbt.putBoolean("resume", bot.resume);
            storage.savedBotList.put(bot.createState.realName(), nbt);
            storage.saveBotList();
        }
    }

    @Override
    public Optional<CompoundTag> load(Player player) {
        // 尝试从所有世界加载假人数据
        for (WorldStorage storage : worldStorages.values()) {
            Optional<CompoundTag> data = storage.load(player.getScoreboardName(), player.getStringUUID());
            if (data.isPresent()) {
                return data;
            }
        }
        return Optional.empty();
    }

    public CompoundTag getSavedBotList() {
        // 合并所有世界的假人列表
        CompoundTag mergedList = new CompoundTag();
        for (WorldStorage storage : worldStorages.values()) {
            CompoundTag worldList = storage.getSavedBotList();
            for (String key : worldList.getAllKeys()) {
                mergedList.put(key, worldList.getCompound(key));
            }
        }
        return mergedList;
    }

    /**
     * 每个世界的假人数据存储
     */
    private class WorldStorage {
        private final File botDir;
        private final File botListFile;
        private CompoundTag savedBotList;
        private final World world;

        public WorldStorage(World world) {
            this.world = world;
            String worldName = world.getName();
            
            // 为每个世界创建单独的存储目录
            this.botDir = new File(session.getLevelPath(BOT_DATA_DIR).toFile(), worldName);
            this.botListFile = new File(session.getLevelPath(BOT_DATA_DIR).toFile(), worldName + "-fakeplayer.dat");
            this.botDir.mkdirs();

            this.savedBotList = new CompoundTag();
            if (this.botListFile.exists() && this.botListFile.isFile()) {
                try {
                    Optional.of(NbtIo.readCompressed(this.botListFile.toPath(), NbtAccounter.unlimitedHeap()))
                            .ifPresent(tag -> this.savedBotList = tag);
                } catch (Exception exception) {
                    LOGGER.warn("Failed to load player data list for world: {}", worldName);
                }
            }
        }

        public Optional<CompoundTag> load(String name, String uuid) {
            File file = new File(this.botDir, uuid + ".dat");

            if (file.exists() && file.isFile()) {
                try {
                    Optional<CompoundTag> optional = Optional.of(NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()));
                    if (!file.delete()) {
                        throw new IOException("Failed to delete fakeplayer data");
                    }
                    this.savedBotList.remove(name);
                    this.saveBotList();
                    return optional;
                } catch (Exception exception) {
                    LOGGER.warn("Failed to load fakeplayer data for {}", name);
                }
            }

            return Optional.empty();
        }

        private void saveBotList() {
            try {
                if (this.botListFile.exists() && this.botListFile.isFile()) {
                    if (!this.botListFile.delete()) {
                        throw new IOException("Failed to delete file: " + this.botListFile);
                    }
                }
                if (!this.botListFile.createNewFile()) {
                    throw new IOException("Failed to create nbt file: " + this.botListFile);
                }
                NbtIo.writeCompressed(this.savedBotList, this.botListFile.toPath());
            } catch (Exception exception) {
                LOGGER.warn("Failed to save player data list for world: {}", world.getName());
            }
        }

        public CompoundTag getSavedBotList() {
            return savedBotList;
        }
    }
}