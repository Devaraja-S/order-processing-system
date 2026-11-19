package com.peerislands.orders.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SchedulerProperties.class)
    static class Config {
    }

    private static final Map<String, String> DEFAULTS = Map.of(
            "orders.scheduler.enabled", "true",
            "orders.scheduler.cron", "0 */5 * * * *",
            "orders.scheduler.min-pending-age", "PT0S",
            "orders.scheduler.batch-size", "1000",
            "orders.scheduler.lock-at-most-for", "PT4M");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    private ApplicationContextRunner withProperties(Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>(DEFAULTS);
        values.putAll(overrides);
        return runner.withPropertyValues(values.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .toArray(String[]::new));
    }

    @Test
    void defaultsBindAndValidate() {
        withProperties(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            SchedulerProperties props = context.getBean(SchedulerProperties.class);
            assertThat(props.enabled()).isTrue();
            assertThat(props.cron()).isEqualTo("0 */5 * * * *");
            assertThat(props.minPendingAge()).isEqualTo(Duration.ZERO);
            assertThat(props.batchSize()).isEqualTo(1000);
            assertThat(props.lockAtMostFor()).isEqualTo(Duration.ofMinutes(4));
        });
    }

    @Test
    void boundaryValuesAreAccepted() {
        withProperties(Map.of("orders.scheduler.batch-size", "10000", "orders.scheduler.min-pending-age", "PT5M"))
                .run(context -> assertThat(context).hasNotFailed());
        withProperties(Map.of("orders.scheduler.batch-size", "1"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "{0}={1} fails startup")
    @CsvSource(delimiter = '|', value = {
            "orders.scheduler.batch-size       | 0",
            "orders.scheduler.batch-size       | 10001",
            "orders.scheduler.batch-size       | -5",
            "orders.scheduler.cron             | not a cron",
            "orders.scheduler.cron             | '*/5 * * * *'",
            "orders.scheduler.cron             | ' '",
            "orders.scheduler.min-pending-age  | -PT1S",
            "orders.scheduler.lock-at-most-for | PT0S",
            "orders.scheduler.lock-at-most-for | -PT1M"
    })
    void invalidValueFailsStartup(String key, String value) {
        withProperties(Map.of(key, value)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(BindValidationException.class);
        });
    }

    @Test
    void missingCronFailsStartup() {
        Map<String, String> values = new HashMap<>(DEFAULTS);
        values.remove("orders.scheduler.cron");
        runner.withPropertyValues(values.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void environmentVariablesOverrideEveryValue() {
        Map<String, Object> env = Map.of(
                "ORDERS_SCHEDULER_ENABLED", "false",
                "ORDERS_SCHEDULER_CRON", "0 0 * * * *",
                "ORDERS_SCHEDULER_MIN_PENDING_AGE", "PT5M",
                "ORDERS_SCHEDULER_BATCH_SIZE", "50",
                "ORDERS_SCHEDULER_LOCK_AT_MOST_FOR", "PT30S");

        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        // Name must end in "-systemEnvironment" for Boot's env-var relaxed binding to apply.
                        .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", env)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SchedulerProperties props = context.getBean(SchedulerProperties.class);
                    assertThat(props.enabled()).isFalse();
                    assertThat(props.cron()).isEqualTo("0 0 * * * *");
                    assertThat(props.minPendingAge()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(props.batchSize()).isEqualTo(50);
                    assertThat(props.lockAtMostFor()).isEqualTo(Duration.ofSeconds(30));
                });
    }
}
