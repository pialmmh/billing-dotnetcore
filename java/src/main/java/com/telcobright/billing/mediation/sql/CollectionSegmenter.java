// Ported VERBATIM from legacy LibraryExtensions/CollectionSegmenter.cs — the tested batch slicer the CDR
// writers use to write ANY number of rows in fixed-size segments (so one insert never exceeds
// max_allowed_packet). Namespace kept as the legacy LibraryExtensions.
package com.telcobright.billing.mediation.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class CollectionSegmenter<T> {
    //convert enumerable to list to save problems associated with enumerable.skip() with parallel collections
    public final List<T> Enumerable;
    private int SkipFromStart;

    public CollectionSegmenter(Iterable<T> enumerable, int startAtZeroBasedIndex) {
        // The C# guarded `enumerable is ParallelQuery` and threw — there is no LINQ ParallelQuery in Java, so
        // that check has no equivalent; the materialise-to-list (the actual safety it protected) is preserved.
        var list = new ArrayList<T>();
        for (var t : enumerable) list.add(t);
        this.Enumerable = list;
        this.SkipFromStart = startAtZeroBasedIndex;
    }

    public void ExecuteMethodInSegments(int segmentSize, Consumer<List<T>> method) {
        List<T> segment;
        while (!(segment = GetNextSegment(segmentSize)).isEmpty()) {
            method.accept(segment);
        }
    }

    public <TOut> List<TOut> ExecuteMethodInSegmentsWithRetval(int segmentSize, Function<List<T>, List<TOut>> method) {
        List<T> segment;
        List<TOut> retVal = new ArrayList<>();
        while (!(segment = GetNextSegment(segmentSize)).isEmpty()) {
            retVal.addAll(method.apply(segment));
        }
        return retVal;
    }

    public List<T> GetNextSegment(int segmentSize) {
        if (segmentSize <= 0) throw new RuntimeException("Segment size must be >=0");
        int from = Math.min(this.SkipFromStart, this.Enumerable.size());
        int to = Math.min(from + segmentSize, this.Enumerable.size());
        var segment = new ArrayList<>(this.Enumerable.subList(from, to));
        this.SkipFromStart += segmentSize;
        return segment;
    }
}
