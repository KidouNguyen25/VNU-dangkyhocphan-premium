package vn.edu.vnu.registration.domain;

import vn.edu.vnu.registration.domain.auth.model.Account;
import vn.edu.vnu.registration.domain.auth.model.Identity;
import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.auth.model.Token;
import vn.edu.vnu.registration.domain.enrollment.model.*;
import vn.edu.vnu.registration.domain.idempotency.model.SavedResponse;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class Ports {
    private Ports() {}

    public interface TokenGateway {
        Token issue(Identity identity);
        Identity verify(String bearerToken);
    }

    public interface PasswordVerifier { boolean matches(String raw, String hash); }

    public interface Reader {
        Optional<Account> accountByCode(String code);
        Optional<Account> accountById(long id);
        Optional<RegistrationPeriod> currentPeriod();
        List<Section> sections(SectionQuery query);
        List<Registered> registered(long studentId, String term);
        void ping();
    }

    public interface Repository extends Reader {
        <T> T transaction(Function<Transaction, T> work);
    }

    public interface Transaction extends Reader {
        boolean claimKey(long studentId, String key, String hash);
        SavedResponse lockKey(long studentId, String key);
        void completeKey(long studentId, String key, Outcome outcome);
        void lockAccount(long id);
        void lockSectionAdmin(long id);
        List<Section> lockSections(List<Long> ids);
        Object savepoint();
        void rollbackTo(Object savepoint);
        Enrollment enroll(long studentId, long sectionId);
        Optional<Cancellation> enrollment(long studentId, long enrollmentId);
        void cancel(long studentId, long enrollmentId, long sectionId);
        void upsertSection(long id, SectionInput input);
        void upsertPeriod(RegistrationPeriod input);
    }
}
