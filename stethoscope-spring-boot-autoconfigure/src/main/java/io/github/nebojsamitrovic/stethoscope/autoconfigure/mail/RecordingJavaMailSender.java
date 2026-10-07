package io.github.nebojsamitrovic.stethoscope.autoconfigure.mail;

import io.github.nebojsamitrovic.stethoscope.core.Entry;
import io.github.nebojsamitrovic.stethoscope.core.EntryType;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Delegating {@link JavaMailSender} that records every message as a {@link EntryType#MAIL} entry. */
public class RecordingJavaMailSender implements JavaMailSender {

    private static final int MAX_BODY = 256 * 1024;

    private final JavaMailSender delegate;
    private final Supplier<Recorder> recorder;

    public RecordingJavaMailSender(JavaMailSender delegate, Supplier<Recorder> recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    /** The wrapped sender, for code that needs the concrete type. */
    public JavaMailSender getDelegate() {
        return delegate;
    }

    @Override
    public MimeMessage createMimeMessage() {
        return delegate.createMimeMessage();
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) throws MailException {
        return delegate.createMimeMessage(contentStream);
    }

    @Override
    public void send(MimeMessage... mimeMessages) throws MailException {
        try {
            delegate.send(mimeMessages);
        } catch (MailException ex) {
            for (MimeMessage message : mimeMessages) {
                record(describe(message), ex);
            }
            throw ex;
        }
        for (MimeMessage message : mimeMessages) {
            record(describe(message), null);
        }
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) throws MailException {
        try {
            delegate.send(simpleMessages);
        } catch (MailException ex) {
            for (SimpleMailMessage message : simpleMessages) {
                record(describe(message), ex);
            }
            throw ex;
        }
        for (SimpleMailMessage message : simpleMessages) {
            record(describe(message), null);
        }
    }

    private void record(Map<String, Object> content, MailException error) {
        try {
            Recorder target = recorder.get();
            if (target == null || !target.isRecording() || content == null) {
                return;
            }
            Set<String> tags = new HashSet<>();
            if (error != null) {
                content.put(Entry.Content.ERROR, error.getClass().getName() + ": " + error.getMessage());
                tags.add(Entry.Tags.FAILED);
            }
            target.record(EntryType.MAIL, content, tags);
        } catch (RuntimeException ignored) {
            // never break sending because of the debugger
        }
    }

    static Map<String, Object> describe(SimpleMailMessage message) {
        Map<String, Object> content = new LinkedHashMap<>();
        putIfPresent(content, Entry.Content.FROM, message.getFrom() == null ? List.of() : List.of(message.getFrom()));
        putIfPresent(content, Entry.Content.TO, list(message.getTo()));
        putIfPresent(content, Entry.Content.CC, list(message.getCc()));
        putIfPresent(content, Entry.Content.BCC, list(message.getBcc()));
        content.put(Entry.Content.SUBJECT, message.getSubject() == null ? "" : message.getSubject());
        if (message.getText() != null) {
            content.put(Entry.Content.TEXT_BODY, truncate(message.getText()));
        }
        return content;
    }

    static Map<String, Object> describe(MimeMessage message) {
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            putIfPresent(content, Entry.Content.FROM, addresses(message.getFrom()));
            putIfPresent(content, Entry.Content.TO, addresses(message.getRecipients(Message.RecipientType.TO)));
            putIfPresent(content, Entry.Content.CC, addresses(message.getRecipients(Message.RecipientType.CC)));
            putIfPresent(content, Entry.Content.BCC, addresses(message.getRecipients(Message.RecipientType.BCC)));
            content.put(Entry.Content.SUBJECT, message.getSubject() == null ? "" : message.getSubject());
            Parts parts = new Parts();
            parts.collect(message, 0);
            if (parts.text != null) {
                content.put(Entry.Content.TEXT_BODY, truncate(parts.text));
            }
            if (parts.html != null) {
                content.put(Entry.Content.HTML_BODY, truncate(parts.html));
            }
            if (!parts.attachments.isEmpty()) {
                content.put(Entry.Content.ATTACHMENTS, parts.attachments);
            }
            return content;
        } catch (MessagingException | RuntimeException ex) {
            return null;
        }
    }

    /** Walks a MIME tree and keeps the first text and HTML body plus attachment names. */
    private static final class Parts {

        String text;
        String html;
        final List<String> attachments = new ArrayList<>();

        void collect(Part part, int depth) {
            if (depth > 10) {
                return;
            }
            try {
                String fileName = part.getFileName();
                if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || fileName != null) {
                    attachments.add(fileName == null ? "(unnamed " + part.getContentType() + ")" : fileName);
                    return;
                }
                // Decide by the actual content: before saveChanges() a message built with
                // MimeMessageHelper still reports "text/plain" while holding a multipart.
                Object content = part.getContent();
                if (content instanceof Multipart multipart) {
                    for (int i = 0; i < multipart.getCount(); i++) {
                        collect(multipart.getBodyPart(i), depth + 1);
                    }
                } else if (content instanceof String string) {
                    String type = actualType(part);
                    if (type.startsWith("text/html")) {
                        html = html == null ? string : html;
                    } else if (type.startsWith("text/plain")) {
                        text = text == null ? string : text;
                    }
                }
            } catch (MessagingException | IOException | RuntimeException ignored) {
                // show what could be read
            }
        }
    }

    /** Content type from the data handler, which is set even before the headers are updated. */
    private static String actualType(Part part) throws MessagingException {
        String type = part.getDataHandler() != null ? part.getDataHandler().getContentType() : part.getContentType();
        return type == null ? "text/plain" : type.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> addresses(Address[] addresses) {
        List<String> result = new ArrayList<>();
        if (addresses != null) {
            for (Address address : addresses) {
                result.add(address.toString());
            }
        }
        return result;
    }

    private static List<String> list(String[] values) {
        return values == null ? List.of() : List.of(values);
    }

    private static void putIfPresent(Map<String, Object> content, String key, List<String> values) {
        if (!values.isEmpty()) {
            content.put(key, values);
        }
    }

    private static String truncate(String text) {
        return text.length() > MAX_BODY ? text.substring(0, MAX_BODY) + "\n… (truncated)" : text;
    }
}
