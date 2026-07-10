package com.cobre.notifications;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// R2DBC is wired as of P-02, so the health endpoint aggregates an r2dbc indicator; a real
// database must be present for the context to boot healthy.
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class NotificationsApplicationTests {

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Proves the WebFlux shell and configuration are wired and the context boots green
     * with no database present.
     */
    @Test
    void contextLoads() {
    }

    /**
     * Proves Actuator health is exposed over the reactive stack and reports UP, with no
     * db/r2dbc component in this phase.
     */
    @Test
    void healthEndpointReportsUp() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }
}
