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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

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
        return new HttpClientRecorder(recorder, redactor, properties.getHttpClient(), properties.isStoreEntries());
    }

    @Bean
    @ConditionalOnMissingBean
    public StethoscopeClientHttpRequestInterceptor stethoscopeClientHttpRequestInterceptor(HttpClientRecorder recorder) {
        return new StethoscopeClientHttpRequestInterceptor(recorder);
    }

    static void addInterceptor(RestTemplate restTemplate, StethoscopeClientHttpRequestInterceptor interceptor) {
        if (!restTemplate.getInterceptors().contains(interceptor)) {
            restTemplate.getInterceptors().add(interceptor);
        }
    }

    // Spring Boot 4 moved the customizer interfaces to new packages. Each pair below is guarded by
    // class name, so only the one matching the running Boot version is ever loaded.

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.web.client.RestTemplateCustomizer")
    static class Blocking {

        @Bean
        org.springframework.boot.web.client.RestTemplateCustomizer stethoscopeRestTemplateCustomizer(
                StethoscopeClientHttpRequestInterceptor interceptor) {
            return restTemplate -> addInterceptor(restTemplate, interceptor);
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.web.client.RestClient")
        org.springframework.boot.web.client.RestClientCustomizer stethoscopeRestClientCustomizer(
                StethoscopeClientHttpRequestInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.restclient.RestTemplateCustomizer")
    static class BlockingBoot4 {

        @Bean
        org.springframework.boot.restclient.RestTemplateCustomizer stethoscopeRestTemplateCustomizer(
                StethoscopeClientHttpRequestInterceptor interceptor) {
            return restTemplate -> addInterceptor(restTemplate, interceptor);
        }

        @Bean
        org.springframework.boot.restclient.RestClientCustomizer stethoscopeRestClientCustomizer(
                StethoscopeClientHttpRequestInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "org.springframework.web.reactive.function.client.WebClient",
        "org.springframework.boot.web.reactive.function.client.WebClientCustomizer"
    })
    static class Reactive {

        @Bean
        org.springframework.boot.web.reactive.function.client.WebClientCustomizer stethoscopeWebClientCustomizer(
                HttpClientRecorder recorder) {
            StethoscopeExchangeFilterFunction filter = new StethoscopeExchangeFilterFunction(recorder);
            return builder -> builder.filter(filter);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "org.springframework.web.reactive.function.client.WebClient",
        "org.springframework.boot.webclient.WebClientCustomizer"
    })
    static class ReactiveBoot4 {

        @Bean
        org.springframework.boot.webclient.WebClientCustomizer stethoscopeWebClientCustomizer(HttpClientRecorder recorder) {
            StethoscopeExchangeFilterFunction filter = new StethoscopeExchangeFilterFunction(recorder);
            return builder -> builder.filter(filter);
        }
    }
}
