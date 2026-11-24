package ec.usfq.smartinventory.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Patron Strategy (OCP): el flujo de reserva depende de esta interfaz, no de FEFO.
 * Agregar FIFO o "lote mas cercano" no exige tocar {@code ReservationService}.
 */
public interface AllocationPolicy {

    /**
     * @throws InsufficientStockException si el stock asignable no alcanza
     * @throws IllegalArgumentException   si {@code quantity <= 0}
     */
    List<Allocation> allocate(List<Batch> batches, int quantity, LocalDate today);
}
