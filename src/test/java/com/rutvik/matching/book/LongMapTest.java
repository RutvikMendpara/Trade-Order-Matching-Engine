package com.rutvik.matching.book;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class LongMapTest {

    // small key range so we get lots of collisions, removes and re-inserts
    @Property(tries = 500)
    void behavesLikeHashMap(@ForAll @Size(max = 2000) java.util.List<@LongRange(min = -50, max = 300) Long> keys,
                            @ForAll @IntRange(min = 0, max = 2) int removeEvery) {
        LongMap<String> map = new LongMap<>(4);
        Map<Long, String> expected = new HashMap<>();
        int i = 0;
        for (long key : keys) {
            if (removeEvery > 0 && i++ % (removeEvery + 1) == 0) {
                assertThat(map.remove(key)).isEqualTo(expected.remove(key));
            } else {
                map.put(key, "v" + i);
                expected.put(key, "v" + i);
            }
            assertThat(map.size()).isEqualTo(expected.size());
        }
        for (long k = -50; k <= 300; k++) {
            assertThat(map.get(k)).isEqualTo(expected.get(k));
        }
    }

    @Test
    void growsPastInitialCapacity() {
        LongMap<Long> map = new LongMap<>(16);
        for (long k = 1; k <= 100_000; k++) {
            map.put(k * 7919, k);
        }
        for (long k = 1; k <= 100_000; k += 2) {
            map.remove(k * 7919);
        }
        assertThat(map.size()).isEqualTo(50_000);
        assertThat(map.get(2 * 7919)).isEqualTo(2L);
        assertThat(map.get(7919)).isNull();
    }
}
