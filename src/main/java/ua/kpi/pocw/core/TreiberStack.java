package ua.kpi.pocw.core;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free LIFO stack (R. K. Treiber, 1986).
 *
 * <p>{@link #push} and {@link #pop} retry a compare-and-set on the head pointer, so no thread ever
 * blocks another; a failed CAS means some other thread made progress (lock freedom). Nodes are
 * immutable after publication and Java's garbage collector prevents the ABA problem: a node can
 * not be reused while any thread still holds a reference to it.
 */
public final class TreiberStack<T> {
  private static final class Node<T> {
    final T value;
    final Node<T> next;

    Node(T value, Node<T> next) {
      this.value = value;
      this.next = next;
    }
  }

  private final AtomicReference<Node<T>> head = new AtomicReference<>();

  public void push(T value) {
    while (true) {
      Node<T> current = head.get();
      if (head.compareAndSet(current, new Node<>(value, current))) {
        return;
      }
    }
  }

  /** Returns the top element or {@code null} when the stack is empty. */
  public T pop() {
    while (true) {
      Node<T> current = head.get();
      if (current == null) {
        return null;
      }
      if (head.compareAndSet(current, current.next)) {
        return current.value;
      }
    }
  }

  public boolean isEmpty() {
    return head.get() == null;
  }
}
