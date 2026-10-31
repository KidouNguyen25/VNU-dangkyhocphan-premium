package vn.edu.vnu.registration.data;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.vnu.registration.data.metrics.QueryTimer;
import vn.edu.vnu.registration.domain.port.TransactionRunner;

import java.util.function.Function;

public class TransactionRunnerImpl extends GenericRepositoryImpl implements TransactionRunner {

    private final TransactionTemplate transactions;

    public TransactionRunnerImpl(JdbcTemplate jdbc, QueryTimer timer, TransactionTemplate transactions) {
        super(jdbc, timer);
        this.transactions = transactions;
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public <T> T runInTransaction(Function<TransactionContext, T> action) {
        try {
            return transactions.execute(status -> {
                update("SET LOCAL lock_timeout = '10s'");
                return action.apply(new PgTransactionContext(status));
            });
        } catch (DataAccessException e) {
            throw translate(e);
        }
    }

    private static class PgTransactionContext implements TransactionContext {
        private final TransactionStatus status;

        PgTransactionContext(TransactionStatus status) {
            this.status = status;
        }

        @Override
        public Object createSavepoint() {
            return status.createSavepoint();
        }

        @Override
        public void rollbackToSavepoint(Object savepoint) {
            status.rollbackToSavepoint(savepoint);
        }
    }
}
