package com.perftrace.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perftrace.dto.AuthResponse;
import com.perftrace.dto.RegisterRequest;
import com.perftrace.repository.AnalysisRunRepository;
import com.perftrace.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real PostgreSQL integration test.
 *
 * <p>This is deliberately separate from the fast H2 suite: H2 compatibility is not
 * evidence of PostgreSQL compatibility. The test boots the application with the actual
 * Flyway migrations and {@code ddl-auto=validate}, so Hibernate must agree with the
 * PostgreSQL schema created by {@code V1__init_schema.sql} and
 * {@code V2__workload_metadata.sql}.</p>
 *
 * <p>Docker is required. When no Docker daemon is available the class is skipped rather
 * than failed, so local Windows development without Docker remains possible. CI runs it
 * on GitHub Actions.</p>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

    private static final String JWT_SECRET =
        "postgres-test-secret-key-that-is-at-least-256-bits-long-for-hs256";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("perftrace")
        .withUsername("perftrace")
        .withPassword("perftrace");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("perftrace.jwt.secret", () -> JWT_SECRET);
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisRunRepository runRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDatabase() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
        runRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Flyway V2 schema validates and persists user-supplied workload metadata")
    void testFlywaySchemaAndWorkloadMetadata() throws Exception {
        String latestVersion = jdbcTemplate.queryForObject(
            "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
            String.class);
        assertThat(latestVersion).isEqualTo("2");

        String nullable = jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns " +
                "WHERE table_name = 'analysis_runs' AND column_name = 'workload_type'",
            String.class);
        assertThat(nullable).isEqualTo("NO");

        String token = registerAndGetToken("pgWorkload", "pgworkload@example.com");

        MockMultipartFile gaming = new MockMultipartFile(
            "file", "gaming.csv", "text/csv", simpleCsv().getBytes(StandardCharsets.UTF_8));
        MvcResult response = mockMvc.perform(multipart("/api/runs/upload")
                .file(gaming)
                .param("workloadType", "GAMING")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.workloadType").value("GAMING"))
            .andReturn();

        Long runId = objectMapper.readTree(response.getResponse().getContentAsString())
            .get("runId").asLong();

        String storedWorkload = jdbcTemplate.queryForObject(
            "SELECT workload_type FROM analysis_runs WHERE id = ?", String.class, runId);
        assertThat(storedWorkload).isEqualTo("GAMING");

        // Omitted metadata must default to UNSPECIFIED, never to a guessed category.
        MockMultipartFile unspecified = new MockMultipartFile(
            "file", "unspecified.csv", "text/csv", simpleCsv().getBytes(StandardCharsets.UTF_8));
        MvcResult defaultResponse = mockMvc.perform(multipart("/api/runs/upload")
                .file(unspecified)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.workloadType").value("UNSPECIFIED"))
            .andReturn();

        Long defaultRunId = objectMapper.readTree(defaultResponse.getResponse().getContentAsString())
            .get("runId").asLong();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT workload_type FROM analysis_runs WHERE id = ?", String.class, defaultRunId))
            .isEqualTo("UNSPECIFIED");
    }

    @Test
    @DisplayName("PostgreSQL NTILE min/max downsampling preserves a short stress spike")
    void testPostgresDownsamplingPreservesSpike() throws Exception {
        String token = registerAndGetToken("pgSpike", "pgspike@example.com");

        StringBuilder csv = new StringBuilder();
        csv.append("Time,\"Total CPU Usage [%]\",\"Core Max [%]\",\"CPU Package [°C]\",")
            .append("\"Core Clocks (avg) [MHz]\",\"GPU Core Load [%]\",\"GPU Temperature [°C]\",")
            .append("\"GPU Clock [MHz]\",\"Core Thermal Throttling [Yes/No]\"\n");
        for (int i = 0; i < 240; i++) {
            boolean spike = i == 137;
            double cpu = spike ? 99.0 : 20.0 + (i % 5);
            double gpu = spike ? 99.0 : 30.0 + (i % 4);
            double temp = spike ? 95.0 : 60.0;
            csv.append(i).append(',')
                .append(cpu).append(',')
                .append(spike ? 99.0 : 35.0).append(',')
                .append(temp).append(',')
                .append(spike ? 3000.0 : 4000.0).append(',')
                .append(gpu).append(',')
                .append(temp).append(',')
                .append(spike ? 1800.0 : 1500.0).append(',')
                .append("No\n");
        }

        MockMultipartFile file = new MockMultipartFile(
            "file", "pg-spike.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        MvcResult upload = mockMvc.perform(multipart("/api/runs/upload")
                .file(file)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andReturn();
        Long runId = objectMapper.readTree(upload.getResponse().getContentAsString())
            .get("runId").asLong();

        MvcResult samples = mockMvc.perform(get("/api/runs/" + runId + "/samples?maxPoints=10")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

        var points = objectMapper.readTree(samples.getResponse().getContentAsString());
        assertThat(points.size()).isBetween(1, 10);
        boolean spikePreserved = false;
        for (var point : points) {
            if (point.hasNonNull("cpuTotalLoad") && point.get("cpuTotalLoad").asDouble() == 99.0) {
                spikePreserved = true;
            }
        }
        assertThat(spikePreserved)
            .as("The 99%% CPU spike must survive PostgreSQL NTILE min/max downsampling")
            .isTrue();
    }

    private String registerAndGetToken(String username, String email) throws Exception {
        RegisterRequest request = new RegisterRequest(username, email, "password123");
        MvcResult response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn();
        AuthResponse auth = objectMapper.readValue(
            response.getResponse().getContentAsString(), AuthResponse.class);
        return auth.getToken();
    }

    private String simpleCsv() {
        return """
            Time,"Total CPU Usage [%]","Core Max [%]","CPU Package [°C]","Core Clocks (avg) [MHz]","GPU Core Load [%]","GPU Temperature [°C]","GPU Clock [MHz]","Core Thermal Throttling [Yes/No]"
            0,90.0,95.0,75.0,4400.0,60.0,65.0,1900.0,No
            2,91.0,96.0,76.0,4400.0,61.0,65.5,1900.0,No
            4,92.0,97.0,77.0,4400.0,62.0,66.0,1900.0,No
            """;
    }
}
