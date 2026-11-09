package io.github.nebojsamitrovic.stethoscope.it;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

/** The gate runs after Spring Security's filter chain and sees the signed-in user and roles. */
@SpringBootTest(classes = {TestApplication.class, LoginGateIntegrationTest.Security.class}, properties = {
        "stethoscope.enabled=true",
        "stethoscope.login.roles=ADMIN",
        "spring.datasource.url=jdbc:h2:mem:stethoscope-login;DB_CLOSE_DELAY=-1",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@AutoConfigureMockMvc
class LoginGateIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousIsRefused() throws Exception {
        mvc.perform(get("/stethoscope/requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void userWithoutTheRoleIsForbidden() throws Exception {
        mvc.perform(get("/stethoscope/requests").with(httpBasic("jane", "pw"))).andExpect(status().isForbidden());
    }

    @Test
    void adminGetsIn() throws Exception {
        mvc.perform(get("/stethoscope/requests").with(httpBasic("admin", "pw"))).andExpect(status().isOk());
    }

    @Test
    void allowedIpsStillApply() throws Exception {
        mvc.perform(get("/stethoscope/requests").with(httpBasic("admin", "pw"))
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Security {

        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http
                    .authorizeHttpRequests(a -> a.requestMatchers("/stethoscope/**").permitAll().anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .build();
        }

        @Bean
        InMemoryUserDetailsManager users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("admin").password("{noop}pw").roles("ADMIN").build(),
                    User.withUsername("jane").password("{noop}pw").roles("USER").build());
        }
    }
}
