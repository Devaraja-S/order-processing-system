package com.peerislands.orders;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // Fails if the application context cannot start against real MongoDB and Redis.
    }

    @Test
    void healthIsUpWithMongoAndRedisComponents() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("mongo").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("redis").path("status").asText()).isEqualTo("UP");
    }
}
