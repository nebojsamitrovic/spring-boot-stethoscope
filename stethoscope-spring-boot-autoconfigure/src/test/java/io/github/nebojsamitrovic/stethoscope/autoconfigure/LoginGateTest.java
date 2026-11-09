package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginGateTest {

    @Test
    void anySignedInUserWithoutRoles() {
        LoginGate gate = new LoginGate(List.of());

        assertThat(gate.allows(new MockHttpServletRequest())).isFalse();
        assertThat(gate.allows(user())).isTrue();
    }

    @Test
    void oneOfTheRolesWhenSet() {
        LoginGate gate = new LoginGate(List.of("ADMIN", " OPS ", ""));

        assertThat(gate.allows(user())).isFalse();
        assertThat(gate.allows(user("USER"))).isFalse();
        assertThat(gate.allows(user("OPS"))).isTrue();
        assertThat(gate.allows(user("USER", "ADMIN"))).isTrue();
    }

    @Test
    void unauthorizedWhenAnonymousForbiddenWhenRoleMissing() throws Exception {
        LoginGate gate = new LoginGate(List.of("ADMIN"));

        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        gate.deny(new MockHttpServletRequest(), anonymous);
        assertThat(anonymous.getStatus()).isEqualTo(401);

        MockHttpServletResponse signedIn = new MockHttpServletResponse();
        gate.deny(user("USER"), signedIn);
        assertThat(signedIn.getStatus()).isEqualTo(403);
    }

    private static MockHttpServletRequest user(String... roles) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "jane");
        for (String role : roles) {
            request.addUserRole(role);
        }
        return request;
    }
}
