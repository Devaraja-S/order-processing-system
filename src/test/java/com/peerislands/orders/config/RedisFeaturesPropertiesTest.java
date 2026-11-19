package com.peerislands.orders.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisFeaturesPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(RedisFeaturesConfig.class)
            .withPropertyValues(
                    "orders.cache.order-ttl=PT60S",
                    "orders.rate-limit.enabled=true",
                    "orders.rate-limit.capacity=100",
                    "orders.rate-limit.refill-tokens=100",
                    "orders.rate-limit.refill-period=PT1M");

    @Test
    void defaultsBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OrdersCacheProperties.class).orderTtl()).isEqualTo(Duration.ofSeconds(60));
            RateLimitProperties rateLimit = context.getBean(RateLimitProperties.class);
            assertThat(rateLimit.enabled()).isTrue();
            assertThat(rateLimit.capacity()).isEqualTo(100);
            assertThat(rateLimit.refillTokens()).isEqualTo(100);
            assertThat(rateLimit.refillPeriod()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "orders.cache.order-ttl=PT0S",
            "orders.cache.order-ttl=-PT1S",
            "orders.rate-limit.capacity=0",
            "orders.rate-limit.refill-tokens=0",
            "orders.rate-limit.refill-period=PT0S"
    })
    void invalidValueFailsStartup(String property) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class);
        });
    }
}
