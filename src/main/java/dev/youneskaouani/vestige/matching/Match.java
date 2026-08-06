package dev.youneskaouani.vestige.matching;

/** One resolved pairing: {@code current} is the same claim as {@code previous}, per {@code rung}. */
public record Match(PreviousIssueCandidate previous, IncomingFinding current, Rung rung) {
}
