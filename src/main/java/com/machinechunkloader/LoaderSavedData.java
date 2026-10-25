package com.machinechunkloader;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * What one dimension remembers across restarts: the positions of tracked machines and the chunks that hold network
 * blocks. Tickets are not saved; they are rebuilt from this on load.
 */
public final class LoaderSavedData extends SavedData {

    private static final Codec<LoaderSavedData> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.LONG.listOf().optionalFieldOf("machines", List.of()).forGetter(d -> new LongArrayList(d.machines)),
                    Codec.LONG.listOf().optionalFieldOf("network_chunks", List.of()).forGetter(d -> new LongArrayList(d.networkChunks)),
                    Codec.LONG.listOf().optionalFieldOf("full_network_chunks", List.of()).forGetter(d -> new LongArrayList(d.fullNetworkChunks)))
            .apply(i, LoaderSavedData::new));

    public static final SavedDataType<LoaderSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(MachineChunkLoader.MOD_ID, "tracked"), LoaderSavedData::new, CODEC, null);

    /** Block positions ({@code BlockPos.asLong}) of tracked machines. */
    final LongOpenHashSet machines = new LongOpenHashSet();
    /** Chunk positions ({@code ChunkPos.pack}) holding network blocks that only need loading. */
    final LongOpenHashSet networkChunks = new LongOpenHashSet();
    /** Chunk positions holding network blocks that need full simulation (conveyors). */
    final LongOpenHashSet fullNetworkChunks = new LongOpenHashSet();

    public LoaderSavedData() {}

    private LoaderSavedData(List<Long> machines, List<Long> networkChunks, List<Long> fullNetworkChunks) {
        this.machines.addAll(machines);
        this.networkChunks.addAll(networkChunks);
        this.fullNetworkChunks.addAll(fullNetworkChunks);
    }

    public static LoaderSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    void changed() {
        setDirty();
    }
}
