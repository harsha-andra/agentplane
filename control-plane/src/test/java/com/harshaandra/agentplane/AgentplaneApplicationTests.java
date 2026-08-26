package com.harshaandra.agentplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full Spring context load smoke test. Runs under the {@code test} profile (see
 * application-test.yml): Postgres is swapped for in-memory H2 and nothing else touches a real
 * Mongo/Redis/Kubernetes at startup - so this passes on a clean clone with no Docker, no
 * Testcontainers, and no cluster. See README.md for the {@code -Pintegration} profile that
 * exercises the real Postgres/Mongo backends via Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgentplaneApplicationTests {

    @Test
    void contextLoads(ApplicationContext context) {
        assertThat(context).isNotNull();
        assertThat(context.getBeanDefinitionCount()).isGreaterThan(0);
    }
}
