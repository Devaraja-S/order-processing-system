package com.peerislands.orders.scheduler;

import com.peerislands.orders.config.SchedulingEnabledConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** {@code orders.scheduler.enabled} switches the cron trigger on/off; the scheduler bean exists either way. */
class SchedulingToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingEnabledConfig.class, OrderStatusScheduler.class)
            .withBean(PendingOrderProcessor.class, () -> mock(PendingOrderProcessor.class))
            // A cron that never fires during the test (Feb 30th).
            .withPropertyValues("orders.scheduler.cron=0 0 0 30 2 *", "orders.scheduler.lock-at-most-for=PT4M");

    @Test
    void enabledRegistersCronTaskWithConfiguredExpression() {
        runner.withPropertyValues("orders.scheduler.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OrderStatusScheduler.class);
            assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks())
                    .singleElement()
                    .satisfies(task -> assertThat(task.getTask())
                            .isInstanceOfSatisfying(CronTask.class,
                                    cron -> assertThat(cron.getExpression()).isEqualTo("0 0 0 30 2 *")));
        });
    }

    @Test
    void enabledByDefaultWhenPropertyMissing() {
        runner.run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void disabledRegistersNoScheduledTasksButKeepsTheBean() {
        runner.withPropertyValues("orders.scheduler.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(OrderStatusScheduler.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }
}
