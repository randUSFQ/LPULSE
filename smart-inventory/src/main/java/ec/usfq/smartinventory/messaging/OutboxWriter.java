package ec.usfq.smartinventory.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patron Outbox: escribe el evento en la MISMA transaccion que el cambio de dominio.
 * MANDATORY impide usarlo fuera de una transaccion (sino se perderia la garantia).
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String routingKey, String correlationId, Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        var envelope = new EventEnvelope(eventId, routingKey, 1, clock.instant(),
                correlationId != null ? correlationId : MDC.get("requestId"), "smart-inventory", payload);
        try {
            jdbc.update("""
                    insert into outbox (id, event_type, routing_key, payload, correlation_id, occurred_at)
                    values (?, ?, ?, ?::jsonb, ?, ?)""",
                    eventId, routingKey, routingKey, mapper.writeValueAsString(envelope),
                    envelope.correlationId(), Timestamp.from(envelope.occurredAt()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + routingKey, e);
        }
        return eventId;
    }
}
