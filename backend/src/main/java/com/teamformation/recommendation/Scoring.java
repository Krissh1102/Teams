package com.teamformation.recommendation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Transparent, explainable teammate scoring. Pure functions: no I/O, trivially unit-testable.
 *
 *   total = 100 * (0.5 * skills + 0.3 * timezone + 0.2 * interests)      each component in [0,1]
 *
 *  skills    = if the team listed what it is looking for:  0.6 * coverage + 0.4 * novelty
 *              else:                                       novelty
 *              coverage = share of the team's "looking for" skills the candidate has
 *              novelty  = share of the candidate's skills the team does NOT already have (complementary)
 *  timezone  = max(0, 1 - hoursApart / 12), measured from the team's average UTC offset
 *  interests = Jaccard overlap between candidate interests and the union of team interests
 */
public final class Scoring {
    private Scoring() {}

    public static final double W_SKILLS = 0.5, W_TZ = 0.3, W_INTERESTS = 0.2;

    public record Candidate(java.util.UUID id, String name, int utcOffsetMin, List<String> skills, List<String> interests) {}
    public record Team(Set<String> skills, Set<String> lookingFor, Set<String> interests, double avgUtcOffsetMin) {}
    public record Result(double total, double skills, double timezone, double interests, List<String> reasons) {}

    public static Result score(Team team, Candidate c) {
        Set<String> cs = new LinkedHashSet<>(c.skills());
        List<String> reasons = new ArrayList<>();

        // --- skills
        double novelty = 0;
        Set<String> added = new LinkedHashSet<>(cs);
        added.removeAll(team.skills());
        if (!cs.isEmpty()) novelty = (double) added.size() / cs.size();

        double skills;
        if (!team.lookingFor().isEmpty()) {
            Set<String> hit = new LinkedHashSet<>(team.lookingFor());
            hit.retainAll(cs);
            double coverage = (double) hit.size() / team.lookingFor().size();
            skills = 0.6 * coverage + 0.4 * novelty;
            if (!hit.isEmpty()) reasons.add("Has " + hit.size() + " of the " + team.lookingFor().size()
                    + " skills you are looking for: " + String.join(", ", hit));
        } else {
            skills = novelty;
        }
        if (!added.isEmpty()) reasons.add("Adds skills your team lacks: " + String.join(", ", added));

        // --- time zone
        double hoursApart = Math.abs(c.utcOffsetMin() - team.avgUtcOffsetMin()) / 60.0;
        double tz = Math.max(0, 1 - hoursApart / 12.0);
        reasons.add(String.format("%.1f h from your team's average time zone", hoursApart));

        // --- interests
        Set<String> ci = new LinkedHashSet<>(c.interests());
        Set<String> inter = new LinkedHashSet<>(ci);
        inter.retainAll(team.interests());
        Set<String> union = new LinkedHashSet<>(ci);
        union.addAll(team.interests());
        double interests = union.isEmpty() ? 0 : (double) inter.size() / union.size();
        if (!inter.isEmpty()) reasons.add("Shares interests: " + String.join(", ", inter));

        double total = 100 * (W_SKILLS * skills + W_TZ * tz + W_INTERESTS * interests);
        return new Result(round(total), round(skills), round(tz), round(interests), reasons);
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
