package vn.edu.vnu.registration.domain.auth;

import vn.edu.vnu.registration.domain.auth.model.Account;
import vn.edu.vnu.registration.domain.auth.model.Identity;
import vn.edu.vnu.registration.domain.auth.model.Role;
import vn.edu.vnu.registration.domain.auth.model.Token;
import vn.edu.vnu.registration.domain.auth.model.RegistrationPeriod;
import vn.edu.vnu.registration.domain.port.AccountRepository;
import vn.edu.vnu.registration.domain.port.PasswordVerifier;
import vn.edu.vnu.registration.domain.port.RegistrationPeriodRepository;
import vn.edu.vnu.registration.domain.port.TokenGateway;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import static vn.edu.vnu.registration.domain.GeneralUtil.within;
import static vn.edu.vnu.registration.domain.exception.BusinessException.error;

public class AuthService {
    private final AccountRepository accountRepository;
    private final RegistrationPeriodRepository registrationPeriodRepository;
    private final TokenGateway tokens;
    private final PasswordVerifier passwords;
    private final String dummyHash;
    private final Clock clock;

    public AuthService(
            AccountRepository aRepo,
            RegistrationPeriodRepository rRepo,
            TokenGateway tokens,
            PasswordVerifier passwords,
            String dummyHash,
            Clock clock
    ) {
        this.accountRepository = aRepo;
        this.registrationPeriodRepository = rRepo;
        this.tokens = tokens;
        this.passwords = passwords;
        this.dummyHash = dummyHash;
        this.clock = clock;
    }

    public Token login(String code, String password) {
        var account = accountRepository.accountByCode(code);
        boolean valid = passwords.matches(password, account.map(Account::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !valid) throw error(401, "INVALID_CREDENTIALS", "Mã đăng nhập hoặc mật khẩu không đúng.");
        var a = account.get();
        var period = registrationPeriodRepository.currentPeriod();
        var now = clock.instant();
        if (a.role() == Role.STUDENT && (period.isEmpty() || !within(now, period.get().loginOpensAt(), period.get().closesAt()))) {
            throw error(403, "LOGIN_NOT_OPEN", "Chưa trong thời gian đăng nhập của sinh viên.");
        }
        if (a.role() == Role.ADMIN && period.filter(p -> within(now, p.opensAt(), p.closesAt())).isPresent()) {
            throw error(403, "ADMIN_LOGIN_LOCKED", "Tạm khóa đăng nhập quản trị trong thời gian đăng ký.");
        }
        return tokens.issue(new Identity(a.id(), a.role()));
    }

    public Map<String, Object> me(Identity identity) {
        var a = accountRepository.accountById(identity.accountId()).orElseThrow(() -> error(401, "UNAUTHORIZED", "Tài khoản không còn tồn tại."));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", a.id()); body.put("code", a.code()); body.put("fullName", a.fullName()); body.put("role", identity.role());
        body.put("creditLimit", registrationPeriodRepository.currentPeriod().map(RegistrationPeriod::creditLimit).orElse(null));
        return body;
    }
}
