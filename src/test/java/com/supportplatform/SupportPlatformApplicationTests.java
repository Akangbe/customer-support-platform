package com.supportplatform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Phase 1 foundation end to end: the application context loads,
 * the datasource connects to a real Postgres, Flyway applies its migrations
 * cleanly, and the actuator health endpoint reports the app as up.
 */
class SupportPlatformApplicationTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void contextLoads() {
        // A failed context load (bad config, failed Flyway migration, missing bean) fails this test.
    }

    @Test
    void healthEndpointReportsUp() {
        TestRestTemplate rest = new TestRestTemplate();
        ResponseEntity<String> response = rest.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
