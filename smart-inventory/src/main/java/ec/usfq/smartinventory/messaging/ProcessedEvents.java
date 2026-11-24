package ec.usfq.smartinventory.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Idempotencia de consumidor: insertar (consumer, eventId) en la misma transaccion que el efecto. */
@Component
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true si es la primera vez que este consumidor ve el evento */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumer, UUID eventId) {
        return jdbc.update("insert into processed_event (consumer, event_id) values (?, ?) on conflict do nothing",
                consumer, eventId) == 1;
    }
}
