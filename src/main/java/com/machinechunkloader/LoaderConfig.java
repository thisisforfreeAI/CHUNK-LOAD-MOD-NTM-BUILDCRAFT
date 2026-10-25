package com.machinechunkloader;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-wide settings, stored in config/machinechunkloader-common.toml. */
public final class LoaderConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue MACHINE_ONLY;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NAMESPACES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_MACHINES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FULL_TICK_MACHINES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> AREA_MACHINES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BOX_MACHINES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NETWORK_BLOCKS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FULL_TICK_BLOCKS;
    public static final ModConfigSpec.IntValue EDGE_MARGIN;
    public static final ModConfigSpec.IntValue MAX_BOX_CHUNKS;
    public static final ModConfigSpec.IntValue DISCOVERY_RADIUS;
    public static final ModConfigSpec.IntValue DISCOVERY_INTERVAL;
    public static final ModConfigSpec.IntValue SCANS_PER_TICK;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        ENABLED = b.comment("Master switch. When false, no chunks are held and no machines are ticked by this mod.")
                .define("enabled", true);
        MACHINE_ONLY = b.comment(
                        "true: machine chunks are kept in memory but not simulated; this mod ticks only the machines and pipes.",
                        "     Mobs, crops, redstone, flowing liquids, dropped items and random ticks in those chunks stay frozen.",
                        "false: every machine chunk is fully force-loaded like a normal chunk loader (much heavier).")
                .define("machineOnlyMode", true);
        NAMESPACES = b.comment("Mods whose machines are kept running, by registry namespace.")
                .defineListAllowEmpty("modNamespaces",
                        List.of("hbm", "hbmspace", "buildcraftunofficial"), () -> "", LoaderConfig::isString);
        b.pop();

        b.push("machines");
        EXCLUDED_MACHINES = b.comment(
                        "Block entity ids (regular expressions) that are never kept loaded.",
                        "Covers world-generated blocks so exploring does not leave chunks loaded forever.")
                .defineListAllowEmpty("excluded", List.of(
                        "hbm:(geysir|fissure|vent|dungeon_spawner|glyphid_spawner|volcano_core|emitter|partemitter)",
                        "hbm:(trapped_brick|crashed_balefire|landmine|loot|bedrock_ore|supply_crate|crate_hot)",
                        "hbm:(pink_cloud_broadcaster|floodlight_beam|zirnox_destroyed|launchpad_rusted|turret_.*_damaged)",
                        "hbm:(nuke.*|bombmulti|explosive_charge|firework_box|test_block_entity|objtester)",
                        "hbm:(deco_f|lantern_.*|bobblehead|snowglobe|plushie|skeleton|door)",
                        "hbmspace:(geysir|geysir_dcm|lightning_volcano)",
                        "buildcraftunofficial:(spring_oil|marker_volume|marker_path)"),
                        () -> "", LoaderConfig::isRegex);
        FULL_TICK_MACHINES = b.comment(
                        "Block entity ids (regular expressions) whose chunks are fully simulated instead of machine-only.",
                        "Use this for machines that need entities, dropped items or scheduled block updates around them.")
                .defineListAllowEmpty("fullTicking", List.of(), () -> "", LoaderConfig::isRegex);
        AREA_MACHINES = b.comment(
                        "Machines that work on an area around themselves, as \"blockentity_id=radius_in_chunks\".",
                        "Radius 4 loads a 9x9 chunk square centred on the machine.")
                .defineListAllowEmpty("areaRadius", List.of(
                        "hbm:derrick=4",
                        "hbm:machine_pumpjack=4",
                        "hbm:fracking_tower=4",
                        "hbm:excavator=1",
                        "hbm:mining_laser=2",
                        "buildcraftunofficial:pump=4"),
                        () -> "", LoaderConfig::isAreaEntry);
        BOX_MACHINES = b.comment(
                        "BuildCraft machines that work inside a marked box. Every chunk of the box is loaded.",
                        "The quarry is left out because BuildCraft already loads its frame area itself.")
                .defineListAllowEmpty("boxArea",
                        List.of("buildcraftunofficial:filler", "buildcraftunofficial:builder"),
                        () -> "", LoaderConfig::isString);
        MAX_BOX_CHUNKS = b.comment("Upper limit on chunks loaded for one box machine, as a safety net against huge boxes.")
                .defineInRange("maxBoxChunks", 1024, 1, 65536);
        EDGE_MARGIN = b.comment(
                        "A machine this many blocks or closer to a chunk border also loads the chunk across the border.",
                        "Keeps multiblocks and pipe connections that cross a border working.")
                .defineInRange("edgeMarginBlocks", 6, 0, 15);
        b.pop();

        b.push("networks");
        NETWORK_BLOCKS = b.comment(
                        "Block ids (regular expressions) for cables, pipes and pylons without their own ticking.",
                        "Chunks that contain them are kept loaded so power and fluid networks stay connected.")
                .defineListAllowEmpty("networkBlocks", List.of(
                        "hbm:(red_cable.*|red_wire_coated|cable|cable_.*)",
                        "hbm:(fluid_pipe.*|fluid_duct.*|fluid_valve|fluid_switch|fluid_counter_valve|fluid_pump|pipe_anchor)",
                        "hbm:(red_connector.*|red_pylon.*|substation)"),
                        () -> "", LoaderConfig::isRegex);
        FULL_TICK_BLOCKS = b.comment(
                        "Block ids (regular expressions) whose chunks must be fully simulated.",
                        "NTM conveyors move items as entities, which only move in fully simulated chunks.")
                .defineListAllowEmpty("fullTickingBlocks", List.of("hbm:conveyor.*"), () -> "", LoaderConfig::isRegex);
        b.pop();

        b.push("discovery");
        DISCOVERY_RADIUS = b.comment("Chunks around each player that are scanned for new machines.")
                .defineInRange("playerScanRadius", 6, 0, 32);
        DISCOVERY_INTERVAL = b.comment("Ticks between scans around players.")
                .defineInRange("playerScanInterval", 100, 20, 12000);
        SCANS_PER_TICK = b.comment("Most chunks scanned per tick per dimension.")
                .defineInRange("chunkScansPerTick", 32, 1, 1024);
        b.pop();

        SPEC = b.build();
    }

    private LoaderConfig() {}

    private static boolean isString(Object o) {
        return o instanceof String;
    }

    private static boolean isRegex(Object o) {
        if (!(o instanceof String s)) return false;
        try {
            Pattern.compile(s);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private static boolean isAreaEntry(Object o) {
        if (!(o instanceof String s)) return false;
        int eq = s.lastIndexOf('=');
        if (eq <= 0) return false;
        try {
            int r = Integer.parseInt(s.substring(eq + 1).trim());
            return r >= 0 && r <= 16;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---- Parsed views, rebuilt when the config (re)loads ----

    private static volatile Parsed parsed;

    public static Parsed get() {
        Parsed p = parsed;
        if (p == null) parsed = p = new Parsed();
        return p;
    }

    public static void invalidate() {
        parsed = null;
    }

    public static final class Parsed {
        public final boolean enabled = ENABLED.get();
        public final boolean machineOnly = MACHINE_ONLY.get();
        public final Set<String> namespaces = Set.copyOf(NAMESPACES.get());
        public final List<Pattern> excluded = compile(EXCLUDED_MACHINES.get());
        public final List<Pattern> fullTickMachines = compile(FULL_TICK_MACHINES.get());
        public final List<Pattern> networkBlocks = compile(NETWORK_BLOCKS.get());
        public final List<Pattern> fullTickBlocks = compile(FULL_TICK_BLOCKS.get());
        public final Set<String> boxMachines = BOX_MACHINES.get().stream().map(String::trim).collect(Collectors.toUnmodifiableSet());
        public final Object2IntOpenHashMap<String> areaRadius = parseAreas(AREA_MACHINES.get());
        public final int edgeMargin = EDGE_MARGIN.get();
        public final int maxBoxChunks = MAX_BOX_CHUNKS.get();
        public final int discoveryRadius = DISCOVERY_RADIUS.get();
        public final int discoveryInterval = DISCOVERY_INTERVAL.get();
        public final int scansPerTick = SCANS_PER_TICK.get();

        public boolean tracksNamespace(Identifier id) {
            return namespaces.contains(id.getNamespace());
        }

        private static List<Pattern> compile(List<? extends String> list) {
            List<Pattern> out = new ArrayList<>(list.size());
            for (String s : list) out.add(Pattern.compile(s.trim()));
            return List.copyOf(out);
        }

        private static Object2IntOpenHashMap<String> parseAreas(List<? extends String> list) {
            Object2IntOpenHashMap<String> map = new Object2IntOpenHashMap<>();
            for (String s : list) {
                int eq = s.lastIndexOf('=');
                map.put(s.substring(0, eq).trim(), Integer.parseInt(s.substring(eq + 1).trim()));
            }
            return map;
        }
    }

    public static boolean matchesAny(List<Pattern> patterns, String id) {
        for (Pattern p : patterns) {
            if (p.matcher(id).matches()) return true;
        }
        return false;
    }
}
