package dev.youneskaouani.vestige.gate.domain;

import java.util.List;

/** A gate evaluation: the overall verdict plus the per-condition detail behind it. */
public record GateOutcome(String gateName, GateStatus status, List<ConditionOutcome> conditions) {

    public GateOutcome {
        conditions = List.copyOf(conditions);
    }

    /** The conditions that failed, which is what a reviewer actually wants to read. */
    public List<ConditionOutcome> failures() {
        return conditions.stream().filter(c -> c.status() == GateStatus.FAIL).toList();
    }

    /** A short summary line suitable for a GitHub check run title. */
    public String summary() {
        if (status == GateStatus.PASS) {
            return "%s passed (%d conditions)".formatted(gateName, conditions.size());
        }
        return "%s failed (%d of %d conditions)".formatted(gateName, failures().size(), conditions.size());
    }
}
