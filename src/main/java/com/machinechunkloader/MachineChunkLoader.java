package com.machinechunkloader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(MachineChunkLoader.MOD_ID)
public final class MachineChunkLoader {

    public static final String MOD_ID = "machinechunkloader";

    private static final DeferredRegister<TicketType> TICKET_TYPES = DeferredRegister.create(Registries.TICKET_TYPE, MOD_ID);

    /**
     * Keeps a chunk fully loaded without simulating it: no entity ticks, random ticks or block-entity ticks from
     * vanilla. This mod ticks the machines in it instead. Not persisted; rebuilt from {@link LoaderSavedData}.
     */
    public static final DeferredHolder<TicketType, TicketType> LOAD_TICKET = TICKET_TYPES.register("machine_load",
            () -> new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    /** Normal force-loading for chunks that need full simulation. */
    public static final TicketController CONTROLLER = new TicketController(
            Identifier.fromNamespaceAndPath(MOD_ID, "full"),
            // Drop whatever was persisted; trackers rebuild their tickets from saved data on their first tick.
            (level, helper) -> List.copyOf(helper.getBlockTickets().keySet()).forEach(helper::removeAllTickets));

    /** All of this mod's forced tickets share one owner; the tracker counts references itself. */
    public static final BlockPos TICKET_OWNER = BlockPos.ZERO;

    private static final Map<ResourceKey<Level>, LevelTracker> TRACKERS = new ConcurrentHashMap<>();

    public MachineChunkLoader(IEventBus modBus, ModContainer container) {
        TICKET_TYPES.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, LoaderConfig.SPEC);
        modBus.addListener((RegisterTicketControllersEvent e) -> e.register(CONTROLLER));
        modBus.addListener((ModConfigEvent.Loading e) -> onConfig(e.getConfig()));
        modBus.addListener((ModConfigEvent.Reloading e) -> onConfig(e.getConfig()));

        NeoForge.EVENT_BUS.addListener((LevelEvent.Load e) -> {
            if (e.getLevel() instanceof ServerLevel level) TRACKERS.put(level.dimension(), new LevelTracker(level));
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload e) -> {
            if (e.getLevel() instanceof ServerLevel level) {
                LevelTracker t = TRACKERS.remove(level.dimension());
                if (t != null) t.release();
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> TRACKERS.clear());
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post e) -> {
            if (e.getLevel() instanceof ServerLevel level) {
                LevelTracker t = TRACKERS.get(level.dimension());
                if (t != null && t.level == level) t.tick();
            }
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load e) -> {
            if (!e.isNewChunk() && e.getLevel() instanceof ServerLevel level) {
                LevelTracker t = TRACKERS.get(level.dimension());
                if (t != null) t.onChunkLoaded(e.getChunk());
            }
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (e.getLevel() instanceof ServerLevel level) {
                LevelTracker t = TRACKERS.get(level.dimension());
                if (t != null) t.onBlockPlaced(e.getPos());
            }
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> LoaderCommands.register(e.getDispatcher()));
    }

    private static void onConfig(ModConfig config) {
        if (config.getSpec() != LoaderConfig.SPEC) return;
        LoaderConfig.invalidate();
        for (LevelTracker t : TRACKERS.values()) t.rebuild();
    }

    static LevelTracker tracker(ServerLevel level) {
        return TRACKERS.get(level.dimension());
    }

    static Collection<LevelTracker> trackers() {
        return new ArrayList<>(TRACKERS.values());
    }
}
