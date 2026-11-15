package dev.memscope.fixture;

import java.util.Arrays;

/**
 * Allocates a planted byte array and writes to every page so it is resident (an untouched array is never backed by
 * memory, so PSS and the memory timeline would not see it). Each array gets its own fill value and a 4-byte running
 * id at its start, so no two planted arrays have equal contents; duplicates exist only where an action builds them
 * on purpose.
 */
final class Fill {
    private static int counter;

    private Fill() { }

    static synchronized byte[] bytes(int size) {
        int id = ++counter;
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) (id * 31 + 7));
        for (int i = 0; i < 4 && i < size; i++) b[i] = (byte) (id >>> (8 * i));
        return b;
    }
}
