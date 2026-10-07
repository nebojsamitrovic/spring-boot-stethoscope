package io.github.nebojsamitrovic.stethoscope.autoconfigure.http;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.support.Bodies;
import io.github.nebojsamitrovic.stethoscope.core.Batch;
import io.github.nebojsamitrovic.stethoscope.core.BatchContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Records calls made through {@code RestTemplate} and {@code RestClient}.
 *
 * <p>The response body is never buffered whole: at most {@code max-body-size} bytes are read ahead
 * for the dashboard and handed back to the caller in front of the rest of the stream.
 */
public class StethoscopeClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private final HttpClientRecorder recorder;

    public StethoscopeClientHttpRequestInterceptor(HttpClientRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!recorder.isRecording()) {
            return execution.execute(request, body);
        }
        Batch batch = BatchContext.current();
        String batchId = batch == null ? null : batch.id();
        long start = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException ex) {
            recorder.record(exchange(request, body, null, null, null, ex, start), batchId);
            throw ex;
        }

        ClientHttpResponse toReturn = response;
        String responseBody = null;
        Integer status = null;
        HttpHeaders responseHeaders = null;
        try {
            status = response.getStatusCode().value();
            responseHeaders = response.getHeaders();
            if (recorder.recordBodies() && isPeekable(responseHeaders)) {
                PeekingResponse peeking = new PeekingResponse(response, recorder.maxBodySize());
                toReturn = peeking;
                responseBody = peeking.preview(HttpClientRecorder.charset(responseHeaders),
                        responseHeaders.getContentLength());
            }
        } catch (IOException | RuntimeException ignored) {
            // recording is best effort; the caller still gets the response
        }
        recorder.record(exchange(request, body, status, responseHeaders, responseBody, null, start), batchId);
        return toReturn;
    }

    private HttpClientRecorder.Exchange exchange(HttpRequest request, byte[] body, Integer status,
            HttpHeaders responseHeaders, String responseBody, Throwable error, long start) {
        return new HttpClientRecorder.Exchange(
                request.getMethod().name(), request.getURI(), request.getHeaders(),
                recorder.requestBody(body, request.getHeaders()),
                status, responseHeaders, responseBody, error, System.nanoTime() - start);
    }

    private static boolean isPeekable(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        if (type == null || !Bodies.isText(type.toString())) {
            return false;
        }
        // streams must not be read ahead
        return !type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM) && !type.isCompatibleWith(MediaType.APPLICATION_NDJSON);
    }

    /** Reads the first bytes of the body and replays them to the caller. */
    static final class PeekingResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] prefix;
        private final int prefixLength;
        private final IOException readFailure;
        private final InputStream rest;

        PeekingResponse(ClientHttpResponse delegate, int max) throws IOException {
            this.delegate = delegate;
            this.prefix = new byte[max + 1];
            int read = 0;
            IOException failure = null;
            InputStream in = delegate.getBody();
            try {
                while (read < prefix.length) {
                    int n = in.read(prefix, read, prefix.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            } catch (IOException ex) {
                failure = ex;
            }
            this.prefixLength = read;
            this.readFailure = failure;
            this.rest = in;
        }

        String preview(String charset, long totalLength) {
            return Bodies.toText(prefix, prefixLength, charset, prefix.length - 1, totalLength);
        }

        @Override
        public InputStream getBody() throws IOException {
            InputStream head = new ByteArrayInputStream(prefix, 0, prefixLength);
            if (readFailure != null) {
                return new SequenceInputStream(head, new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw readFailure;
                    }
                });
            }
            return new SequenceInputStream(head, rest);
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
