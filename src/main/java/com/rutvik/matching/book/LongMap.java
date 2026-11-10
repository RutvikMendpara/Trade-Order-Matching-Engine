package com.rutvik.matching.book;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * long -> V hash map with open addressing. Replaces HashMap<Long, Order> in the
 * book, which boxed the order id and allocated a node on every insert - that
 * showed up as ~15% of all allocations in JFR.
 */
final class LongMap<V> {

    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys;
    private V[] values;
    private int size;
    private int mask;
    private int shift;

    LongMap(int capacity) {
        int n = Integer.highestOneBit(Math.max(16, capacity * 2) - 1) << 1;
        allocate(n);
    }

    @SuppressWarnings("unchecked")
    private void allocate(int n) {
        keys = new long[n];
        Arrays.fill(keys, EMPTY);
        values = (V[]) new Object[n];
        mask = n - 1;
        shift = 64 - Integer.numberOfTrailingZeros(n);
    }

    // fibonacci hashing: take the top bits of key * golden ratio. Tried a plain
    // identity hash first since ids are sequential - linear probing then piles
    // new ids into one huge cluster and add/cancel went from ~40ns to ~3us.
    private int slot(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
    }

    V get(long key) {
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) {
                return values[i];
            }
            if (k == EMPTY) {
                return null;
            }
        }
    }

    boolean containsKey(long key) {
        return get(key) != null;
    }

    void put(long key, V value) {
        if (key == EMPTY) {
            throw new IllegalArgumentException("reserved key");
        }
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) {
                values[i] = value;
                return;
            }
            if (k == EMPTY) {
                keys[i] = key;
                values[i] = value;
                if (++size * 2 > keys.length) {
                    grow();
                }
                return;
            }
        }
    }

    V remove(long key) {
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == EMPTY) {
                return null;
            }
            if (k == key) {
                V old = values[i];
                deleteAt(i);
                size--;
                return old;
            }
        }
    }

    // backward shift delete, so lookups never need tombstones
    private void deleteAt(int hole) {
        int i = hole;
        while (true) {
            i = (i + 1) & mask;
            long k = keys[i];
            if (k == EMPTY) {
                break;
            }
            int home = slot(k);
            boolean canMove = hole <= i ? (home <= hole || home > i) : (home <= hole && home > i);
            if (canMove) {
                keys[hole] = k;
                values[hole] = values[i];
                hole = i;
            }
        }
        keys[hole] = EMPTY;
        values[hole] = null;
    }

    private void grow() {
        long[] oldKeys = keys;
        V[] oldValues = values;
        allocate(keys.length * 2);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    int size() {
        return size;
    }

    void forEachValue(Consumer<V> action) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) {
                action.accept(values[i]);
            }
        }
    }
}
