package com.perftrace.controller;

import com.perftrace.dto.AuthResponse;
import com.perftrace.dto.LoginRequest;
import com.perftrace.dto.RegisterRequest;
import com.perftrace.dto.UserDto;
import com.perftrace.entity.User;
import com.perftrace.exception.InvalidFileException;
import com.perftrace.repository.UserRepository;
import com.perftrace.security.CustomUserDetailsService;
import com.perftrace.security.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "User registration, login, and profile")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;

    public AuthController(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        AuthenticationManager authenticationManager,
        JwtTokenProvider tokenProvider,
        CustomUserDetailsService userDetailsService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new InvalidFileException("Username '" + request.getUsername() + "' is already taken.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new InvalidFileException("Email '" + request.getEmail() + "' is already registered.");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user = userRepository.save(user);

        String token = tokenProvider.generateToken(user.getId(), user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new AuthResponse(token, user.getId(), user.getUsername(), user.getEmail()));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with username/email and password")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        User user = userDetailsService.loadUserEntity(authentication.getName());
        String token = tokenProvider.generateToken(user.getId(), user.getUsername());

        return ResponseEntity.ok(new AuthResponse(token, user.getId(), user.getUsername(), user.getEmail()));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current authenticated user profile")
    public ResponseEntity<UserDto> getCurrentUser(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userDetailsService.loadUserEntity(principal.getName());
        return ResponseEntity.ok(new UserDto(user.getId(), user.getUsername(), user.getEmail(), user.getCreatedAt()));
    }
}
