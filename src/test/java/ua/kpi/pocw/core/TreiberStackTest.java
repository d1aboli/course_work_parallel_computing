package ua.kpi.pocw.core;

import static ua.kpi.pocw.testing.Assert.check;
import static ua.kpi.pocw.testing.Assert.equal;

import java.util.BitSet;
import java.util.concurrent.CyclicBarrier;
import ua.kpi.pocw.testing.Test;

class TreiberStackTest {
  @Test
  void isLifo() {
    TreiberStack<Integer> s = new TreiberStack<>();
    check(s.isEmpty() && s.pop() == null, "empty");
    s.push(1);
    s.push(2);
    equal(2, s.pop(), "lifo");
    equal(1, s.pop(), "lifo");
    check(s.isEmpty(), "empty again");
  }

  @Test
  void concurrentPushPopKeepsEveryElementOnce() throws Exception {
    int threads = 8;
    int perThread = 50_000;
    TreiberStack<Integer> stack = new TreiberStack<>();
    BitSet[] seen = new BitSet[threads];
    CyclicBarrier start = new CyclicBarrier(threads);
    Thread[] ts = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      final int id = t;
      seen[t] = new BitSet();
      ts[t] = new Thread(() -> {
        try {
          start.await();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
        for (int i = 0; i < perThread; i++) {
          stack.push(id * perThread + i);
          if (i % 2 == 1) { // pop roughly half while others push
            Integer v = stack.pop();
            if (v != null) {
              seen[id].set(v);
            }
          }
        }
      });
      ts[t].start();
    }
    for (Thread t : ts) {
      t.join();
    }
    BitSet all = new BitSet();
    int popped = 0;
    for (BitSet b : seen) {
      popped += b.cardinality();
      check(!all.intersects(b), "element popped twice");
      all.or(b);
    }
    Integer v;
    while ((v = stack.pop()) != null) {
      check(!all.get(v), "element both popped and left in stack");
      all.set(v);
      popped++;
    }
    equal(threads * perThread, popped, "no element lost");
  }
}
