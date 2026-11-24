package ec.usfq.smartinventory.domain;

import java.util.Optional;

/** Regla minima: si lo disponible cae por debajo del punto de reorden, sugerir completar hasta el nivel objetivo. */
public final class ReorderPointRule implements ReplenishmentRule {

    @Override
    public Optional<Suggestion> evaluate(String sku, int available, int reorderPoint, int targetLevel) {
        if (available >= reorderPoint) {
            return Optional.empty();
        }
        return Optional.of(new Suggestion(sku, targetLevel - available));
    }
}
