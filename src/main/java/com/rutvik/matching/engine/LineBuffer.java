package com.rutvik.matching.engine;

import java.nio.charset.StandardCharsets;

/** Reusable byte buffer we write journal lines into, so encoding allocates nothing. */
final class LineBuffer {

    private final byte[] digits = new byte[20];
    byte[] bytes;
    int length;

    LineBuffer(int capacity) {
        bytes = new byte[capacity];
    }

    LineBuffer put(char c) {
        ensure(1);
        bytes[length++] = (byte) c;
        return this;
    }

    LineBuffer put(String s) {
        int n = s.length();
        ensure(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c > 127 || c == ',' || c == '\n') {
                throw new IllegalArgumentException("journal fields must be plain ascii without commas: " + s);
            }
            bytes[length++] = (byte) c;
        }
        return this;
    }

    LineBuffer put(long v) {
        if (v == Long.MIN_VALUE) {
            return put(Long.toString(v));
        }
        ensure(20);
        if (v < 0) {
            bytes[length++] = '-';
            v = -v;
        }
        int n = 0;
        do {
            digits[n++] = (byte) ('0' + v % 10);
            v /= 10;
        } while (v != 0);
        while (n > 0) {
            bytes[length++] = digits[--n];
        }
        return this;
    }

    private void ensure(int extra) {
        if (length + extra > bytes.length) {
            bytes = java.util.Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + extra));
        }
    }

    void clear() {
        length = 0;
    }

    @Override
    public String toString() {
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }
}
