package ec.usfq.smartinventory.domain;

import java.time.LocalDate;

/** Parte de una reserva tomada de un lote concreto. */
public record Allocation(long batchId, String lotCode, LocalDate expiresOn, int quantity) {
}
