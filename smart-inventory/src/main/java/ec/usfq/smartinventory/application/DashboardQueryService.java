package ec.usfq.smartinventory.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** US-04: expone la hora de ultima actualizacion, el retraso de procesamiento y si el dato esta "stale". */
@Service
public class DashboardQueryService {

    public record Row(String sku, int availableNow, int reservationsCount, UUID lastReservationId,
                      String lastStatus, Instant lastEventAt, Instant lastUpdatedAt,
                      long processingDelayMs, boolean stale) {
    }

    public record View(Instant generatedAt, List<Row> items) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final long staleSeconds;

    public DashboardQueryService(JdbcTemplate jdbc, Clock clock, @Value("${app.dashboard.stale-seconds}") long staleSeconds) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.staleSeconds = staleSeconds;
    }

    public View current() {
        Instant now = clock.instant();
        List<Row> rows = jdbc.query("""
                select d.sku, d.reservations_count, d.last_reservation_id, d.last_status, d.last_event_at,
                       d.last_updated_at,
                       coalesce((select sum(b.physical - b.reserved) from batch b where b.sku = d.sku), 0) as available
                from dashboard_view d order by d.last_updated_at desc""",
                (rs, i) -> {
                    Instant eventAt = rs.getTimestamp("last_event_at").toInstant();
                    Instant updatedAt = rs.getTimestamp("last_updated_at").toInstant();
                    return new Row(rs.getString("sku"), rs.getInt("available"), rs.getInt("reservations_count"),
                            rs.getObject("last_reservation_id", UUID.class), rs.getString("last_status"),
                            eventAt, updatedAt, Duration.between(eventAt, updatedAt).toMillis(),
                            Duration.between(updatedAt, now).getSeconds() > staleSeconds);
                });
        return new View(now, rows);
    }
}
