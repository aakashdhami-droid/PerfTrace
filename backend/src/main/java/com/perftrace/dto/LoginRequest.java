package com.perftrace.dto;

import jakarta.validation.constraints.NotBlank;

public class LoginRequest {

    @NotBlank(message = "Username or email is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    public LoginRequest() {}

    public LoginRequest(String username, String password) {
        setUsername(username);
        this.password = password;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) {
        if (username == null) {
            this.username = null;
            return;
        }
        String trimmed = username.trim();
        // Email addresses are stored lower-case; usernames remain case-sensitive.
        this.username = trimmed.contains("@") ? trimmed.toLowerCase(java.util.Locale.ROOT) : trimmed;
    }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
