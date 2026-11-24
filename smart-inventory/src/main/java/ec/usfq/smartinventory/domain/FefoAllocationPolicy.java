package ec.usfq.smartinventory.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** First-Expired-First-Out: consume primero el lote que vence antes; ignora vencidos y sin saldo. */
public final class FefoAllocationPolicy implements AllocationPolicy {

    @Override
    public List<Allocation> allocate(List<Batch> batches, int quantity, LocalDate today) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        List<Batch> candidates = batches.stream()
                .filter(b -> !b.isExpiredOn(today))
                .filter(b -> b.available() > 0)
                .sorted(Comparator.comparing(Batch::expiresOn).thenComparingLong(Batch::id))
                .toList();

        int totalAvailable = candidates.stream().mapToInt(Batch::available).sum();
        if (totalAvailable < quantity) {
            throw new InsufficientStockException(quantity, totalAvailable);
        }

        List<Allocation> result = new ArrayList<>();
        int remaining = quantity;
        for (Batch b : candidates) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining, b.available());
            result.add(new Allocation(b.id(), b.lotCode(), b.expiresOn(), take));
            remaining -= take;
        }
        return result;
    }
}
