package com.peerislands.orders;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base for full-application integration tests against real MongoDB and Redis (Testcontainers).
 * Subclasses share one Spring context (and therefore one set of containers) as long as they do not
 * add their own context configuration. The scheduler is disabled; tests call the processor directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "orders.scheduler.enabled=false",
                // The filter stays active, but with a limit the suite (all from 127.0.0.1) never reaches.
                "orders.rate-limit.capacity=1000000",
                "orders.rate-limit.refill-tokens=1000000"
        })
@Import(TestcontainersConfig.class)
public abstract class AbstractIntegrationTest {
}
