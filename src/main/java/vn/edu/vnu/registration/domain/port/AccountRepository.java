package vn.edu.vnu.registration.domain.port;

import vn.edu.vnu.registration.domain.auth.model.Account;

import java.util.Optional;

public interface AccountRepository {
    Optional<Account> accountByCode(String code);
    Optional<Account> accountById(long id);
    void lockAccount(long id);
}
