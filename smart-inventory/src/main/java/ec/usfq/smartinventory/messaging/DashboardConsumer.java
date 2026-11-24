package ec.usfq.smartinventory.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import ec.usfq.smartinventory.config.RabbitConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** US-04: mantiene dashboard_view. Un JSON invalido o un error persistente agota reintentos y termina en DLQ. */
@Component
public class DashboardConsumer {

    private static final Logger log = LoggerFactory.getLogger(DashboardConsumer.class);
    static final String NAME = "dashboard";

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;

    public DashboardConsumer(ObjectMapper mapper, JdbcTemplate jdbc, ProcessedEvents processed) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.processed = processed;
    }

    @RabbitListener(queues = RabbitConfig.DASHBOARD_Q)
    @Transactional
    public void onMessage(Message message) throws IOException {
        EventEnvelope e = mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), EventEnvelope.class);
        MDC.put("requestId", e.correlationId());       // continua la traza logica del request original
        try {
            if (!processed.firstTime(NAME, e.eventId())) {
                log.info("duplicate event {} ignored", e.eventId());
                return;
            }
            String sku = (String) e.payload().get("sku");
            UUID reservationId = UUID.fromString((String) e.payload().get("reservationId"));
            jdbc.update("""
                    insert into dashboard_view (sku, reservations_count, last_reservation_id, last_status,
                                                last_event_at, last_updated_at)
                    values (?, 1, ?, 'CONFIRMED', ?, now())
                    on conflict (sku) do update set
                        reservations_count  = dashboard_view.reservations_count + 1,
                        last_reservation_id = excluded.last_reservation_id,
                        last_status         = excluded.last_status,
                        last_event_at       = excluded.last_event_at,
                        last_updated_at     = now()""",
                    sku, reservationId, Timestamp.from(e.occurredAt()));
            log.info("dashboard updated for sku {}", sku);
        } finally {
            MDC.remove("requestId");
        }
    }
}
