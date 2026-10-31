package vn.edu.vnu.registration.domain.port;

import java.util.function.Function;

public interface TransactionRunner {
    <T> T runInTransaction(Function<TransactionContext, T> action);

    interface TransactionContext {
        Object createSavepoint();
        void rollbackToSavepoint(Object savepoint);
    }
}