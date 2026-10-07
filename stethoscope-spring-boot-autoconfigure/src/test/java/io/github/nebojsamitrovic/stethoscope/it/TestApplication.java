package io.github.nebojsamitrovic.stethoscope.it;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Minimal app used by {@link StethoscopeIntegrationTest}. */
@SpringBootApplication
class TestApplication {

    @RestController
    static class ItemController {

        private final JdbcTemplate jdbc;

        ItemController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /** Classic N+1: one query for the ids, then one query per id. */
        @GetMapping("/items")
        List<String> items() {
            List<Integer> ids = jdbc.queryForList("select id from item order by id", Integer.class);
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

        @PostMapping("/login")
        Map<String, Object> login(@RequestBody Map<String, Object> body) {
            return Map.of("user", body.get("username"));
        }

        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<String> conflict(IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
        }
    }
}
