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
            .andExpect(jsonPath("$.cpuBoundSeconds").value(10.0));

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

        // 7. Security: User B tries to view User A's run -> 403 Forbidden
        mockMvc.perform(get("/api/runs/" + runIdA)
                .header("Authorization", "Bearer " + tokenUserB))
            .andExpect(status().isForbidden());

        // 8. Security: User B tries to delete User A's run -> 403 Forbidden
        mockMvc.perform(delete("/api/runs/" + runIdA)
                .header("Authorization", "Bearer " + tokenUserB))
            .andExpect(status().isForbidden());

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
}
