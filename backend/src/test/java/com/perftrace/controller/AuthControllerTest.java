package com.perftrace.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perftrace.dto.LoginRequest;
import com.perftrace.dto.RegisterRequest;
import com.perftrace.repository.UserRepository;
import com.perftrace.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AuthControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Should successfully register a new user and return JWT token")
    void testRegisterSuccess() throws Exception {
        RegisterRequest req = new RegisterRequest("newuser", "newuser@example.com", "securePassword123");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.username").value("newuser"))
            .andExpect(jsonPath("$.email").value("newuser@example.com"));
    }

    @Test
    @DisplayName("Should reject duplicate username registration")
    void testRegisterDuplicateUsername() throws Exception {
        RegisterRequest req1 = new RegisterRequest("alice", "alice@example.com", "password123");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
            .andExpect(status().isCreated());

        RegisterRequest req2 = new RegisterRequest("alice", "alice2@example.com", "password123");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Username 'alice' is already taken."));
    }

    @Test
    @DisplayName("Should reject duplicate email registration with 409 Conflict")
    void testRegisterDuplicateEmail() throws Exception {
        RegisterRequest req1 = new RegisterRequest("dave", "dave@example.com", "password123");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
            .andExpect(status().isCreated());

        RegisterRequest req2 = new RegisterRequest("dave2", "DAVE@example.com", "password123");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Email 'dave@example.com' is already registered."));
    }

    @Test
    @DisplayName("Should trim usernames and normalize emails, allowing email login in any case")
    void testRegistrationNormalizesIdentityFields() throws Exception {
        RegisterRequest req = new RegisterRequest("  erin  ", "  ERIN@Example.COM  ", "password123");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.username").value("erin"))
            .andExpect(jsonPath("$.email").value("erin@example.com"));

        LoginRequest login = new LoginRequest("  ERIN@EXAMPLE.COM  ", "password123");
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("erin"));
    }

    @Test
    @DisplayName("Should reject a username that becomes too short after trimming")
    void testRejectWhitespaceOnlyUsername() throws Exception {
        RegisterRequest req = new RegisterRequest("  a  ", "short@example.com", "password123");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.validationErrors.username").isNotEmpty());
    }

    @Test
    @DisplayName("Should login successfully with valid credentials and return JWT token")
    void testLoginSuccess() throws Exception {
        RegisterRequest reg = new RegisterRequest("bob", "bob@example.com", "mypassword123");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
            .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("bob", "mypassword123");
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.username").value("bob"));
    }

    @Test
    @DisplayName("Should reject login with invalid password")
    void testLoginInvalidPassword() throws Exception {
        RegisterRequest reg = new RegisterRequest("charlie", "charlie@example.com", "correctPassword");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
            .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("charlie", "wrongPassword");
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should reject unauthenticated access to /api/auth/me with a JSON 401 body")
    void testUnauthenticatedMe() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.error").value("Unauthorized"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("Should reject unauthenticated access to run endpoints with a JSON 401 body")
    void testUnauthenticatedRunAccess() throws Exception {
        mockMvc.perform(get("/api/runs"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Should reject a valid token whose user has been deleted")
    void testTokenForDeletedUserIsRejected() throws Exception {
        String ghostToken = tokenProvider.generateToken(999L, "ghost-user");

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + ghostToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Unauthorized"));
    }
}
