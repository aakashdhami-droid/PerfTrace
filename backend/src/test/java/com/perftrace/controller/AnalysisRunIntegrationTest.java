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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AnalysisRunIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisRunRepository runRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
        runRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String registerAndGetToken(String username, String email) throws Exception {
        RegisterRequest req = new RegisterRequest(username, email, "password123");
        MvcResult res = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();

        AuthResponse auth = objectMapper.readValue(res.getResponse().getContentAsString(), AuthResponse.class);
        return auth.getToken();
    }

    @Test
    @DisplayName("Complete end-to-end flow: Upload, Analyze, Retrieve, Downsample, Compare, and Cross-User Isolation")
    void testCompleteTelemetryLifecycleAndOwnership() throws Exception {
        // 1. Register User A
        String tokenUserA = registerAndGetToken("analystA", "analystA@example.com");

        // 2. User A uploads synthetic CPU-bound CSV
        String cpuCsv = """
            Date,Time,"Total CPU Usage [%]","Core Max [%]","CPU Package [°C]","Core Clocks (avg) [MHz]","GPU Core Load [%]","GPU Temperature [°C]","GPU Clock [MHz]","Core Thermal Throttling [Yes/No]"
            26.10.2026,12:00:00,92.0,96.0,78.0,4600.0,60.0,64.0,1850.0,No
            26.10.2026,12:00:02,94.0,98.0,79.0,4600.0,58.0,64.5,1850.0,No
            26.10.2026,12:00:04,91.5,95.5,78.5,4620.0,62.0,65.0,1840.0,No
            26.10.2026,12:00:06,93.0,97.0,80.0,4600.0,61.0,65.2,1850.0,No
            """;

        MockMultipartFile fileA = new MockMultipartFile(
            "file", "benchmark_cpu.csv", "text/csv", cpuCsv.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult uploadResA = mockMvc.perform(multipart("/api/runs/upload")
                .file(fileA)
                .param("description", "CPU Benchmark Run 1")
                .header("Authorization", "Bearer " + tokenUserA))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.runId").isNumber())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andReturn();

        Long runIdA = objectMapper.readTree(uploadResA.getResponse().getContentAsString()).get("runId").asLong();
        assertThat(runIdA).isNotNull();

        // 3. User A views run summary
        mockMvc.perform(get("/api/runs/" + runIdA)
                .header("Authorization", "Bearer " + tokenUserA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileName").value("benchmark_cpu.csv"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.cpuBoundSeconds").value(6.0));

        // 4. User A retrieves detected segments
        mockMvc.perform(get("/api/runs/" + runIdA + "/segments")
                .header("Authorization", "Bearer " + tokenUserA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].classification").value("CPU_BOUND"))
            .andExpect(jsonPath("$[0].evidenceExplanation").isNotEmpty());

        // 5. User A retrieves downsampled chart samples
        mockMvc.perform(get("/api/runs/" + runIdA + "/samples?maxPoints=100")
                .header("Authorization", "Bearer " + tokenUserA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].cpuTotalLoad").value(92.0));

        // 6. User B registers
        String tokenUserB = registerAndGetToken("analystB", "analystB@example.com");

        // 7. Security: User B tries to view User A's run -> 404 (IDOR-safe, no existence leak)
        mockMvc.perform(get("/api/runs/" + runIdA)
                .header("Authorization", "Bearer " + tokenUserB))
            .andExpect(status().isNotFound());

        // 8. Security: User B tries to delete User A's run -> 404 (IDOR-safe)
        mockMvc.perform(delete("/api/runs/" + runIdA)
                .header("Authorization", "Bearer " + tokenUserB))
            .andExpect(status().isNotFound());

        // 9. User A uploads a second run (GPU-bound)
        String gpuCsv = """
            Date,Time,"Total CPU Usage [%]","Core Max [%]","CPU Package [°C]","Core Clocks (avg) [MHz]","GPU Core Load [%]","GPU Temperature [°C]","GPU Clock [MHz]","Core Thermal Throttling [Yes/No]"
            26.10.2026,12:10:00,45.0,60.0,65.0,4700.0,98.0,72.0,2100.0,No
            26.10.2026,12:10:02,46.0,62.0,65.5,4700.0,99.0,72.5,2100.0,No
            26.10.2026,12:10:04,44.5,59.5,66.0,4700.0,97.5,73.0,2095.0,No
            26.10.2026,12:10:06,47.0,63.0,66.5,4700.0,98.5,73.5,2100.0,No
            """;

        MockMultipartFile fileB = new MockMultipartFile(
            "file", "benchmark_gpu.csv", "text/csv", gpuCsv.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult uploadResB = mockMvc.perform(multipart("/api/runs/upload")
                .file(fileB)
                .param("description", "GPU Benchmark Run 2")
                .header("Authorization", "Bearer " + tokenUserA))
            .andExpect(status().isCreated())
            .andReturn();

        Long runIdB = objectMapper.readTree(uploadResB.getResponse().getContentAsString()).get("runId").asLong();

        // 10. Compare the two runs
        mockMvc.perform(get("/api/runs/compare?a=" + runIdA + "&b=" + runIdB)
                .header("Authorization", "Bearer " + tokenUserA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runA.fileName").value("benchmark_cpu.csv"))
            .andExpect(jsonPath("$.runB.fileName").value("benchmark_gpu.csv"))
            .andExpect(jsonPath("$.metrics").isArray())
            .andExpect(jsonPath("$.conclusionNote").isNotEmpty());

        // 11. User A deletes Run A
        mockMvc.perform(delete("/api/runs/" + runIdA)
                .header("Authorization", "Bearer " + tokenUserA))
            .andExpect(status().isNoContent());

        // Verify Run A is gone
        mockMvc.perform(get("/api/runs/" + runIdA)
                .header("Authorization", "Bearer " + tokenUserA))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Upload must persist and return the user-declared workload type")
    void testWorkloadTypeRoundTrip() throws Exception {
        String token = registerAndGetToken("workloadUser", "workload@example.com");

        String csv = """
            Time,"Total CPU Usage [%]","Core Max [%]","CPU Package [°C]","Core Clocks (avg) [MHz]","GPU Core Load [%]","GPU Temperature [°C]","GPU Clock [MHz]","Core Thermal Throttling [Yes/No]"
            0,90.0,95.0,75.0,4400.0,60.0,65.0,1900.0,No
            2,91.0,96.0,76.0,4400.0,61.0,65.5,1900.0,No
            4,92.0,97.0,77.0,4400.0,62.0,66.0,1900.0,No
            """;

        MockMultipartFile gamingFile = new MockMultipartFile(
            "file", "gaming.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        MvcResult gamingUpload = mockMvc.perform(multipart("/api/runs/upload")
                .file(gamingFile)
                .param("workloadType", "GAMING")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.workloadType").value("GAMING"))
            .andReturn();
        Long gamingRunId = objectMapper.readTree(gamingUpload.getResponse().getContentAsString())
            .get("runId").asLong();

        mockMvc.perform(get("/api/runs/" + gamingRunId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workloadType").value("GAMING"));

        MockMultipartFile unspecifiedFile = new MockMultipartFile(
            "file", "unspecified.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/runs/upload")
                .file(unspecifiedFile)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.workloadType").value("UNSPECIFIED"));
    }

    @Test
    @DisplayName("Every run endpoint must reject cross-user access without leaking run existence")
    void testCrossUserAccessIsDeniedForEveryRunEndpoint() throws Exception {
        String tokenA = registerAndGetToken("ownerA", "ownerA@example.com");
        String tokenB = registerAndGetToken("ownerB", "ownerB@example.com");

        Long runA = uploadSimpleRun(tokenA, "owner_a.csv", 90.0, 60.0);
        Long runB = uploadSimpleRun(tokenB, "owner_b.csv", 45.0, 98.0);

        // User B must not read or mutate User A's run through any endpoint. 404 is used
        // deliberately so attackers cannot enumerate valid run IDs.
        mockMvc.perform(get("/api/runs/" + runA).header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/runs/" + runA + "/segments").header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/runs/" + runA + "/samples").header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/runs/" + runA).header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/runs/compare?a=" + runA + "&b=" + runB)
                .header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());

        // Listing must only ever return the caller's own runs.
        mockMvc.perform(get("/api/runs").header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(runB));
    }

    @Test
    @DisplayName("Database downsampling must preserve a short stress spike instead of averaging it away")
    void testDownsamplingPreservesShortSpike() throws Exception {
        String token = registerAndGetToken("spikeUser", "spike@example.com");

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
            "file", "spike.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        MvcResult upload = mockMvc.perform(multipart("/api/runs/upload")
                .file(file)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andReturn();
        Long runId = objectMapper.readTree(upload.getResponse().getContentAsString()).get("runId").asLong();

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
            .as("The 99%% CPU spike must survive min/max-per-bucket downsampling")
            .isTrue();
    }

    private Long uploadSimpleRun(String token, String fileName, double cpuLoad, double gpuLoad)
        throws Exception {

        String csv = String.format("""
            Date,Time,"Total CPU Usage [%%]","Core Max [%%]","CPU Package [°C]","Core Clocks (avg) [MHz]","GPU Core Load [%%]","GPU Temperature [°C]","GPU Clock [MHz]","Core Thermal Throttling [Yes/No]"
            26.10.2026,12:00:00,%.1f,%.1f,70.0,4400.0,%.1f,65.0,1900.0,No
            26.10.2026,12:00:02,%.1f,%.1f,70.5,4400.0,%.1f,65.5,1900.0,No
            26.10.2026,12:00:04,%.1f,%.1f,71.0,4400.0,%.1f,66.0,1900.0,No
            """, cpuLoad, cpuLoad, gpuLoad, cpuLoad, cpuLoad, gpuLoad, cpuLoad, cpuLoad, gpuLoad);

        MockMultipartFile file = new MockMultipartFile(
            "file", fileName, "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        MvcResult response = mockMvc.perform(multipart("/api/runs/upload")
                .file(file)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString()).get("runId").asLong();
    }
}
