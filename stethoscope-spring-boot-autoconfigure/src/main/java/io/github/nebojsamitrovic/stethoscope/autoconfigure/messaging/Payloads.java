package io.github.nebojsamitrovic.stethoscope.autoconfigure.messaging;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

/** Renders keys, payloads and header values for display. */
final class Payloads {

    static final int MAX_PAYLOAD = 16 * 1024;

    private Payloads() {
    }

    /** UTF-8 text when the bytes decode cleanly, otherwise a size marker; other objects via toString. */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text;
        if (value instanceof byte[] bytes) {
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, Math.min(bytes.length, MAX_PAYLOAD)))
                        .toString();
            } catch (CharacterCodingException ex) {
                return "<" + bytes.length + " bytes>";
            }
            if (bytes.length > MAX_PAYLOAD) {
                text += "… (truncated, " + bytes.length + " bytes total)";
            }
            return text;
        }
        text = String.valueOf(value);
        return text.length() > MAX_PAYLOAD ? text.substring(0, MAX_PAYLOAD) + "… (truncated)" : text;
    }
}
