package com.teamformation.user;

import com.teamformation.common.ApiException;
import com.teamformation.common.Db;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    public record UserProfile(UUID id, String email, String displayName, int utcOffsetMin,
                              List<String> skills, List<String> interests) {}

    private final JdbcTemplate jdbc;
    public UserService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public UUID create(String email, String passwordHash, String displayName, int utcOffsetMin,
                       List<String> skills, List<String> interests) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, password_hash, display_name, utc_offset_min, interests) VALUES (?,?,?,?,?,?)",
                id, email, passwordHash, displayName.trim(), utcOffsetMin, Db.textArray(Db.normalize(interests, 15)));
        replaceSkills(id, skills);
        return id;
    }

    @Transactional
    public UserProfile update(UUID id, String displayName, Integer utcOffsetMin, List<String> skills, List<String> interests) {
        if (displayName != null) jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", displayName.trim(), id);
        if (utcOffsetMin != null) jdbc.update("UPDATE users SET utc_offset_min = ? WHERE id = ?", utcOffsetMin, id);
        if (interests != null) jdbc.update("UPDATE users SET interests = ? WHERE id = ?", Db.textArray(Db.normalize(interests, 15)), id);
        if (skills != null) replaceSkills(id, skills);
        return get(id);
    }

    private void replaceSkills(UUID id, List<String> skills) {
        jdbc.update("DELETE FROM user_skills WHERE user_id = ?", id);
        for (String s : Db.normalize(skills, 15)) jdbc.update("INSERT INTO user_skills(user_id, skill) VALUES (?,?)", id, s);
    }

    @Transactional(readOnly = true)
    public UserProfile get(UUID id) {
        List<UserProfile> r = jdbc.query("""
                SELECT u.id, u.email, u.display_name, u.utc_offset_min, u.interests,
                       COALESCE((SELECT array_agg(s.skill ORDER BY s.skill) FROM user_skills s WHERE s.user_id = u.id), '{}') AS skills
                FROM users u WHERE u.id = ?""",
                (rs, i) -> new UserProfile(Db.uuid(rs, "id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getInt("utc_offset_min"), Db.strings(rs, "skills"), Db.strings(rs, "interests")), id);
        if (r.isEmpty()) throw ApiException.notFound("USER_NOT_FOUND", "User not found");
        return r.get(0);
    }
}
