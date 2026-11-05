package vn.edu.vnu.registration.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.vnu.registration.data.metrics.QueryTimer;
import vn.edu.vnu.registration.domain.Outcome;
import vn.edu.vnu.registration.domain.auth.model.Account;
import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.enrollment.model.Cancellation;
import vn.edu.vnu.registration.domain.enrollment.model.Enrollment;
import vn.edu.vnu.registration.domain.enrollment.model.Registered;
import vn.edu.vnu.registration.domain.enrollment.model.Section;
import vn.edu.vnu.registration.domain.enrollment.model.SectionInput;
import vn.edu.vnu.registration.domain.enrollment.model.SectionQuery;
import vn.edu.vnu.registration.domain.idempotency.model.SavedResponse;
import vn.edu.vnu.registration.domain.port.AccountRepository;
import vn.edu.vnu.registration.domain.port.EnrollmentRepository;
import vn.edu.vnu.registration.domain.port.IdempotencyRepository;
import vn.edu.vnu.registration.domain.port.RegistrationPeriodRepository;
import vn.edu.vnu.registration.domain.port.SectionRepository;
import vn.edu.vnu.registration.domain.port.TransactionRunner;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class PostgresRepository extends GenericRepositoryImpl implements AccountRepository, RegistrationPeriodRepository, SectionRepository, EnrollmentRepository, IdempotencyRepository, TransactionRunner {
    private final AccountRepository accountRepo;
    private final RegistrationPeriodRepository periodRepo;
    private final SectionRepository sectionRepo;
    private final EnrollmentRepository enrollmentRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final TransactionRunner txRunner;

    public PostgresRepository(
            AccountRepository accountRepo,
            RegistrationPeriodRepository periodRepo,
            SectionRepository sectionRepo,
            EnrollmentRepository enrollmentRepo,
            IdempotencyRepository idempotencyRepo,
            TransactionRunner txRunner,
            JdbcTemplate jdbc,
            QueryTimer timer
    ) {
        super(jdbc, timer);
        this.accountRepo = accountRepo;
        this.periodRepo = periodRepo;
        this.sectionRepo = sectionRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.txRunner = txRunner;
    }

    public PostgresRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json, QueryTimer timer) {
        this(
                new AccountRepositoryImpl(jdbc, timer),
                new RegistrationPeriodRepositoryImpl(jdbc, timer),
                new SectionRepositoryImpl(jdbc, timer),
                new EnrollmentRepositoryImpl(jdbc, timer),
                new IdempotencyRepositoryImpl(jdbc, timer, json),
                new TransactionRunnerImpl(jdbc, timer, transactions),
                jdbc,
                timer
        );
    }

    public PostgresRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this(jdbc, transactions, json, new QueryTimer());
    }

    public JdbcTemplate getJdbcTemplate() {
        return jdbc;
    }

    public void resetTiming() {
        timer.resetTiming();
    }

    public long queryMillis() {
        return timer.queryMillis();
    }

    public void ping() {
        query("SELECT 1", (rs, row) -> rs.getInt(1));
    }

    // AccountRepository
    @Override
    public Optional<Account> accountByCode(String code) {
        return accountRepo.accountByCode(code);
    }

    @Override
    public Optional<Account> accountById(long id) {
        return accountRepo.accountById(id);
    }

    @Override
    public void lockAccount(long id) {
        accountRepo.lockAccount(id);
    }

    // RegistrationPeriodRepository
    @Override
    public Optional<RegistrationPeriod> currentPeriod() {
        return periodRepo.currentPeriod();
    }

    @Override
    public void upsertPeriod(RegistrationPeriod p) {
        periodRepo.upsertPeriod(p);
    }

    // SectionRepository
    @Override
    public List<Section> sections(SectionQuery q) {
        return sectionRepo.sections(q);
    }

    @Override
    public List<Section> lockSections(List<Long> ids) {
        return sectionRepo.lockSections(ids);
    }

    @Override
    public void lockSectionAdmin(long id) {
        sectionRepo.lockSectionAdmin(id);
    }

    @Override
    public void upsertSection(long id, SectionInput s) {
        sectionRepo.upsertSection(id, s);
    }

    // EnrollmentRepository
    @Override
    public List<Registered> registered(long studentId, String term) {
        return enrollmentRepo.registered(studentId, term);
    }

    @Override
    public Enrollment enroll(long studentId, long sectionId) {
        return enrollmentRepo.enroll(studentId, sectionId);
    }

    @Override
    public Optional<Cancellation> enrollment(long studentId, long enrollmentId) {
        return enrollmentRepo.enrollment(studentId, enrollmentId);
    }

    @Override
    public void cancel(long studentId, long enrollmentId, long sectionId) {
        enrollmentRepo.cancel(studentId, enrollmentId, sectionId);
    }

    // IdempotencyRepository
    @Override
    public boolean claimKey(long studentId, String key, String hash) {
        return idempotencyRepo.claimKey(studentId, key, hash);
    }

    @Override
    public SavedResponse lockKey(long studentId, String key) {
        return idempotencyRepo.lockKey(studentId, key);
    }

    @Override
    public void completeKey(long studentId, String key, Outcome result) {
        idempotencyRepo.completeKey(studentId, key, result);
    }

    // TransactionRunner
    @Override
    public <T> T runInTransaction(Function<TransactionContext, T> action) {
        return txRunner.runInTransaction(action);
    }
}
