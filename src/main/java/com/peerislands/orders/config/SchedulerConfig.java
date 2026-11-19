package com.peerislands.orders.config;

import com.peerislands.orders.scheduler.SchedulerProperties;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Always loaded: binds {@link SchedulerProperties} and sets up ShedLock with MongoDB (collection
 * {@code shedLock}). Whether the cron trigger fires is controlled separately by
 * {@link SchedulingEnabledConfig}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchedulerProperties.class)
@EnableSchedulerLock(defaultLockAtMostFor = "${orders.scheduler.lock-at-most-for}")
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(MongoTemplate mongoTemplate) {
        return new MongoLockProvider(mongoTemplate.getDb());
    }
}
