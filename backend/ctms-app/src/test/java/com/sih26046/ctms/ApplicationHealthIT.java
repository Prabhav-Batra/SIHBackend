package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * B1 acceptance test.
 *
 * <p>Runs against a real PostGIS container rather than an embedded database, because from B3
 * onward the things worth testing — RLS policies, partitions, spatial types — do not exist
 * outside PostgreSQL. Establishing that here means no later phase has to retrofit it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationHealthIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;

    @Autowired JdbcTemplate jdbc;

    @Test
    void livenessProbeReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessProbeReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void flywayAppliedTheBaselineMigration() {
        // Asserting postgis_version() here would prove nothing: the postgis image ships the
        // extension pre-installed, so that query succeeds whether or not V1 ever ran.
        // The migration history is the only evidence that Flyway actually executed.
        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success",
                        Integer.class);
        assertThat(applied).isPositive();
    }

    @Test
    void healthDetailsAreNotLeaked() throws Exception {
        // §18.17: health output must not describe internals to an unauthenticated caller.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
