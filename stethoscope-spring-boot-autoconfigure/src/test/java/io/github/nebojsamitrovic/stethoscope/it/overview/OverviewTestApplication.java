package io.github.nebojsamitrovic.stethoscope.it.overview;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class OverviewTestApplication {

    @RestController
    static class OrdersController {

        private final JdbcTemplate jdbc;

        OrdersController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/orders/{id}")
        String order(@PathVariable("id") long id) {
            return jdbc.queryForObject("select 'order-' || cast(? as varchar)", String.class, id);
        }

        @GetMapping("/orders/{id}/fail")
        String fail(@PathVariable("id") long id) {
            throw new IllegalStateException("order " + id + " is locked");
        }
    }
}
