package com.teamformation.team;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TeamDtos {
    private TeamDtos() {}

    // ---- requests (all inputs validated) ----
    public record CreateTeamRequest(
            @NotNull UUID hackathonId,
            @NotBlank @Size(min = 2, max = 80) String name,
            @Size(max = 500) String description,
            @Size(max = 10) List<@Size(min = 1, max = 40) String> lookingFor,
            @Min(1) @Max(50) Integer maxSize) {}

    public record UpdateTeamRequest(
            @Size(min = 2, max = 80) String name,
            @Size(max = 500) String description,
            @Size(max = 10) List<@Size(min = 1, max = 40) String> lookingFor) {}

    public record JoinBody(@NotBlank @Size(min = 16, max = 64) String inviteCode) {}

    public record RequestToJoinBody(@Size(max = 300) String message) {}

    public enum Decision { ACCEPTED, DECLINED }
    public record DecisionBody(@NotNull Decision status) {}

    // ---- responses ----
    public record TeamSummary(UUID id, UUID hackathonId, String name, String description, List<String> lookingFor,
                              int maxSize, int memberCount, String status, UUID leaderId, String leaderName,
                              Instant createdAt) {}

    public record MemberView(UUID userId, String displayName, List<String> skills, Instant joinedAt, boolean leader) {}

    public record TeamDetail(TeamSummary team, List<MemberView> members) {}

    public record InviteView(String code, Instant expiresAt) {}

    public record RequestView(UUID id, UUID userId, String displayName, List<String> skills, String message,
                              String status, Instant createdAt) {}

    public record RequestDecision(UUID requestId, String status, UUID teamId) {}

    public record LeaveResult(boolean teamDeleted, UUID newLeaderId) {}

    public record BoardPage(List<TeamSummary> items, int page, int size, boolean hasMore) {}
}
