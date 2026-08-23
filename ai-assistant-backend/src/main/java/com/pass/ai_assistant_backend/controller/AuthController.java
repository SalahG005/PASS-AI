package com.pass.ai_assistant_backend.controller;

import com.pass.ai_assistant_backend.dto.AuthResponse;
import com.pass.ai_assistant_backend.dto.LoginRequest;
import com.pass.ai_assistant_backend.dto.RegisterRequest;
import com.pass.ai_assistant_backend.entity.User;
import com.pass.ai_assistant_backend.Repository.UserRepository;
import com.pass.ai_assistant_backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String ALLOWED_EMAIL_DOMAIN = "@pass-consulting.com";
    private static final String EMAIL_PATTERN = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (req == null || isBlank(req.getEmail()) || isBlank(req.getPassword()) || isBlank(req.getFullName())) {
            return ResponseEntity.badRequest().body("fullName, email and password are required");
        }

        String email = req.getEmail().trim().toLowerCase();

        if (!isValidEmailFormat(email)) {
            return ResponseEntity.badRequest().body("Enter a valid email address");
        }

        if (!email.endsWith(ALLOWED_EMAIL_DOMAIN)) {
            return ResponseEntity.badRequest().body("Only @pass-consulting.com email addresses are allowed.");
        }

        if (!isStrongPassword(req.getPassword())) {
            return ResponseEntity.badRequest().body("Password must be at least 8 characters and include uppercase, lowercase, number, and special character.");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.badRequest().body("Email already used");
        }

        User user = new User();
        user.setFullName(req.getFullName().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(req.getPassword()));

        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getEmail());
        return ResponseEntity.ok(new AuthResponse(token));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req == null || isBlank(req.getEmail()) || isBlank(req.getPassword())) {
            return ResponseEntity.badRequest().body("email and password are required");
        }

        String email = req.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            return ResponseEntity.status(401).body("Invalid credentials");
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return ResponseEntity.ok(new AuthResponse(token));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isValidEmailFormat(String email) {
        return email != null && email.matches(EMAIL_PATTERN);
    }

    private boolean isStrongPassword(String password) {
        return password != null
                && password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$");
    }
}
