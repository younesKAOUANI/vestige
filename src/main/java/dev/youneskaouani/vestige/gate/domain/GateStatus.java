package dev.youneskaouani.vestige.gate.domain;

/** Outcome of a quality gate, for one condition or for the gate as a whole. */
public enum GateStatus {
    PASS,
    FAIL;

    /** A gate is only as good as its worst condition. */
    public GateStatus and(GateStatus other) {
        return this == FAIL || other == FAIL ? FAIL : PASS;
    }
}
