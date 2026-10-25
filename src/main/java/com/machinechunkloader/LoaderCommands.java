package com.machinechunkloader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /machineloader stats} for every dimension, {@code list [page]} for the current one, and
 * {@code rescan [radius]} to look for machines around the caller right away.
 */
final class LoaderCommands {

    private static final int PAGE_SIZE = 15;

    private LoaderCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("machineloader")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("stats").executes(LoaderCommands::stats))
                .then(Commands.literal("list")
                        .executes(c -> list(c, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(c -> list(c, IntegerArgumentType.getInteger(c, "page")))))
                .then(Commands.literal("rescan")
                        .executes(c -> rescan(c, 8))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 32))
                                .executes(c -> rescan(c, IntegerArgumentType.getInteger(c, "radius"))))));
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        LoaderConfig.Parsed cfg = LoaderConfig.get();
        src.sendSuccess(() -> Component.literal("Machine Chunk Loader: " + (cfg.enabled ? "on" : "off")
                + ", " + (cfg.machineOnly ? "machine-only mode" : "full chunk mode")), false);
        int total = 0;
        for (LevelTracker t : MachineChunkLoader.trackers()) {
            if (t.machineCount() == 0 && t.networkChunkCount() == 0) continue;
            total += t.machineCount();
            String line = t.level.dimension().identifier() + ": " + t.machineCount() + " machines ("
                    + t.machineOnlyCount() + " machine-only), " + t.networkChunkCount() + " network chunks, "
                    + t.heldLoadOnly() + " chunks held idle, " + t.heldFull() + " fully simulated"
                    + (t.brokenCount() > 0 ? ", " + t.brokenCount() + " stopped after an error" : "");
            src.sendSuccess(() -> Component.literal(line), false);
        }
        if (total == 0) src.sendSuccess(() -> Component.literal("No machines tracked yet."), false);
        return total;
    }

    private static int list(CommandContext<CommandSourceStack> c, int page) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        LevelTracker t = MachineChunkLoader.tracker(level);
        if (t == null || t.machineCount() == 0) {
            src.sendSuccess(() -> Component.literal("No machines tracked in " + level.dimension().identifier()), false);
            return 0;
        }
        var all = t.machines().toArray(new LevelTracker.Machine[0]);
        int pages = (all.length + PAGE_SIZE - 1) / PAGE_SIZE;
        int p = Math.min(page, pages);
        src.sendSuccess(() -> Component.literal("Machines in " + level.dimension().identifier() + ", page " + p + "/" + pages), false);
        for (int i = (p - 1) * PAGE_SIZE; i < Math.min(all.length, p * PAGE_SIZE); i++) {
            LevelTracker.Machine m = all[i];
            BlockPos pos = BlockPos.of(m.pos);
            String line = pos.getX() + " " + pos.getY() + " " + pos.getZ() + "  " + (m.typeId == null ? "(waiting for chunk)" : m.typeId)
                    + "  " + m.chunkCount() + " chunks, " + (m.full ? "full" : "machine-only") + (m.broken ? ", stopped after an error" : "");
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return all.length;
    }

    private static int rescan(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        LevelTracker t = MachineChunkLoader.tracker(src.getLevel());
        if (t == null) return 0;
        Vec3 p = src.getPosition();
        t.queueAround(new ChunkPos(((int) Math.floor(p.x)) >> 4, ((int) Math.floor(p.z)) >> 4), radius);
        int n = (radius * 2 + 1) * (radius * 2 + 1);
        src.sendSuccess(() -> Component.literal("Scanning " + n + " chunks around you for machines."), true);
        return n;
    }
}
