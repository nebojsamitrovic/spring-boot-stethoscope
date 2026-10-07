package io.github.nebojsamitrovic.stethoscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactorAndNormalizerTest {

    private final Redactor redactor = Redactor.defaults();

    @Test
    void masksSensitiveHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer test");
        headers.put("Accept", "application/json");

        assertEquals(Map.of("Authorization", Redactor.MASK, "Accept", "application/json"), redactor.headers(headers));
    }

    @Test
    void masksQueryStringAndFormBody() {
        assertEquals("user=test&password=" + Redactor.MASK + "&x=1", redactor.queryString("user=test&password=test&x=1"));
        assertEquals("token=" + Redactor.MASK, redactor.body("token=test"));
        assertEquals("passwordHint=cat", redactor.queryString("passwordHint=cat"));
    }

    @Test
    void masksJsonFields() {
        String json = "{\"email\":\"a@b.c\",\"password\" : \"te\\\"st\",\"nested\":{\"token\":123}}";
        assertEquals(
                "{\"email\":\"a@b.c\",\"password\" : \"" + Redactor.MASK + "\",\"nested\":{\"token\":\"" + Redactor.MASK + "\"}}",
                redactor.body(json));
    }

    @Test
    void normalizesLiteralsAndInLists() {
        assertEquals("select * from post where author_id = ? and title = ?",
                SqlNormalizer.normalize("select  *  from post\nwhere author_id = 42 and title = 'it''s'"));
        assertEquals("select * from t where id in (?)",
                SqlNormalizer.normalize("select * from t where id in (1, 2, 3)"));
        assertEquals("select * from t2 where c = ?", SqlNormalizer.normalize("select * from t2 where c = ?"));
    }
}
