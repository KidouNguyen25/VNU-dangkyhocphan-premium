package vn.edu.vnu.registration.domain.port;

import vn.edu.vnu.registration.domain.auth.model.Identity;
import vn.edu.vnu.registration.domain.auth.model.Token;

public interface TokenGateway {
    Token issue(Identity identity);
    Identity verify(String bearerToken);
}
