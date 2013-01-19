package me.nallar.tickthreading.minecraft;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkMod;
import me.nallar.tickthreading.Log;
import me.nallar.tickthreading.minecraft.commands.TPSCommand;
import me.nallar.tickthreading.minecraft.commands.TicksCommand;
import me.nallar.tickthreading.minecraft.entitylist.EntityList;
import me.nallar.tickthreading.minecraft.entitylist.LoadedEntityList;
import me.nallar.tickthreading.minecraft.entitylist.LoadedTileEntityList;
import me.nallar.tickthreading.patcher.PatchManager;
import me.nallar.tickthreading.util.FieldUtil;
import me.nallar.tickthreading.util.LocationUtil;
import me.nallar.tickthreading.util.PatchUtil;
import me.nallar.tickthreading.util.VersionUtil;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.Configuration;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.Property;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.world.WorldEvent;

@SuppressWarnings ("WeakerAccess")
@Mod (modid = "TickThreading", name = "TickThreading", version = "@MOD_VERSION@")
@NetworkMod (clientSideRequired = false, serverSideRequired = false)
public class TickThreading {
	private static final int loadedEntityFieldIndex = 0;
	private static final int loadedTileEntityFieldIndex = 2;
	public final boolean enabled;
	private int tickThreads = 0;
	private boolean enableEntityTickThreading = true;
	private boolean enableTileEntityTickThreading = true;
	private int regionSize = 16;
	private boolean variableTickRate = true;
	private boolean requirePatched = true;
	public boolean exitOnDeadlock = false;
	final Map<World, TickManager> managers = new WeakHashMap<World, TickManager>();
	private DeadLockDetector deadLockDetector = null;
	public static TickThreading instance;
	public boolean enableChunkTickThreading = true;
	public boolean enableWorldTickThreading = true;
	public boolean requireOpForTicksCommand = true;
	public boolean shouldLoadSpawn = true;
	public int saveInterval = 1800;
	public int deadLockTime = 30;
	public boolean aggressiveTicks = true;
	public boolean enableFastMobSpawning = false;
	private HashSet<Integer> disabledFastMobSpawningDimensions = new HashSet<Integer>();
	private boolean waitForEntityTick = true;
	public int chunkCacheSize = 0;

	public TickThreading() {
		Log.LOGGER.getLevel(); // Force log class to load
		if (requirePatched && PatchManager.shouldPatch(LocationUtil.getJarLocations())) {
			enabled = false;
			try {
				PatchUtil.writePatchRunners();
			} catch (IOException e) {
				Log.severe("Failed to write patch runners", e);
			}
		} else {
			enabled = true;
		}
		instance = this;
	}

	@Mod.Init
	public void init(FMLInitializationEvent event) {
		if (enabled) {
			MinecraftForge.EVENT_BUS.register(this);
		}
	}

	@Mod.PreInit
	public void preInit(FMLPreInitializationEvent event) {
		Configuration config = new Configuration(event.getSuggestedConfigurationFile());
		config.load();
		Property tickThreadsProperty = config.get(Configuration.CATEGORY_GENERAL, "tickThreads", tickThreads);
		tickThreadsProperty.comment = "number of threads to use to tick. 0 = automatic";
		Property enableEntityTickThreadingProperty = config.get(Configuration.CATEGORY_GENERAL, "enableEntityTickThreading", enableEntityTickThreading);
		enableEntityTickThreadingProperty.comment = "Whether entity ticks should be threaded";
		Property enableTileEntityTickThreadingProperty = config.get(Configuration.CATEGORY_GENERAL, "enableTileEntityTickThreading", enableTileEntityTickThreading);
		enableTileEntityTickThreadingProperty.comment = "Whether tile entity ticks should be threaded";
		Property enableChunkTickThreadingProperty = config.get(Configuration.CATEGORY_GENERAL, "enableChunkTickThreading", enableChunkTickThreading);
		enableChunkTickThreadingProperty.comment = "Whether chunk ticks should be threaded";
		Property enableWorldTickThreadingProperty = config.get(Configuration.CATEGORY_GENERAL, "enableWorldTickThreading", enableWorldTickThreading);
		enableWorldTickThreadingProperty.comment = "Whether world ticks should be threaded";
		Property regionSizeProperty = config.get(Configuration.CATEGORY_GENERAL, "regionSize", regionSize);
		regionSizeProperty.comment = "width/length of tick regions, specified in blocks.";
		Property variableTickRateProperty = config.get(Configuration.CATEGORY_GENERAL, "variableRegionTickRate", variableTickRate);
		variableTickRateProperty.comment = "Allows tick rate to vary per region so that each region uses at most 50ms on average per tick.";
		Property ticksCommandName = config.get(Configuration.CATEGORY_GENERAL, "ticksCommandName", TicksCommand.name);
		ticksCommandName.comment = "Name of the command to be used for performance stats. Defaults to ticks.";
		Property tpsCommandName = config.get(Configuration.CATEGORY_GENERAL, "tpsCommandName", TPSCommand.name);
		tpsCommandName.comment = "Name of the command to be used for TPS reports.";
		Property requirePatchedProperty = config.get(Configuration.CATEGORY_GENERAL, "requirePatched", requirePatched);
		requirePatchedProperty.comment = "If the server must be patched to run with TickThreading";
		Property exitOnDeadlockProperty = config.get(Configuration.CATEGORY_GENERAL, "exitOnDeadlock", exitOnDeadlock);
		exitOnDeadlockProperty.comment = "If the server should shut down when a deadlock is detected";
		Property requireOpForTicksCommandProperty = config.get(Configuration.CATEGORY_GENERAL, "requireOpsForTicksCommand", requireOpForTicksCommand);
		requireOpForTicksCommandProperty.comment = "If a player must be opped to use /ticks";
		Property saveIntervalProperty = config.get(Configuration.CATEGORY_GENERAL, "saveInterval", saveInterval);
		saveIntervalProperty.comment = "Time between auto-saves, in ticks.";
		Property deadLockTimeProperty = config.get(Configuration.CATEGORY_GENERAL, "deadLockTime", deadLockTime);
		deadLockTimeProperty.comment = "The time(seconds) of being frozen which will trigger the DeadLockDetector.";
		Property aggressiveTicksProperty = config.get(Configuration.CATEGORY_GENERAL, "aggressiveTicks", aggressiveTicks);
		aggressiveTicksProperty.comment = "If false, will use Spigot tick time algorithm which may lead to lower idle load, but worse TPS if ticks are spiking.";
		Property shouldLoadSpawnProperty = config.get(Configuration.CATEGORY_GENERAL, "shouldLoadSpawn", shouldLoadSpawn);
		shouldLoadSpawnProperty.comment = "Whether chunks within 200 blocks of world spawn points should always be loaded. Recommended to use Forge's dormant chunk cache if this is enabled";
		Property enableFastMobSpawningProperty = config.get(Configuration.CATEGORY_GENERAL, "enableFastMobSpawning", enableFastMobSpawning);
		enableFastMobSpawningProperty.comment = "If enabled, TT's alternative mob spawning implementation will be used. This is experimental!";
		Property disabledFastMobSpawningDimensionsProperty = config.get(Configuration.CATEGORY_GENERAL, "disableFastMobSpawningDimensions", new int[]{-1});
		disabledFastMobSpawningDimensionsProperty.comment = "List of dimensions not to enable fast spawning in.";
		Property waitForEntityTickProperty = config.get(Configuration.CATEGORY_GENERAL, "waitForEntityTick", waitForEntityTick);
		waitForEntityTickProperty.comment = "Whether we should wait until all Tile/Entity tick threads are finished before moving on with world tick. False = experimental, but may improve performance.";
		Property chunkCacheSizeProperty = config.get(Configuration.CATEGORY_GENERAL, "chunkCacheSize", chunkCacheSize);
		chunkCacheSizeProperty.comment = "Number of unloaded chunks to keep cached. Replacement for Forge's dormant chunk cache, which tends to break.";
		config.save();

		TicksCommand.name = ticksCommandName.value;
		TPSCommand.name = tpsCommandName.value;
		tickThreads = tickThreadsProperty.getInt(tickThreads);
		regionSize = regionSizeProperty.getInt(regionSize);
		saveInterval = saveIntervalProperty.getInt(saveInterval);
		deadLockTime = deadLockTimeProperty.getInt(deadLockTime);
		chunkCacheSize = chunkCacheSizeProperty.getInt(chunkCacheSize);
		enableEntityTickThreading = enableEntityTickThreadingProperty.getBoolean(enableEntityTickThreading);
		enableTileEntityTickThreading = enableTileEntityTickThreadingProperty.getBoolean(enableTileEntityTickThreading);
		variableTickRate = variableTickRateProperty.getBoolean(variableTickRate);
		requirePatched = requirePatchedProperty.getBoolean(requirePatched);
		exitOnDeadlock = exitOnDeadlockProperty.getBoolean(exitOnDeadlock);
		enableChunkTickThreading = enableChunkTickThreadingProperty.getBoolean(enableChunkTickThreading);
		enableWorldTickThreading = enableWorldTickThreadingProperty.getBoolean(enableWorldTickThreading);
		enableFastMobSpawning = enableFastMobSpawningProperty.getBoolean(enableFastMobSpawning);
		requireOpForTicksCommand = requireOpForTicksCommandProperty.getBoolean(requireOpForTicksCommand);
		aggressiveTicks = aggressiveTicksProperty.getBoolean(aggressiveTicks);
		shouldLoadSpawn = shouldLoadSpawnProperty.getBoolean(shouldLoadSpawn);
		waitForEntityTick = waitForEntityTickProperty.getBoolean(waitForEntityTick);
		int[] disabledDimensions = disabledFastMobSpawningDimensionsProperty.getIntList();
		disabledFastMobSpawningDimensions = new HashSet<Integer>(disabledDimensions.length);
		for (int disabledDimension : disabledDimensions) {
			disabledFastMobSpawningDimensions.add(disabledDimension);
		}
	}

	@Mod.ServerStarting
	public void serverStarting(FMLServerStartingEvent event) {
		if (enabled) {
			Log.severe(VersionUtil.versionString() + " is installed on this server!"
					+ "\nIf anything breaks, check if it is still broken without TickThreading"
					+ "\nWe don't want to annoy mod devs with issue reports caused by TickThreading."
					+ "\nIf it's only broken with TickThreading, report it at"
					+ " http://github.com/nallar/TickThreading"
					+ "\n\nThe FML invalid fingerprint event has been disabled as some mods break if it is fired"
					+ "\nEG, forestry as an attempt to stop tekkit from including it.");
			ServerCommandManager serverCommandManager = (ServerCommandManager) event.getServer().getCommandManager();
			serverCommandManager.registerCommand(new TicksCommand());
			serverCommandManager.registerCommand(new TPSCommand());
		} else {
			Log.severe("TickThreading is disabled, because your server has not been patched!" +
					"\nTo patch your server, simply run the PATCHME.bat/sh file in your server directory");
		}
	}

	@ForgeSubscribe
	public void onWorldLoad(WorldEvent.Load event) {
		TickManager manager = new TickManager(event.world, regionSize, tickThreads, waitForEntityTick);
		manager.setVariableTickRate(variableTickRate);
		try {
			if (enableTileEntityTickThreading) {
				Field loadedTileEntityField = FieldUtil.getFields(World.class, List.class)[loadedTileEntityFieldIndex];
				new LoadedTileEntityList<TileEntity>(event.world, loadedTileEntityField, manager);
			}
			if (enableEntityTickThreading) {
				Field loadedEntityField = FieldUtil.getFields(World.class, List.class)[loadedEntityFieldIndex];
				new LoadedEntityList<TileEntity>(event.world, loadedEntityField, manager);
			}
			Log.info("Threading initialised for world " + Log.name(event.world));
			managers.put(event.world, manager);
		} catch (Exception e) {
			Log.severe("Failed to initialise threading for world " + Log.name(event.world), e);
		}
		if (deadLockDetector == null) {
			deadLockDetector = new DeadLockDetector(managers);
		}
	}

	@ForgeSubscribe
	public void onWorldUnload(WorldEvent.Unload event) {
		try {
			TickManager tickManager = managers.get(event.world);
			if (tickManager != null) {
				tickManager.unload();
			}
			managers.remove(event.world);
			if (enableTileEntityTickThreading) {
				Field loadedTileEntityField = FieldUtil.getFields(World.class, List.class)[loadedTileEntityFieldIndex];
				Object loadedTileEntityList = loadedTileEntityField.get(event.world);
				if (!(loadedTileEntityList instanceof EntityList)) {
					Log.severe("Looks like another mod broke TickThreaded tile entities in world: " + Log.name(event.world));
				}
			}
			if (enableEntityTickThreading) {
				Field loadedEntityField = FieldUtil.getFields(World.class, List.class)[loadedEntityFieldIndex];
				Object loadedEntityList = loadedEntityField.get(event.world);
				if (!(loadedEntityList instanceof EntityList)) {
					Log.severe("Looks like another mod broke TickThreaded entities in world: " + Log.name(event.world));
				}
			}
		} catch (Exception e) {
			Log.severe("Probable memory leak, failed to unload threading for world " + Log.name(event.world), e);
		}
	}

	public TickManager getManager(World world) {
		return managers.get(world);
	}

	public List<TickManager> getManagers() {
		return new ArrayList<TickManager>(managers.values());
	}

	public boolean shouldFastSpawn(World world) {
		return this.enableFastMobSpawning && !disabledFastMobSpawningDimensions.contains(world.provider.dimensionId);
	}
}
