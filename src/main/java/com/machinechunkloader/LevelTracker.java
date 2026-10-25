package com.machinechunkloader;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

/**
 * Everything this mod does for one dimension: which machines it tracks, which chunks it holds and how, and the
 * machine-only ticking of chunks that are loaded but not simulated.
 */
public final class LevelTracker {

    private static final Logger LOG = LogUtils.getLogger();

    private static final byte NET_NONE = 0;
    private static final byte NET_LOAD = 1;
    private static final byte NET_FULL = 2;

    /** Ticks between checks of fully simulated machines, which vanilla ticks for us. */
    private static final int FULL_VALIDATE_INTERVAL = 100;
    /** Ticks between re-reading box machines' boxes, which players can change. */
    private static final int BOX_REFRESH_INTERVAL = 200;
    /** Ticks between rescans of held network chunks, to let go of removed cables. */
    private static final int NETWORK_RESCAN_INTERVAL = 1200;

    final ServerLevel level;
    private final LoaderSavedData data;

    private final Long2ObjectOpenHashMap<Machine> machines = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap loadRefs = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap fullRefs = new Long2IntOpenHashMap();
    private final LongOpenHashSet heldLoad = new LongOpenHashSet();
    private final LongOpenHashSet heldFull = new LongOpenHashSet();
    private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();
    private final LongLinkedOpenHashSet scanQueue = new LongLinkedOpenHashSet();
    private final Map<Block, Byte> blockClass = new IdentityHashMap<>();

    private boolean initialized;
    private long ticks;

    LevelTracker(ServerLevel level) {
        this.level = level;
        this.data = LoaderSavedData.get(level);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Rebuilds every reference from saved data. Runs on the first tick, after NeoForge reinstated its tickets. */
    private void initialize() {
        initialized = true;
        LoaderConfig.Parsed cfg = LoaderConfig.get();
        if (!cfg.enabled) return;
        for (long pos : data.machines.toLongArray()) {
            Machine m = new Machine(pos);
            machines.put(pos, m);
            m.chunks = baseChunks(pos, cfg);
            m.full = !cfg.machineOnly;
            addRefs(m);
        }
        for (long c : data.networkChunks) ref(c, !cfg.machineOnly, 1);
        for (long c : data.fullNetworkChunks) ref(c, true, 1);
        scanQueue.addAll(data.networkChunks);
        scanQueue.addAll(data.fullNetworkChunks);
        if (!machines.isEmpty()) {
            LOG.info("Restored {} machines and {} network chunks in {}", machines.size(),
                    data.networkChunks.size() + data.fullNetworkChunks.size(), level.dimension().identifier());
        }
    }

    /** Lets go of every ticket and rebuilds from saved data with the current config. */
    void rebuild() {
        for (Machine m : machines.values()) removeRefs(m);
        machines.clear();
        loadRefs.clear();
        fullRefs.clear();
        for (long c : heldLoad) dirtyChunks.add(c);
        for (long c : heldFull) dirtyChunks.add(c);
        blockClass.clear();
        initialized = false;
        flushTickets();
    }

    /** Drops all tickets, for level unload or server stop. Saved data stays. */
    void release() {
        machines.clear();
        initialized = false;
        loadRefs.clear();
        fullRefs.clear();
        dirtyChunks.addAll(heldLoad);
        dirtyChunks.addAll(heldFull);
        flushTickets();
    }

    // ------------------------------------------------------------------ discovery hooks

    void onChunkLoaded(LevelChunk chunk) {
        scanQueue.add(chunk.getPos().pack());
    }

    void onBlockPlaced(BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) scanQueue.add(ChunkPos.pack(cx + dx, cz + dz));
        }
    }

    void queueAround(ChunkPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) scanQueue.add(ChunkPos.pack(center.x() + dx, center.z() + dz));
        }
    }

    // ------------------------------------------------------------------ per tick

    void tick() {
        LoaderConfig.Parsed cfg = LoaderConfig.get();
        if (!cfg.enabled) {
            if (!heldLoad.isEmpty() || !heldFull.isEmpty()) release();
            initialized = false;
            return;
        }
        if (!initialized) initialize();
        ticks++;

        if (ticks % cfg.discoveryInterval == 0 && cfg.discoveryRadius > 0) {
            for (ServerPlayer player : level.players()) queueAround(player.chunkPosition(), cfg.discoveryRadius);
        }
        if (ticks % NETWORK_RESCAN_INTERVAL == 0) {
            scanQueue.addAll(data.networkChunks);
            scanQueue.addAll(data.fullNetworkChunks);
        }

        for (int i = 0; i < cfg.scansPerTick && !scanQueue.isEmpty(); i++) scanChunk(scanQueue.removeFirstLong(), cfg);

        tickMachines(cfg);
        flushTickets();
    }

    private void tickMachines(LoaderConfig.Parsed cfg) {
        boolean validateFull = ticks % FULL_VALIDATE_INTERVAL == 0;
        boolean refreshBoxes = ticks % BOX_REFRESH_INTERVAL == 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LongArrayList gone = null;

        for (Machine m : machines.values()) {
            if (m.full && !validateFull && !m.pending) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(BlockPos.getX(m.pos) >> 4, BlockPos.getZ(m.pos) >> 4);
            if (chunk == null) continue;
            cursor.set(m.pos);

            BlockEntity be = m.be;
            if (be == null || be.isRemoved() || be.getLevel() != level) {
                be = m.be = chunk.getBlockEntity(cursor);
                m.tickerState = null;
                m.ticker = null;
            }
            if (be == null || (m.typeId != null && !m.typeId.equals(typeId(be)))) {
                if (gone == null) gone = new LongArrayList();
                gone.add(m.pos);
                continue;
            }
            if (m.pending) {
                if (!resolve(m, be, cfg)) {
                    if (gone == null) gone = new LongArrayList();
                    gone.add(m.pos);
                    continue;
                }
            } else if (refreshBoxes && m.box) {
                updateArea(m, be, cfg);
            }

            if (m.full || m.broken) continue;
            if (level.shouldTickBlocksAt(cursor)) continue; // a player is close: vanilla is ticking it already

            BlockState state = be.getBlockState();
            if (!be.getType().isValid(state)) continue;
            if (state != m.tickerState) {
                m.tickerState = state;
                m.ticker = tickerFor(state, be.getType());
            }
            BlockEntityTicker<BlockEntity> ticker = m.ticker;
            if (ticker == null) continue;
            try {
                ticker.tick(level, be.getBlockPos(), state, be);
            } catch (Throwable t) {
                m.broken = true;
                LOG.error("Machine {} at {} in {} threw while ticking with nobody nearby; it will only run while a player is close until the server restarts",
                        m.typeId, cursor.immutable(), level.dimension().identifier(), t);
            }
        }

        if (gone != null) {
            for (long pos : gone) untrack(pos);
        }
    }

    // ------------------------------------------------------------------ scanning

    private void scanChunk(long chunkPos, LoaderConfig.Parsed cfg) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos));
        if (chunk == null) return;

        for (BlockEntity be : List.copyOf(chunk.getBlockEntities().values())) {
            if (be.isRemoved()) continue;
            long pos = be.getBlockPos().asLong();
            if (machines.containsKey(pos)) continue;
            if (qualifies(be, cfg) != null) track(pos, be, cfg);
        }

        byte net = scanNetwork(chunk, cfg);
        setNetwork(chunkPos, net, cfg);
    }

    private byte scanNetwork(LevelChunk chunk, LoaderConfig.Parsed cfg) {
        if (cfg.networkBlocks.isEmpty() && cfg.fullTickBlocks.isEmpty()) return NET_NONE;
        byte found = NET_NONE;
        for (LevelChunkSection section : chunk.getSections()) {
            if (section.hasOnlyAir()) continue;
            if (!section.maybeHas(s -> classify(s.getBlock(), cfg) != NET_NONE)) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        byte c = classify(section.getBlockState(x, y, z).getBlock(), cfg);
                        if (c == NET_FULL) return NET_FULL;
                        if (c > found) found = c;
                    }
                }
            }
        }
        return found;
    }

    private byte classify(Block block, LoaderConfig.Parsed cfg) {
        Byte cached = blockClass.get(block);
        if (cached != null) return cached;
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        byte c = NET_NONE;
        if (id != null) {
            String s = id.toString();
            if (LoaderConfig.matchesAny(cfg.fullTickBlocks, s)) c = NET_FULL;
            else if (LoaderConfig.matchesAny(cfg.networkBlocks, s)) c = NET_LOAD;
        }
        blockClass.put(block, c);
        return c;
    }

    private void setNetwork(long chunkPos, byte net, LoaderConfig.Parsed cfg) {
        boolean wasLoad = data.networkChunks.contains(chunkPos);
        boolean wasFull = data.fullNetworkChunks.contains(chunkPos);
        boolean isLoad = net == NET_LOAD;
        boolean isFull = net == NET_FULL;
        if (wasLoad == isLoad && wasFull == isFull) return;

        if (wasLoad) ref(chunkPos, !cfg.machineOnly, -1);
        if (wasFull) ref(chunkPos, true, -1);
        data.networkChunks.remove(chunkPos);
        data.fullNetworkChunks.remove(chunkPos);
        if (isLoad) {
            data.networkChunks.add(chunkPos);
            ref(chunkPos, !cfg.machineOnly, 1);
        }
        if (isFull) {
            data.fullNetworkChunks.add(chunkPos);
            ref(chunkPos, true, 1);
        }
        data.changed();
    }

    // ------------------------------------------------------------------ machines

    /** @return the block entity's registry id when this mod should keep it running, else null. */
    private String qualifies(BlockEntity be, LoaderConfig.Parsed cfg) {
        Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        if (id == null || !cfg.tracksNamespace(id)) return null;
        String s = id.toString();
        if (LoaderConfig.matchesAny(cfg.excluded, s)) return null;
        // Only machines that actually do something each tick on the server. Pure storage or decoration is skipped.
        if (tickerFor(be.getBlockState(), be.getType()) == null) return null;
        return s;
    }

    private void track(long pos, BlockEntity be, LoaderConfig.Parsed cfg) {
        Machine m = new Machine(pos);
        m.be = be;
        if (!resolve(m, be, cfg)) return;
        machines.put(pos, m);
        if (data.machines.add(pos)) data.changed();
    }

    /** Works out a machine's type, mode and chunks from its block entity and takes its references. */
    private boolean resolve(Machine m, BlockEntity be, LoaderConfig.Parsed cfg) {
        String id = m.pending ? qualifies(be, cfg) : (m.typeId != null ? m.typeId : qualifies(be, cfg));
        if (id == null) return false;
        if (m.chunks != null) removeRefs(m);
        m.pending = false;
        m.typeId = id;
        m.full = !cfg.machineOnly || LoaderConfig.matchesAny(cfg.fullTickMachines, id);
        m.box = cfg.boxMachines.contains(id);
        m.chunks = computeChunks(m, be, cfg);
        addRefs(m);
        return true;
    }

    private void updateArea(Machine m, BlockEntity be, LoaderConfig.Parsed cfg) {
        long[] next = computeChunks(m, be, cfg);
        if (sameChunks(next, m.chunks)) return;
        removeRefs(m);
        m.chunks = next;
        addRefs(m);
    }

    private void untrack(long pos) {
        Machine m = machines.remove(pos);
        if (m != null) removeRefs(m);
        if (data.machines.remove(pos)) data.changed();
    }

    private long[] computeChunks(Machine m, BlockEntity be, LoaderConfig.Parsed cfg) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (long c : baseChunks(m.pos, cfg)) set.add(c);

        int cx = BlockPos.getX(m.pos) >> 4;
        int cz = BlockPos.getZ(m.pos) >> 4;
        int r = cfg.areaRadius.getOrDefault(m.typeId, 0);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) set.add(ChunkPos.pack(cx + dx, cz + dz));
        }

        if (m.box) {
            BlockPos[] box = AreaReader.box(be);
            if (box != null) {
                int minX = box[0].getX() >> 4, maxX = box[1].getX() >> 4;
                int minZ = box[0].getZ() >> 4, maxZ = box[1].getZ() >> 4;
                long count = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
                if (count > cfg.maxBoxChunks) {
                    LOG.warn("{} at {} has a box of {} chunks, over the maxBoxChunks limit of {}; only its own chunks are loaded",
                            m.typeId, BlockPos.of(m.pos), count, cfg.maxBoxChunks);
                } else {
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) set.add(ChunkPos.pack(x, z));
                    }
                }
            }
        }
        return set.toLongArray();
    }

    /** The machine's chunk plus any chunk within the edge margin, for multiblocks and border-crossing pipes. */
    private static long[] baseChunks(long pos, LoaderConfig.Parsed cfg) {
        int x = BlockPos.getX(pos), z = BlockPos.getZ(pos), e = cfg.edgeMargin;
        int minX = (x - e) >> 4, maxX = (x + e) >> 4, minZ = (z - e) >> 4, maxZ = (z + e) >> 4;
        long[] out = new long[(maxX - minX + 1) * (maxZ - minZ + 1)];
        int i = 0;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) out[i++] = ChunkPos.pack(cx, cz);
        }
        return out;
    }

    private static boolean sameChunks(long[] a, long[] b) {
        if (a.length != b.length) return false;
        LongOpenHashSet set = new LongOpenHashSet(a);
        for (long c : b) {
            if (!set.contains(c)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ tickets

    private void addRefs(Machine m) {
        for (long c : m.chunks) ref(c, m.full, 1);
    }

    private void removeRefs(Machine m) {
        if (m.chunks == null) return;
        for (long c : m.chunks) ref(c, m.full, -1);
    }

    private void ref(long chunk, boolean full, int delta) {
        Long2IntOpenHashMap refs = full ? fullRefs : loadRefs;
        int now = refs.addTo(chunk, delta) + delta;
        if (now <= 0) refs.remove(chunk);
        dirtyChunks.add(chunk);
    }

    /** Applies ticket changes for every chunk whose references changed since the last flush. */
    private void flushTickets() {
        if (dirtyChunks.isEmpty()) return;
        for (long c : dirtyChunks) {
            boolean wantFull = fullRefs.get(c) > 0;
            boolean wantLoad = wantFull || loadRefs.get(c) > 0;
            int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);

            if (wantLoad && heldLoad.add(c)) {
                level.getChunkSource().addTicketWithRadius(MachineChunkLoader.LOAD_TICKET.get(), new ChunkPos(x, z), 0);
            } else if (!wantLoad && heldLoad.remove(c)) {
                level.getChunkSource().removeTicketWithRadius(MachineChunkLoader.LOAD_TICKET.get(), new ChunkPos(x, z), 0);
            }

            if (wantFull && heldFull.add(c)) {
                MachineChunkLoader.CONTROLLER.forceChunk(level, MachineChunkLoader.TICKET_OWNER, x, z, true, false);
            } else if (!wantFull && heldFull.remove(c)) {
                MachineChunkLoader.CONTROLLER.forceChunk(level, MachineChunkLoader.TICKET_OWNER, x, z, false, false);
            }
        }
        dirtyChunks.clear();
    }

    // ------------------------------------------------------------------ helpers and reporting

    @SuppressWarnings("unchecked")
    private BlockEntityTicker<BlockEntity> tickerFor(BlockState state, BlockEntityType<?> type) {
        return state.getTicker(level, (BlockEntityType<BlockEntity>) type);
    }

    private static String typeId(BlockEntity be) {
        Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return id == null ? null : id.toString();
    }

    public int machineCount() {
        return machines.size();
    }

    public int machineOnlyCount() {
        int n = 0;
        for (Machine m : machines.values()) {
            if (!m.full) n++;
        }
        return n;
    }

    public int brokenCount() {
        int n = 0;
        for (Machine m : machines.values()) {
            if (m.broken) n++;
        }
        return n;
    }

    public int heldLoadOnly() {
        int n = 0;
        for (long c : heldLoad) {
            if (!heldFull.contains(c)) n++;
        }
        return n;
    }

    public int heldFull() {
        return heldFull.size();
    }

    public int networkChunkCount() {
        return data.networkChunks.size() + data.fullNetworkChunks.size();
    }

    public Collection<Machine> machines() {
        List<Machine> out = new ArrayList<>(machines.values());
        out.sort((a, b) -> Long.compare(a.pos, b.pos));
        return out;
    }

    public static final class Machine {
        public final long pos;
        public String typeId;
        public boolean full;
        public boolean broken;
        boolean box;
        /** Restored from save data and not yet matched with its block entity. */
        boolean pending;
        long[] chunks;
        BlockEntity be;
        BlockState tickerState;
        BlockEntityTicker<BlockEntity> ticker;

        Machine(long pos) {
            this.pos = pos;
            this.pending = true;
        }

        public int chunkCount() {
            return chunks == null ? 0 : chunks.length;
        }
    }
}
