package vn.edu.vnu.registration.data.metrics;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;


/**
 * Cái này phải là Singleton.
 */
@Component
public class QueryTimer {
    private final ThreadLocal<Long> queryNanos = ThreadLocal.withInitial(() -> 0L);

    public <T> T measured(Supplier<T> action) {
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            queryNanos.set(queryNanos.get() + System.nanoTime() - start);
        }
    }

    public void resetTiming() {
        queryNanos.remove();
    }

    public long queryMillis() {
        return queryNanos.get() / 1_000_000;
    }
}