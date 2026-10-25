package com.machinechunkloader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Reads the work box of BuildCraft machines (Filler, Builder) without compiling against BuildCraft. Looks for a
 * {@code getBox()} method or a {@code box} field whose value has {@code isInitialized()}, {@code min()} and
 * {@code max()}, which is BuildCraft's {@code buildcraft.lib.misc.data.Box}.
 */
final class AreaReader {

    private static final Map<Class<?>, Accessor> CACHE = new ConcurrentHashMap<>();
    private static final Accessor NONE = new Accessor(null, null, null, null);

    private AreaReader() {}

    /** @return {min, max} of the machine's box, or null when it has none or it is not set up yet. */
    static BlockPos[] box(BlockEntity be) {
        Accessor a = CACHE.computeIfAbsent(be.getClass(), AreaReader::find);
        if (a == NONE) return null;
        try {
            Object box = a.getter.invoke(be);
            if (box == null) return null;
            if (a.initialized != null && !(boolean) a.initialized.invoke(box)) return null;
            Object min = a.min.invoke(box);
            Object max = a.max.invoke(box);
            if (min instanceof BlockPos lo && max instanceof BlockPos hi) return new BlockPos[] {lo, hi};
        } catch (Throwable ignored) {
            // A changed BuildCraft class shape just means no area loading for this machine.
        }
        return null;
    }

    private static Accessor find(Class<?> type) {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle getter = null;
        Class<?> boxType = null;
        try {
            Method m = type.getMethod("getBox");
            getter = lookup.unreflect(m);
            boxType = m.getReturnType();
        } catch (ReflectiveOperationException ignored) {
            try {
                Field f = type.getField("box");
                getter = lookup.unreflectGetter(f);
                boxType = f.getType();
            } catch (ReflectiveOperationException ignoredToo) {
                return NONE;
            }
        }
        try {
            MethodHandle min = lookup.findVirtual(boxType, "min", MethodType.methodType(BlockPos.class));
            MethodHandle max = lookup.findVirtual(boxType, "max", MethodType.methodType(BlockPos.class));
            MethodHandle init = null;
            try {
                init = lookup.findVirtual(boxType, "isInitialized", MethodType.methodType(boolean.class));
            } catch (ReflectiveOperationException ignored) {
                // optional
            }
            return new Accessor(getter, init, min, max);
        } catch (ReflectiveOperationException e) {
            return NONE;
        }
    }

    private record Accessor(MethodHandle getter, MethodHandle initialized, MethodHandle min, MethodHandle max) {}
}
