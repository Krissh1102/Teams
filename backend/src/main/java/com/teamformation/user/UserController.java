package com.teamformation.user;

import com.teamformation.user.UserService.UserProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    public record UpdateProfileRequest(
            @Size(min = 2, max = 80) String displayName,
            @Min(-720) @Max(840) Integer utcOffsetMin,
            @Size(max = 15) List<@Size(min = 1, max = 40) String> skills,
            @Size(max = 15) List<@Size(min = 1, max = 40) String> interests) {}

    private final UserService users;
    public UserController(UserService users) { this.users = users; }

    @GetMapping("/me")
    public UserProfile me(@AuthenticationPrincipal UUID userId) { return users.get(userId); }

    @PutMapping("/me")
    public UserProfile update(@AuthenticationPrincipal UUID userId, @Valid @RequestBody UpdateProfileRequest r) {
        return users.update(userId, r.displayName(), r.utcOffsetMin(), r.skills(), r.interests());
    }
}
