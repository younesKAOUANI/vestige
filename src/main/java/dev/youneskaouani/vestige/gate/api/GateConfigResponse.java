package dev.youneskaouani.vestige.gate.api;

import dev.youneskaouani.vestige.gate.domain.GateCondition;
import dev.youneskaouani.vestige.gate.domain.QualityGateDefinition;
import java.util.List;

/**
 * {@code GET}/{@code PUT /api/v1/projects/{id}/gate}'s response - the resolved gate,
 * human-readable.
 */
public record GateConfigResponse(String name, List<ConditionView> conditions) {

    public record ConditionView(String type, long threshold, String description) {

        static ConditionView of(GateCondition condition) {
            return new ConditionView(
                    condition.type().name(), condition.threshold(), condition.describe());
        }
    }

    public static GateConfigResponse of(QualityGateDefinition definition) {
        return new GateConfigResponse(
                definition.name(),
                definition.conditions().stream().map(ConditionView::of).toList());
    }
}
