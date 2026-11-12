package ua.kpi.pocw.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Blocking baseline builder: lock striping.
 *
 * <p>The term space is split into {@code S} stripes, each a {@link HashMap} guarded by its own
 * {@link ReentrantLock}. A map task groups its batch by stripe and merges each group while holding
 * that stripe's lock, so a thread that finds a stripe locked waits. With {@code S = 1} this is a
 * single global lock. A thread holds at most one stripe lock at a time, so a deadlock is
 * impossible. Used as the comparison point for {@link PartitionedIndexBuilder}.
 */
public final class StripedIndexBuilder implements IndexBuilder {
  private final ReentrantLock[] locks;
  private final HashMap<String, PostingList>[] stripes;
  private final LongAdder lockWaitNanos = new LongAdder();
  private final LongAdder contended = new LongAdder();
  private final LongAdder acquisitions = new LongAdder();

  @SuppressWarnings("unchecked")
  public StripedIndexBuilder(int stripeCount) {
    if (stripeCount < 1) {
      throw new IllegalArgumentException("stripes must be >= 1");
    }
    locks = new ReentrantLock[stripeCount];
    stripes = new HashMap[stripeCount];
    for (int i = 0; i < stripeCount; i++) {
      locks[i] = new ReentrantLock();
      stripes[i] = new HashMap<>();
    }
  }

  @Override
  public void addBatch(Map<String, PostingList> batch) {
    int s = stripes.length;
    @SuppressWarnings("unchecked")
    List<Map.Entry<String, PostingList>>[] groups = new List[s];
    for (Map.Entry<String, PostingList> e : batch.entrySet()) {
      int idx = InvertedIndex.partitionOf(e.getKey(), s);
      if (groups[idx] == null) {
        groups[idx] = new ArrayList<>();
      }
      groups[idx].add(e);
    }
    for (int i = 0; i < s; i++) {
      if (groups[i] == null) {
        continue;
      }
      ReentrantLock lock = locks[i];
      acquisitions.increment();
      if (!lock.tryLock()) {
        contended.increment();
        long t0 = System.nanoTime();
        lock.lock();
        lockWaitNanos.add(System.nanoTime() - t0);
      }
      try {
        HashMap<String, PostingList> target = stripes[i];
        for (Map.Entry<String, PostingList> e : groups[i]) {
          PostingList existing = target.putIfAbsent(e.getKey(), e.getValue());
          if (existing != null) {
            existing.addAll(e.getValue());
          }
        }
      } finally {
        lock.unlock();
      }
    }
  }

  @Override
  public InvertedIndex finish(ThreadPool pool, DocumentTable docs)
      throws InterruptedException, ExecutionException {
    @SuppressWarnings("unchecked")
    Map<String, long[]>[] result = new Map[stripes.length];
    List<Future<?>> futures = new ArrayList<>(stripes.length);
    for (int i = 0; i < stripes.length; i++) {
      final int idx = i;
      futures.add(
          pool.submit(
              () -> {
                locks[idx].lock(); // establishes happens-before with the last writer
                try {
                  HashMap<String, PostingList> stripe = stripes[idx];
                  HashMap<String, long[]> frozen =
                      new HashMap<>((int) (stripe.size() / 0.75f) + 1);
                  for (Map.Entry<String, PostingList> e : stripe.entrySet()) {
                    frozen.put(e.getKey(), e.getValue().toSortedArray());
                  }
                  stripe.clear();
                  result[idx] = frozen;
                } finally {
                  locks[idx].unlock();
                }
              }));
    }
    for (Future<?> f : futures) {
      f.get();
    }
    return new InvertedIndex(result, docs);
  }

  @Override
  public String name() {
    return "striped";
  }

  @Override
  public Map<String, Object> stats() {
    Map<String, Object> m = new HashMap<>();
    m.put("lock_acquisitions", acquisitions.sum());
    m.put("lock_contended", contended.sum());
    m.put("lock_wait_ms", lockWaitNanos.sum() / 1_000_000);
    return m;
  }
}
