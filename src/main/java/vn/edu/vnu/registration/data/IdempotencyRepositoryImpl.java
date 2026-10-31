package vn.edu.vnu.registration.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.vnu.registration.data.metrics.QueryTimer;
import vn.edu.vnu.registration.domain.Outcome;
import vn.edu.vnu.registration.domain.idempotency.model.SavedResponse;
import vn.edu.vnu.registration.domain.port.IdempotencyRepository;

public class IdempotencyRepositoryImpl extends GenericRepositoryImpl implements IdempotencyRepository {
    private final ObjectMapper json;

    public IdempotencyRepositoryImpl(JdbcTemplate jdbc, QueryTimer timer, ObjectMapper json) {
        super(jdbc, timer);
        this.json = json != null ? json : new ObjectMapper();
    }

    public IdempotencyRepositoryImpl(JdbcTemplate jdbc, QueryTimer timer) {
        this(jdbc, timer, new ObjectMapper());
    }

    @Override
    public boolean claimKey(long studentId, String key, String hash) {
        return !query("""
                INSERT INTO idempotency_record (student_id, idempotency_key, request_hash, state, created_at, expires_at)
                VALUES (?, ?, ?, 'PROCESSING', clock_timestamp(), clock_timestamp() + interval '24 hours')
                ON CONFLICT (student_id, idempotency_key) DO UPDATE SET request_hash = EXCLUDED.request_hash,
                  state = 'PROCESSING', response_status = NULL, response_body = NULL,
                  created_at = clock_timestamp(), expires_at = clock_timestamp() + interval '24 hours'
                WHERE idempotency_record.expires_at <= clock_timestamp() RETURNING id
                """, (rs, row) -> rs.getLong(1), studentId, key, hash).isEmpty();
    }

    @Override
    public SavedResponse lockKey(long studentId, String key) {
        return first(query("""
                SELECT request_hash, state, response_status, response_body FROM idempotency_record
                WHERE student_id = ? AND idempotency_key = ? FOR UPDATE
                """, (rs, row) -> new SavedResponse(rs.getString("request_hash"), rs.getString("state"),
                (Integer) rs.getObject("response_status", Integer.class), decode(rs.getString("response_body"))), studentId, key))
                .orElseThrow(() -> new IllegalStateException("Missing idempotency record"));
    }

    @Override
    public void completeKey(long studentId, String key, Outcome result) {
        update("UPDATE idempotency_record SET state = 'COMPLETED', response_status = ?, response_body = ?::jsonb WHERE student_id = ? AND idempotency_key = ?",
                result.status(), encode(result.body()), studentId, key);
    }

    private Object decode(String data) {
        if (data == null) return null;
        try { return json.readValue(data, Object.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored response", e); }
    }

    private String encode(Object data) {
        try { return json.writeValueAsString(data); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode response", e); }
    }
}
