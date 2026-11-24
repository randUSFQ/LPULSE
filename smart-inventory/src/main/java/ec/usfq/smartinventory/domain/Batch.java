package ec.usfq.smartinventory.domain;

import java.time.LocalDate;

/** Lote de un SKU en una ubicacion. {@code available} distingue fisico de reservado (US-01). */
public record Batch(long id, String sku, String location, String lotCode,
                    LocalDate expiresOn, int physical, int reserved) {

    public int available() {
        return physical - reserved;
    }

    public boolean isExpiredOn(LocalDate today) {
        return expiresOn.isBefore(today);
    }
}
