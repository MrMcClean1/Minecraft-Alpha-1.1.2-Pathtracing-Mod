package com.alexmcclellan.betapt.mixin;

import net.minecraft.client.render.world.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Comparator;
import java.util.List;

/**
 * Alpha's chunk sorters (which chunk to build or draw first) don't always agree with themselves: the camera moves
 * while they compare. Java 7 and later check that and throw "Comparison method violates its general contract!",
 * which crashed the game. A plain merge sort that doesn't check, as Java 6's did, gives the same order without
 * the crash.
 */
@Mixin(WorldRenderer.class)
public abstract class ChunkSortMixin {
    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Ljava/util/Collections;sort(Ljava/util/List;Ljava/util/Comparator;)V", remap = false), require = 0)
    private static <T> void betapt$sortList(List<T> list, Comparator<? super T> c) {
        @SuppressWarnings("unchecked") T[] a = (T[]) list.toArray();
        mergeSort(a, c);
        for (int i = 0; i < a.length; i++) list.set(i, a[i]);
    }

    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Ljava/util/Arrays;sort([Ljava/lang/Object;Ljava/util/Comparator;)V", remap = false), require = 0)
    private static <T> void betapt$sortArray(T[] a, Comparator<? super T> c) {
        mergeSort(a, c);
    }

    private static <T> void mergeSort(T[] a, Comparator<? super T> c) {
        if (a.length < 2) return;
        T[] tmp = a.clone();
        sort(tmp, a, 0, a.length, c);
    }

    /** Sorts src[lo, hi) into dst[lo, hi) (both start as copies of the same items). */
    private static <T> void sort(T[] src, T[] dst, int lo, int hi, Comparator<? super T> c) {
        if (hi - lo < 8) {
            for (int i = lo + 1; i < hi; i++) {
                T v = dst[i];
                int j = i - 1;
                while (j >= lo && c.compare(dst[j], v) > 0) { dst[j + 1] = dst[j]; j--; }
                dst[j + 1] = v;
            }
            return;
        }
        int mid = (lo + hi) >>> 1;
        sort(dst, src, lo, mid, c);
        sort(dst, src, mid, hi, c);
        for (int i = lo, p = lo, q = mid; i < hi; i++)
            dst[i] = q >= hi || p < mid && c.compare(src[p], src[q]) <= 0 ? src[p++] : src[q++];
    }
}
