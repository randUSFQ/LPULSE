package ec.usfq.smartinventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FefoAllocationPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private final AllocationPolicy policy = new FefoAllocationPolicy();

    private static Batch batch(long id, String lot, String expires, int physical, int reserved) {
        return new Batch(id, "SKU-1", "BOD", lot, LocalDate.parse(expires), physical, reserved);
    }

    @Test
    void consumesEarliestExpiryFirstAndSpansLots() {
        var batches = List.of(
                batch(1, "LATE", "2027-03-01", 100, 0),
                batch(2, "EARLY", "2026-12-01", 30, 0),
                batch(3, "MID", "2026-12-15", 40, 5));

        var result = policy.allocate(batches, 50, TODAY);

        assertEquals(List.of("EARLY", "MID"), result.stream().map(Allocation::lotCode).toList());
        assertEquals(30, result.get(0).quantity());
        assertEquals(20, result.get(1).quantity());
    }

    @Test
    void neverAssignsExpiredLots() {
        var batches = List.of(batch(1, "OLD", "2026-11-22", 50, 0), batch(2, "OK", "2027-01-01", 10, 0));
        var result = policy.allocate(batches, 10, TODAY);
        assertEquals("OK", result.get(0).lotCode());
        assertThrows(InsufficientStockException.class, () -> policy.allocate(batches, 11, TODAY));
    }

    @Test
    void lotExpiringTodayIsStillAssignable() {
        var result = policy.allocate(List.of(batch(1, "TODAY", "2026-11-23", 5, 0)), 5, TODAY);
        assertEquals(5, result.get(0).quantity());
    }

    @Test
    void usesAvailableNotPhysical() {
        var batches = List.of(batch(1, "A", "2026-12-01", 10, 8));
        assertThrows(InsufficientStockException.class, () -> policy.allocate(batches, 3, TODAY));
        assertEquals(2, policy.allocate(batches, 2, TODAY).get(0).quantity());
    }

    @Test
    void insufficientStockReportsWhatIsAssignable() {
        var ex = assertThrows(InsufficientStockException.class,
                () -> policy.allocate(List.of(batch(1, "A", "2026-12-01", 4, 0)), 9, TODAY));
        assertEquals(9, ex.requested());
        assertEquals(4, ex.available());
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> policy.allocate(List.of(), 0, TODAY));
        assertThrows(IllegalArgumentException.class, () -> policy.allocate(List.of(), -1, TODAY));
    }

    @Test
    void tieOnExpiryIsBrokenByIdDeterministically() {
        var batches = List.of(batch(7, "B", "2026-12-01", 5, 0), batch(3, "A", "2026-12-01", 5, 0));
        assertEquals("A", policy.allocate(batches, 5, TODAY).get(0).lotCode());
    }
}
