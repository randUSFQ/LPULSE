package ec.usfq.smartinventory.domain;

import java.util.Optional;

/** Strategy para la regla de reposicion (US-03). */
public interface ReplenishmentRule {

    record Suggestion(String sku, int quantity) {
    }

    Optional<Suggestion> evaluate(String sku, int available, int reorderPoint, int targetLevel);
}
