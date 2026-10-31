package vn.edu.vnu.registration.domain.idempotency;

import vn.edu.vnu.registration.domain.Outcome;
import vn.edu.vnu.registration.domain.exception.BusinessException;
import vn.edu.vnu.registration.domain.idempotency.model.SavedResponse;
import vn.edu.vnu.registration.domain.port.IdempotencyRepository;
import vn.edu.vnu.registration.domain.port.TransactionRunner;

import java.util.Objects;
import java.util.function.Supplier;

public class IdempotencyService {
    private final IdempotencyRepository idempotencyRepo;
    private final TransactionRunner txRunner;

    public IdempotencyService(IdempotencyRepository idempotencyRepo, TransactionRunner txRunner) {
        this.idempotencyRepo = idempotencyRepo;
        this.txRunner = txRunner;
    }

    public Outcome execute(long userId, String key, String hash, Supplier<Outcome> action) {
        return execute(userId, key, hash, null, action);
    }

    public Outcome execute(long userId, String key, String hash, Runnable beforeClaim, Supplier<Outcome> action) {
        return txRunner.runInTransaction(ctx -> {
            if (beforeClaim != null) {
                beforeClaim.run();
            }

            // 1. Attempt to claim key
            if (!idempotencyRepo.claimKey(userId, key, hash)) {
                // Key already claimed: lock row and fetch SavedResponse
                SavedResponse saved = idempotencyRepo.lockKey(userId, key);

                if (!Objects.equals(saved.requestHash(), hash)) {
                    throw BusinessException.error(409, "IDEMPOTENCY_KEY_REUSED", "Khóa đã được dùng với nội dung khác.");
                }
                if (!"COMPLETED".equals(saved.state()) || saved.responseStatus() == null) {
                    throw new IllegalStateException("Incomplete idempotency record");
                }

                // Convert SavedResponse -> replayed Outcome
                return new Outcome(saved.responseStatus(), saved.responseBody(), true);
            }

            // 2. Fresh execution
            Object savepoint = ctx.createSavepoint();
            Outcome result;
            try {
                result = action.get();
            } catch (BusinessException e) {
                if (e.status() >= 500) throw e;
                ctx.rollbackToSavepoint(savepoint);
                result = new Outcome(e.status(), e.body());
            }

            // 3. Complete and persist result
            idempotencyRepo.completeKey(userId, key, result);

            // Return updated outcome
            SavedResponse completed = idempotencyRepo.lockKey(userId, key);
            return new Outcome(result.status(), completed.responseBody());
        });
    }
}
