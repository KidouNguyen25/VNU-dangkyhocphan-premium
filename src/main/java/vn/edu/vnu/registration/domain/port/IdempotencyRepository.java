package vn.edu.vnu.registration.domain.port;

import vn.edu.vnu.registration.domain.Outcome;
import vn.edu.vnu.registration.domain.idempotency.model.SavedResponse;

public interface IdempotencyRepository {
    boolean claimKey(long studentId, String key, String hash);
    SavedResponse lockKey(long studentId, String key);
    void completeKey(long studentId, String key, Outcome outcome);
}
