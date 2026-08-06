package dev.youneskaouani.vestige.gate.api;

import dev.youneskaouani.vestige.gate.domain.ConditionType;
import dev.youneskaouani.vestige.gate.domain.GateCondition;
import dev.youneskaouani.vestige.gate.domain.QualityGateDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** {@code PUT /api/v1/projects/{id}/gate}'s body (§8): a full replacement of the gate's conditions. */
public record GateConfigRequest(@NotBlank String name, @NotEmpty List<@Valid ConditionRequest> conditions) {

    public record ConditionRequest(@NotNull ConditionType type, @Min(0) long threshold) {
    }

    public QualityGateDefinition toDefinition() {
        return new QualityGateDefinition(
                name, conditions.stream().map(c -> new GateCondition(c.type(), c.threshold())).toList());
    }
}
