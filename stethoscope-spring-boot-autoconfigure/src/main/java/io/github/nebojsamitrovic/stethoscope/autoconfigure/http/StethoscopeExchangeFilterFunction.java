package io.github.nebojsamitrovic.stethoscope.autoconfigure.http;

import io.github.nebojsamitrovic.stethoscope.core.Batch;
import io.github.nebojsamitrovic.stethoscope.core.BatchContext;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Records calls made through {@code WebClient}: method, URL, headers, status and timing. Bodies are
 * not recorded, since reading them would interfere with the caller's streaming.
 */
public class StethoscopeExchangeFilterFunction implements ExchangeFilterFunction {

    private final HttpClientRecorder recorder;

    public StethoscopeExchangeFilterFunction(HttpClientRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        if (!recorder.isRecording()) {
            return next.exchange(request);
        }
        // The filter runs when the exchange is subscribed to, which for blocking callers is the
        // request thread; the response may arrive on an event-loop thread.
        Batch batch = BatchContext.current();
        String batchId = batch == null ? null : batch.id();
        long start = System.nanoTime();
        return next.exchange(request)
                .doOnNext(response -> recorder.record(new HttpClientRecorder.Exchange(
                        request.method().name(), request.url(), request.headers(), null,
                        response.statusCode().value(), response.headers().asHttpHeaders(), null, null,
                        System.nanoTime() - start), batchId))
                .doOnError(error -> recorder.record(new HttpClientRecorder.Exchange(
                        request.method().name(), request.url(), request.headers(), null,
                        null, null, null, error, System.nanoTime() - start), batchId));
    }
}
