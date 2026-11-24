package ec.usfq.smartinventory.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.usfq.smartinventory.config.RabbitConfig;
import ec.usfq.smartinventory.domain.Allocation;
import ec.usfq.smartinventory.domain.AllocationPolicy;
import ec.usfq.smartinventory.domain.Batch;
import ec.usfq.smartinventory.messaging.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-02. Una sola transaccion: idempotency key -> lock de lotes (FOR UPDATE) -> politica FEFO ->
 * update de saldos -> reserva -> outbox. Si algo falla, nada de lo anterior queda a medias.
 */
@Service
public class ReservationService {

    public record Command(String clientId, String idempotencyKey, String sku, String location,
                          int quantity, String destination, String correlationId) {
    }

    public record Line(String lotCode, LocalDate expiresOn, int quantity) {
    }

    public record Result(UUID reservationId, String sku, String location, int quantity, String destination,
                         String status, List<Line> lines, boolean replay) {
        Result asReplay() {
            return new Result(reservationId, sku, location, quantity, destination, status, lines, true);
        }
    }

    private final JdbcTemplate jdbc;
    private final AllocationPolicy policy;     // Strategy inyectada (FEFO)
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MeterRegistry metrics;

    public ReservationService(JdbcTemplate jdbc, AllocationPolicy policy, OutboxWriter outbox,
                              ObjectMapper mapper, Clock clock, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public Result reserve(Command cmd) {
        String key = cmd.clientId() + ":" + cmd.idempotencyKey();
        String hash = hash(cmd.sku() + "|" + cmd.location() + "|" + cmd.quantity() + "|" + cmd.destination());

        // Si la misma clave llega en paralelo, el segundo INSERT espera al primer commit (indice unico).
        int inserted = jdbc.update("""
                insert into idempotency_key (key, request_hash, created_at) values (?, ?, ?)
                on conflict (key) do nothing""", key, hash, Timestamp.from(clock.instant()));
        if (inserted == 0) {
            return replay(key, hash);
        }

        List<Batch> batches = jdbc.query("""
                select id, sku, location, lot_code, expires_on, physical, reserved
                from batch where sku = ? and location = ?
                order by expires_on, id
                for update""",
                (rs, i) -> new Batch(rs.getLong("id"), rs.getString("sku"), rs.getString("location"),
                        rs.getString("lot_code"), rs.getDate("expires_on").toLocalDate(),
                        rs.getInt("physical"), rs.getInt("reserved")),
                cmd.sku(), cmd.location());
        if (batches.isEmpty()) {
            throw ApiException.notFound("No stock registered for sku " + cmd.sku() + " at " + cmd.location());
        }

        List<Allocation> allocations;
        try {
            allocations = policy.allocate(batches, cmd.quantity(), LocalDate.now(clock));
        } catch (RuntimeException e) {
            metrics.counter("smartinventory.reservations", "result", "rejected").increment();
            throw e;
        }

        UUID reservationId = UUID.randomUUID();
        jdbc.update("""
                insert into reservation (id, sku, location, quantity, destination, status, created_at)
                values (?, ?, ?, ?, ?, 'CONFIRMED', ?)""",
                reservationId, cmd.sku(), cmd.location(), cmd.quantity(), cmd.destination(),
                Timestamp.from(clock.instant()));
        for (Allocation a : allocations) {
            jdbc.update("update batch set reserved = reserved + ? where id = ?", a.quantity(), a.batchId());
            jdbc.update("insert into reservation_line (reservation_id, batch_id, lot_code, quantity) values (?,?,?,?)",
                    reservationId, a.batchId(), a.lotCode(), a.quantity());
        }

        List<Line> lines = allocations.stream()
                .map(a -> new Line(a.lotCode(), a.expiresOn(), a.quantity())).toList();
        Result result = new Result(reservationId, cmd.sku(), cmd.location(), cmd.quantity(),
                cmd.destination(), "CONFIRMED", lines, false);

        outbox.append(RabbitConfig.STOCK_RESERVED, cmd.correlationId(), Map.of(
                "reservationId", reservationId.toString(), "sku", cmd.sku(), "location", cmd.location(),
                "quantity", cmd.quantity(), "destination", cmd.destination(),
                "lines", lines.stream().map(l -> Map.of("lotCode", l.lotCode(), "quantity", l.quantity())).toList()));

        jdbc.update("update idempotency_key set response = ?::jsonb where key = ?", toJson(result), key);
        metrics.counter("smartinventory.reservations", "result", "created").increment();
        return result;
    }

    private Result replay(String key, String hash) {
        Map<String, Object> row = jdbc.queryForMap(
                "select request_hash, response::text as response from idempotency_key where key = ?", key);
        if (!hash.equals(row.get("request_hash"))) {
            throw new ApiException(422, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request");
        }
        try {
            metrics.counter("smartinventory.reservations", "result", "replayed").increment();
            return mapper.readValue((String) row.get("response"), Result.class).asReplay();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    private String toJson(Result r) {
        try {
            return mapper.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
