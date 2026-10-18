package io.github.nebojsamitrovic.stethoscope.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BasicAuthGateTest {

    private final BasicAuthGate gate = new BasicAuthGate("admin", "s3cret");

    @Test
    void allowsOnlyTheConfiguredCredentials() {
        assertThat(gate.allows(request("admin:s3cret"))).isTrue();
        assertThat(gate.allows(request("admin:wrong"))).isFalse();
        assertThat(gate.allows(request("admin"))).isFalse();
        assertThat(gate.allows(new MockHttpServletRequest())).isFalse();

        MockHttpServletRequest garbage = new MockHttpServletRequest();
        garbage.addHeader("Authorization", "Basic not-base64!");
        assertThat(gate.allows(garbage)).isFalse();

        MockHttpServletRequest bearer = new MockHttpServletRequest();
        bearer.addHeader("Authorization", "Bearer abc");
        assertThat(gate.allows(bearer)).isFalse();
    }

    @Test
    void deniesWithABasicChallengeSoTheBrowserAsks() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        gate.deny(new MockHttpServletRequest(), response);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Basic realm=\"Stethoscope\"");
    }

    @Test
    void combinedGateAnswersWithTheGateThatRefused() throws Exception {
        StethoscopeGate both = StethoscopeGate.allOf(new AllowedIpsGate(List.of("127.0.0.1")), gate);

        MockHttpServletRequest local = request("admin:s3cret");
        assertThat(both.allows(local)).isTrue();

        MockHttpServletRequest remote = request("admin:s3cret");
        remote.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        assertThat(both.allows(remote)).isFalse();
        both.deny(remote, forbidden);
        assertThat(forbidden.getStatus()).isEqualTo(403);

        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        MockHttpServletResponse challenge = new MockHttpServletResponse();
        both.deny(anonymous, challenge);
        assertThat(challenge.getStatus()).isEqualTo(401);
    }

    private static MockHttpServletRequest request(String credentials) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        return request;
    }
}
