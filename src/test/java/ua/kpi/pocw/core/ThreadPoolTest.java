package ua.kpi.pocw.core;

import static ua.kpi.pocw.testing.Assert.check;
import static ua.kpi.pocw.testing.Assert.equal;
import static ua.kpi.pocw.testing.Assert.throwsType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ua.kpi.pocw.testing.Test;

class ThreadPoolTest {
  @Test
  void returnsResultsThroughFutures() throws Exception {
    try (ThreadPool pool = new ThreadPool(4, "t")) {
      List<Future<Integer>> fs = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        final int x = i;
        fs.add(pool.submit(() -> x * x));
      }
      int sum = 0;
      for (Future<Integer> f : fs) {
        sum += f.get();
      }
      equal(328350, sum, "sum of squares");
    }
  }

  @Test
  void runsTasksInParallel() throws Exception {
    int n = 4;
    try (ThreadPool pool = new ThreadPool(n, "t")) {
      CountDownLatch allStarted = new CountDownLatch(n);
      List<Future<?>> fs = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        fs.add(pool.submit(() -> {
          allStarted.countDown();
          try {
            // only completes if all n tasks are running at the same time
            check(allStarted.await(5, TimeUnit.SECONDS), "tasks did not run concurrently");
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
        }));
      }
      for (Future<?> f : fs) {
        f.get();
      }
    }
  }

  @Test
  void propagatesExceptionsAndKeepsWorkersAlive() throws Exception {
    try (ThreadPool pool = new ThreadPool(1, "t")) {
      Future<Object> bad = pool.submit(() -> {
        throw new IllegalStateException("boom");
      });
      ExecutionException e = throwsType(ExecutionException.class, bad::get);
      check(e.getCause() instanceof IllegalStateException, "cause preserved");
      equal(7, pool.submit(() -> 7).get(), "worker survived");
    }
  }

  @Test
  void gracefulShutdownDrainsQueueAndRejectsNewTasks() throws Exception {
    AtomicInteger done = new AtomicInteger();
    ThreadPool pool = new ThreadPool(2, "t");
    for (int i = 0; i < 1000; i++) {
      pool.execute(done::incrementAndGet);
    }
    pool.shutdown();
    throwsType(RejectedExecutionException.class, () -> pool.execute(() -> { }));
    check(pool.awaitTermination(5, TimeUnit.SECONDS), "terminated");
    equal(1000, done.get(), "all queued tasks executed");
    equal(1000L, pool.completedTasks(), "completed counter");
  }

  @Test
  void shutdownNowDropsQueuedTasks() throws Exception {
    ThreadPool pool = new ThreadPool(1, "t");
    CountDownLatch block = new CountDownLatch(1);
    pool.execute(() -> {
      try {
        block.await();
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    });
    for (int i = 0; i < 10; i++) {
      pool.execute(() -> { });
    }
    List<Runnable> dropped = pool.shutdownNow();
    check(pool.awaitTermination(5, TimeUnit.SECONDS), "terminated");
    check(dropped.size() >= 9, "queued tasks returned, got " + dropped.size());
  }

  @Test
  void concurrentSubmittersLoseNoTasks() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    try (ThreadPool pool = new ThreadPool(8, "t")) {
      Thread[] producers = new Thread[8];
      for (int p = 0; p < producers.length; p++) {
        producers[p] = new Thread(() -> {
          for (int i = 0; i < 10_000; i++) {
            pool.execute(counter::incrementAndGet);
          }
        });
        producers[p].start();
      }
      for (Thread t : producers) {
        t.join();
      }
    } // close() drains the queue and joins the workers
    equal(80_000, counter.get(), "every submitted task ran exactly once");
  }
}
