package ua.kpi.pocw.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Non-blocking index builder: hash partitions with lock-free inboxes and combining owners.
 *
 * <p>Each of the {@code P} partitions owns a private {@link HashMap} and a lock-free inbox ({@link
 * TreiberStack}). A map task splits its batch into per-partition fragments and, for every
 * fragment:
 *
 * <ol>
 *   <li>pushes it into the partition inbox (lock-free CAS);
 *   <li>tries to become the partition owner with {@code busy.compareAndSet(false, true)};
 *   <li>if it succeeds, it drains the inbox (its own fragment and any fragments handed off by
 *       other threads) into the partition map, releases ownership and re-checks the inbox;
 *   <li>if it fails, another thread owns the partition and will merge the fragment, so this
 *       thread moves on immediately instead of waiting (hand-off).
 * </ol>
 *
 * <p>No thread ever blocks waiting for a partition. The re-check after the release closes the
 * window in which a fragment is pushed after the owner's last {@code pop} but before it clears
 * {@code busy}; together with the rule that a pusher always attempts ownership after pushing, no
 * fragment is left in an inbox when all {@code addBatch} calls have returned.
 *
 * <p>Memory visibility: the partition map is touched only by the current owner. Ownership passes
 * through the volatile {@code busy} flag (release on {@code set(false)}, acquire on a successful
 * CAS), so each owner sees all writes of the previous one.
 */
public final class PartitionedIndexBuilder implements IndexBuilder {
  private static final class Fragment {
    final List<String> terms = new ArrayList<>();
    final List<PostingList> lists = new ArrayList<>();
  }

  private static final class Partition {
    final TreiberStack<Fragment> inbox = new TreiberStack<>();
    final AtomicBoolean busy = new AtomicBoolean();
    final HashMap<String, PostingList> terms = new HashMap<>();
  }

  private final Partition[] partitions;
  private final LongAdder fragments = new LongAdder();
  private final LongAdder handoffs = new LongAdder();
  private final LongAdder foreignMerged = new LongAdder();

  public PartitionedIndexBuilder(int partitionCount) {
    if (partitionCount < 1) {
      throw new IllegalArgumentException("partitions must be >= 1");
    }
    partitions = new Partition[partitionCount];
    for (int i = 0; i < partitionCount; i++) {
      partitions[i] = new Partition();
    }
  }

  @Override
  public void addBatch(Map<String, PostingList> batch) {
    int p = partitions.length;
    Fragment[] split = new Fragment[p];
    for (Map.Entry<String, PostingList> e : batch.entrySet()) {
      int idx = InvertedIndex.partitionOf(e.getKey(), p);
      Fragment f = split[idx];
      if (f == null) {
        f = split[idx] = new Fragment();
      }
      f.terms.add(e.getKey());
      f.lists.add(e.getValue());
    }
    for (int i = 0; i < p; i++) {
      if (split[i] != null) {
        fragments.increment();
        Partition part = partitions[i];
        part.inbox.push(split[i]);
        if (!drain(part)) {
          handoffs.increment();
        }
      }
    }
  }

  /** Drains the inbox if ownership can be taken; returns whether this thread merged anything. */
  private boolean drain(Partition part) {
    boolean merged = false;
    while (!part.inbox.isEmpty() && part.busy.compareAndSet(false, true)) {
      try {
        Fragment f;
        int count = 0;
        while ((f = part.inbox.pop()) != null) {
          merge(part.terms, f);
          count++;
        }
        if (count > 1) {
          foreignMerged.add(count - 1);
        }
        merged |= count > 0;
      } finally {
        part.busy.set(false);
      }
    }
    return merged;
  }

  private static void merge(HashMap<String, PostingList> target, Fragment f) {
    for (int i = 0; i < f.terms.size(); i++) {
      PostingList incoming = f.lists.get(i);
      PostingList existing = target.putIfAbsent(f.terms.get(i), incoming);
      if (existing != null) {
        existing.addAll(incoming);
      }
    }
  }

  @Override
  public InvertedIndex finish(ThreadPool pool, DocumentTable docs)
      throws InterruptedException, ExecutionException {
    @SuppressWarnings("unchecked")
    Map<String, long[]>[] result = new Map[partitions.length];
    List<Future<?>> futures = new ArrayList<>(partitions.length);
    for (int i = 0; i < partitions.length; i++) {
      final int idx = i;
      futures.add(
          pool.submit(
              () -> {
                Partition part = partitions[idx];
                drain(part); // defensive: inbox is already empty when all addBatch calls returned
                HashMap<String, long[]> frozen =
                    new HashMap<>((int) (part.terms.size() / 0.75f) + 1);
                for (Map.Entry<String, PostingList> e : part.terms.entrySet()) {
                  frozen.put(e.getKey(), e.getValue().toSortedArray());
                }
                part.terms.clear();
                result[idx] = frozen;
              }));
    }
    for (Future<?> f : futures) {
      f.get();
    }
    return new InvertedIndex(result, docs);
  }

  @Override
  public String name() {
    return "partitioned";
  }

  @Override
  public Map<String, Object> stats() {
    Map<String, Object> m = new HashMap<>();
    m.put("fragments", fragments.sum());
    m.put("handoffs", handoffs.sum());
    m.put("fragments_merged_by_other_thread", foreignMerged.sum());
    return m;
  }
}
