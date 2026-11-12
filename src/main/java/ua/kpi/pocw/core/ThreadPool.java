package ua.kpi.pocw.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-size thread pool implemented from scratch.
 *
 * <p>Tasks are kept in an unbounded FIFO queue guarded by a single {@link ReentrantLock}. Idle
 * workers park on the {@code notEmpty} condition, so there is no busy waiting. The state machine
 * is {@code RUNNING -> SHUTDOWN -> TERMINATED}:
 *
 * <ul>
 *   <li>{@link #shutdown()} rejects new tasks but lets workers drain the queue;
 *   <li>{@link #shutdownNow()} also discards queued tasks and interrupts the workers;
 *   <li>{@link #awaitTermination(long, TimeUnit)} waits until every worker has exited.
 * </ul>
 *
 * <p>Invariants: {@code queue} and {@code state} are read and written only while holding {@code
 * lock}; a task is removed from the queue by exactly one worker, so it runs exactly once.
 */
public final class ThreadPool implements AutoCloseable {
  private enum State {
    RUNNING,
    SHUTDOWN,
    TERMINATED
  }

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private final Condition terminated = lock.newCondition();
  private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
  private final List<Thread> workers;
  private final String name;

  private State state = State.RUNNING;
  private int liveWorkers;
  private long completedTasks;
  private int peakQueueSize;

  public ThreadPool(int threads, String name) {
    if (threads < 1) {
      throw new IllegalArgumentException("threads must be >= 1, got " + threads);
    }
    this.name = name;
    this.workers = new ArrayList<>(threads);
    this.liveWorkers = threads;
    for (int i = 0; i < threads; i++) {
      Thread t = new Thread(this::workerLoop, name + "-" + i);
      t.setDaemon(true);
      workers.add(t);
    }
    workers.forEach(Thread::start);
  }

  /** Submits a value-returning task. */
  public <T> Future<T> submit(Callable<T> task) {
    FutureTask<T> future = new FutureTask<>(task);
    execute(future);
    return future;
  }

  /** Submits a task without a result. */
  public Future<?> submit(Runnable task) {
    FutureTask<Void> future = new FutureTask<>(task, null);
    execute(future);
    return future;
  }

  /** Enqueues a task; throws {@link RejectedExecutionException} after shutdown. */
  public void execute(Runnable task) {
    if (task == null) {
      throw new NullPointerException("task");
    }
    lock.lock();
    try {
      if (state != State.RUNNING) {
        throw new RejectedExecutionException("Pool " + name + " is shut down");
      }
      queue.addLast(task);
      peakQueueSize = Math.max(peakQueueSize, queue.size());
      notEmpty.signal();
    } finally {
      lock.unlock();
    }
  }

  private void workerLoop() {
    try {
      while (true) {
        Runnable task;
        lock.lock();
        try {
          while (queue.isEmpty() && state == State.RUNNING) {
            notEmpty.await(); // spurious wake-ups are handled by the loop predicate
          }
          task = queue.pollFirst();
          if (task == null) {
            return; // shut down and nothing left to run
          }
        } finally {
          lock.unlock();
        }
        try {
          task.run(); // FutureTask captures exceptions; a raw Runnable must not kill the worker
        } catch (RuntimeException | Error e) {
          System.err.println("[" + Thread.currentThread().getName() + "] task failed: " + e);
        }
        lock.lock();
        try {
          completedTasks++;
        } finally {
          lock.unlock();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      lock.lock();
      try {
        if (--liveWorkers == 0) {
          state = State.TERMINATED;
          terminated.signalAll();
        }
      } finally {
        lock.unlock();
      }
    }
  }

  /** Stops accepting tasks; queued tasks still run. */
  public void shutdown() {
    lock.lock();
    try {
      if (state == State.RUNNING) {
        state = State.SHUTDOWN;
      }
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /** Stops accepting tasks, drops queued ones and interrupts workers. Returns dropped tasks. */
  public List<Runnable> shutdownNow() {
    List<Runnable> dropped;
    lock.lock();
    try {
      if (state == State.RUNNING) {
        state = State.SHUTDOWN;
      }
      dropped = new ArrayList<>(queue);
      queue.clear();
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
    workers.forEach(Thread::interrupt);
    return dropped;
  }

  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long nanos = unit.toNanos(timeout);
    lock.lock();
    try {
      while (state != State.TERMINATED) {
        if (nanos <= 0) {
          return false;
        }
        nanos = terminated.awaitNanos(nanos);
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  /** Graceful shutdown: drain the queue and join all workers. */
  @Override
  public void close() {
    shutdown();
    boolean interrupted = false;
    for (Thread t : workers) {
      while (true) {
        try {
          t.join();
          break;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  public int threadCount() {
    return workers.size();
  }

  public int queueSize() {
    lock.lock();
    try {
      return queue.size();
    } finally {
      lock.unlock();
    }
  }

  public int peakQueueSize() {
    lock.lock();
    try {
      return peakQueueSize;
    } finally {
      lock.unlock();
    }
  }

  public long completedTasks() {
    lock.lock();
    try {
      return completedTasks;
    } finally {
      lock.unlock();
    }
  }

  public boolean isShutdown() {
    lock.lock();
    try {
      return state != State.RUNNING;
    } finally {
      lock.unlock();
    }
  }
}
