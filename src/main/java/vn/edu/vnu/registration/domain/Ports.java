package vn.edu.vnu.registration.domain;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import static vn.edu.vnu.registration.domain.Models.*;

public final class Ports {
    private Ports() {}

    /** Đồng đội triển khai ký/xác minh JWT, gồm chữ ký, exp, account.id và role. */
    public interface TokenGateway {
        Token issue(Identity identity);
        Identity verify(String bearerToken);
    }
    public interface PasswordVerifier { boolean matches(String raw, String hash); }

    public interface Reader {
        Optional<Account> accountByCode(String code);
        Optional<Account> accountById(long id);
        Optional<Period> currentPeriod();
        List<Section> sections(SectionQuery query);
        List<Registered> registered(long studentId, String term);
        void ping();
    }

    public interface Repository extends Reader {
        <T> T transaction(Function<Transaction, T> work);
    }

    /**
     * Cung cấp các phương thức thao tác ghi,
     * quản lý Idempotency Key để xử lý Race Condition
     * và điều khiển giao dịch.
     */
    public interface Transaction extends Reader {
        Object savepoint();
        void rollbackTo(Object savepoint);

        /**
         * Đăng ký Idempotency Key cho sinh viên.
         *
         * @param studentId ID của sinh viên gửi request
         * @param key       Mã khóa Idempotency do client gửi lên
         * @param hash      Mã hash của nội dung request
         * @return {@code true} nếu khóa được đăng ký mới thành công; {@code false} nếu khóa đã tồn tại
         */
        boolean claimKey(long studentId, String key, String hash);

        /**
         * Khóa và lấy thông tin Idempotency Key đã lưu trước đó để xử lý trùng lặp.
         *
         * @param studentId ID của sinh viên
         * @param key       Mã khóa Idempotency
         * @return Đối tượng {@link SavedResponse} chứa trạng thái và kết quả request trước đó
         */
        SavedResponse lockKey(long studentId, String key);

        /**
         * Hoàn tất xử lý và lưu kết quả phản hồi cho Idempotency Key.
         *
         * @param studentId ID của sinh viên
         * @param key       Mã khóa Idempotency
         * @param outcome   Kết quả xử lý request (gồm status HTTP và response body)
         */
        void completeKey(long studentId, String key, Outcome outcome);

        /**
         * Khóa bản ghi tài khoản sinh viên.
         * <p>
         * Gọi ở đầu giao dịch để ngăn hai request song song của cùng một sinh viên.
         *
         * @param id ID của sinh viên
         */
        void lockAccount(long id);

        /**
         * Khóa bản ghi tài khoản Admin.
         * <p>
         * Gọi ở đầu giao dịch để ngăn hai request song song của cùng admin.
         *
         * @param id ID của admin
         */
        void lockSectionAdmin(long id);

        List<Section> lockSections(List<Long> ids);

        Enrollment enroll(long studentId, long sectionId);
        Optional<Cancellation> enrollment(long studentId, long enrollmentId);
        void cancel(long studentId, long enrollmentId, long sectionId);
        void upsertSection(long id, SectionInput input);
        void upsertPeriod(Period input);
    }
}
