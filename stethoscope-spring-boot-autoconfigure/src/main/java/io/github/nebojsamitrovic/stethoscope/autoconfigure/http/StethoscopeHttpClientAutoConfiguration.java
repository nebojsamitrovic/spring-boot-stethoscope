package io.github.nebojsamitrovic.stethoscope.autoconfigure.http;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeAutoConfiguration;
import io.github.nebojsamitrovic.stethoscope.autoconfigure.StethoscopeProperties;
import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import io.github.nebojsamitrovic.stethoscope.core.Redactor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outgoing HTTP watcher. Hooks into the {@code RestTemplateBuilder}, {@code RestClient.Builder} and
 * {@code WebClient.Builder} beans that Spring Boot provides, so only clients built from those are
 * recorded.
 */
@AutoConfiguration(after = StethoscopeAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.http.client.ClientHttpRequestInterceptor")
@ConditionalOnProperty(prefix = "stethoscope", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "stethoscope.http-client", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(Recorder.class)
public class StethoscopeHttpClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HttpClientRecorder stethoscopeHttpClientRecorder(
            Recorder recorder, Redactor redactor, StethoscopeProperties properties) {
        return new HttpClientRecorder(recorder, redactor, properties.getHttpClient());
    }

    @Bean
    @ConditionalOnMissingBean
    public StethoscopeClientHttpRequestInterceptor stethoscopeClientHttpRequestInterceptor(HttpClientRecorder recorder) {
        return new StethoscopeClientHttpRequestInterceptor(recorder);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestTemplate.class)
    static class Blocking {

        @Bean
        RestTemplateCustomizer stethoscopeRestTemplateCustomizer(StethoscopeClientHttpRequestInterceptor interceptor) {
            return restTemplate -> {
                if (!restTemplate.getInterceptors().contains(interceptor)) {
                    restTemplate.getInterceptors().add(interceptor);
                }
            };
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.web.client.RestClient")
        RestClientCustomizer stethoscopeRestClientCustomizer(StethoscopeClientHttpRequestInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClient.class)
    static class Reactive {

        @Bean
        WebClientCustomizer stethoscopeWebClientCustomizer(HttpClientRecorder recorder) {
            StethoscopeExchangeFilterFunction filter = new StethoscopeExchangeFilterFunction(recorder);
            return builder -> builder.filter(filter);
        }
    }
}
