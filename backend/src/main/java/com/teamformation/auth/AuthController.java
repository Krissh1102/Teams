package com.teamformation.auth;

import com.teamformation.common.ApiException;
import com.teamformation.config.JwtService;
import com.teamformation.user.UserService;
import com.teamformation.user.UserService.UserProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(min = 2, max = 80) String displayName,
            @Min(-720) @Max(840) Integer utcOffsetMin,
            @Size(max = 15) List<@Size(min = 1, max = 40) String> skills,
            @Size(max = 15) List<@Size(min = 1, max = 40) String> interests) {}

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}

    public record AuthResponse(String token, UserProfile user) {}

    private final UserService users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final String dummyHash;

    public AuthController(UserService users, JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt) {
        this.users = users; this.jdbc = jdbc; this.encoder = encoder; this.jwt = jwt;
        this.dummyHash = encoder.encode("not-a-real-password");   // equalises timing for unknown emails
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest r) {
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        // a concurrent duplicate is caught by the UNIQUE(email) constraint and mapped to 409 EMAIL_TAKEN
        UUID id = users.create(email, encoder.encode(r.password()), r.displayName(),
                r.utcOffsetMin() == null ? 0 : r.utcOffsetMin(), r.skills(), r.interests());
        return new AuthResponse(jwt.issue(id), users.get(id));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest r) {
        List<Object[]> rows = jdbc.query("SELECT id, password_hash FROM users WHERE email = ?",
                (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("password_hash")},
                r.email().trim().toLowerCase(Locale.ROOT));
        String hash = rows.isEmpty() ? dummyHash : (String) rows.get(0)[1];
        boolean ok = encoder.matches(r.password(), hash) && !rows.isEmpty();
        if (!ok) throw ApiException.unauthorized("Invalid email or password");
        UUID id = (UUID) rows.get(0)[0];
        return new AuthResponse(jwt.issue(id), users.get(id));
    }
}
