package com.opencodeweb.auth;

import com.opencodeweb.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_-]{3,32}$");

    private final UserRepository users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(UserRepository users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    private record Credentials(String username, String password) {}

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Credentials creds) {
        if (creds == null || creds.username() == null || creds.password() == null) {
            throw ApiException.badRequest("username and password are required");
        }
        if (!USERNAME.matcher(creds.username().trim()).matches()) {
            throw ApiException.badRequest("username must be 3-32 chars, letters/digits/_/- only");
        }
        if (creds.password().length() < 6) {
            throw ApiException.badRequest("password must be at least 6 characters");
        }
        String username = creds.username().trim();
        if (users.findByUsername(username).isPresent()) {
            throw ApiException.conflict("username already taken");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(creds.password()));
        users.save(user);

        return Map.of("token", jwt.issue(user), "username", username);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Credentials creds) {
        User user = users.findByUsername(creds.username() == null ? "" : creds.username().trim())
                .orElseThrow(() -> ApiException.badRequest("invalid username or password"));
        if (!encoder.matches(creds.password(), user.getPasswordHash())) {
            throw ApiException.badRequest("invalid username or password");
        }
        return Map.of("token", jwt.issue(user), "username", user.getUsername());
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        User user = (User) req.getAttribute("authUser");
        return Map.of("username", user.getUsername());
    }
}
