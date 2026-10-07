package io.github.nebojsamitrovic.stethoscope.it;

import io.github.nebojsamitrovic.stethoscope.core.Stethoscope;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** Minimal app used by the integration tests. */
@SpringBootApplication
@EnableCaching
@EnableScheduling
class TestApplication {

    private static final Logger log = LoggerFactory.getLogger(TestApplication.class);

    /** Captures mail instead of talking to an SMTP server. */
    @Bean
    CapturingMailSender mailSender() {
        return new CapturingMailSender();
    }

    static class CapturingMailSender extends JavaMailSenderImpl {

        private final List<MimeMessage> sent = new ArrayList<>();

        /** A method, not a field: the bean is proxied, and fields of a class-based proxy are not the target's. */
        List<MimeMessage> sent() {
            return sent;
        }

        @Override
        protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
            sent.addAll(List.of(mimeMessages));
        }
    }

    record OrderPlaced(long orderId, String customer) {
    }

    @Service
    static class PriceService {

        @Cacheable("prices")
        public int price(int id) {
            return id * 10;
        }
    }

    @Component
    static class Cleanup {

        /** Tests switch the job on when they want to observe it. */
        static final AtomicBoolean ENABLED = new AtomicBoolean();

        private final JdbcTemplate jdbc;

        Cleanup(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Scheduled(fixedDelay = 50)
        void purge() {
            if (!ENABLED.get()) {
                return;
            }
            jdbc.queryForObject("select count(*) from item", Integer.class);
            log.info("purged stale items");
            throw new IllegalStateException("cleanup failed");
        }
    }

    @RestController
    static class ItemController {

        private final JdbcTemplate jdbc;
        private final RestClient restClient;
        private final WebClient webClient;
        private final PriceService prices;
        private final ApplicationEventPublisher events;
        private final JavaMailSender mailSender;

        ItemController(JdbcTemplate jdbc, RestClient.Builder restClient, WebClient.Builder webClient,
                PriceService prices, ApplicationEventPublisher events, JavaMailSender mailSender,
                @Value("${test.external-url:http://localhost:1}") String externalUrl) {
            this.jdbc = jdbc;
            this.restClient = restClient.baseUrl(externalUrl).build();
            this.webClient = webClient.baseUrl(externalUrl).build();
            this.prices = prices;
            this.events = events;
            this.mailSender = mailSender;
        }

        /** Classic N+1: one query for the ids, then one query per id. */
        @GetMapping("/items")
        List<String> items() {
            List<Integer> ids = jdbc.queryForList("select id from item order by id", Integer.class);
            log.info("loading {} items", ids.size());
            return ids.stream()
                    .map(id -> jdbc.queryForObject("select name from item where id = ?", String.class, id))
                    .toList();
        }

        @GetMapping("/items/fail")
        String fail() {
            jdbc.queryForObject("select count(*) from item", Integer.class);
            throw new IllegalStateException("conflict");
        }

        @GetMapping("/items/crash")
        String crash() {
            throw new IllegalArgumentException("boom");
        }

        @GetMapping("/items/logged-error")
        String loggedError() {
            try {
                throw new UnsupportedOperationException("not yet");
            } catch (UnsupportedOperationException ex) {
                log.error("could not do it", ex);
            }
            return "ok";
        }

        @PostMapping("/login")
        Map<String, Object> login(@RequestBody Map<String, Object> body) {
            return Map.of("user", body.get("username"));
        }

        @GetMapping("/external")
        String external() {
            return restClient.post().uri("/echo?api_key=secret-key")
                    .header("Authorization", "Bearer abc")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body("{\"hello\":\"world\"}")
                    .retrieve().body(String.class);
        }

        @GetMapping("/external-reactive")
        String externalReactive() {
            return webClient.get().uri("/echo").retrieve().bodyToMono(String.class).block();
        }

        @GetMapping("/prices/{id}")
        int price(@PathVariable("id") int id) {
            return prices.price(id) + prices.price(id);
        }

        @PostMapping("/orders")
        String placeOrder() {
            events.publishEvent(new OrderPlaced(42, "ana"));
            return "placed";
        }

        @PostMapping("/mail")
        String mail() throws MessagingException {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom("shop@example.com");
            helper.setTo("ana@example.com");
            helper.setSubject("Your order #42");
            helper.setText("Thanks for your order", "<h1>Thanks</h1><script>alert(1)</script>");
            helper.addAttachment("invoice.txt", new org.springframework.core.io.ByteArrayResource("invoice".getBytes()));
            mailSender.send(message);
            return "sent";
        }

        @GetMapping("/dump")
        String dump() {
            Stethoscope.dump(Map.of("cart", List.of(1, 2, 3)));
            return "dumped";
        }

        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<String> conflict(IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
        }
    }
}
