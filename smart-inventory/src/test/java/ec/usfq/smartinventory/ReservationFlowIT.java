package ec.usfq.smartinventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ec.usfq.smartinventory.application.ApiException;
import ec.usfq.smartinventory.application.ReservationService;
import ec.usfq.smartinventory.domain.InsufficientStockException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integracion con PostgreSQL real (Testcontainers). Se ejecuta con `mvn verify` (failsafe).
 * El broker se desactiva: aqui se prueba commit/rollback, outbox, idempotencia y concurrencia.
 */
@Testcontainers
@SpringBootTest(properties = {"app.outbox.enabled=false", "spring.rabbitmq.listener.simple.auto-startup=false"})
class ReservationFlowIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired ReservationService service;
    @Autowired JdbcTemplate jdbc;

    private static ReservationService.Command cmd(String sku, String key, int qty) {
        return new ReservationService.Command("test-client", key, sku, "BOD-T", qty, "PEDIDO-1", "req-" + key);
    }

    private void seed(String sku, int physical) {
        jdbc.update("insert into batch (sku, location, lot_code, expires_on, physical) values (?, 'BOD-T', 'L1', current_date + 30, ?)",
                sku, physical);
    }

    private int reserved(String sku) {
        return jdbc.queryForObject("select coalesce(sum(reserved),0) from batch where sku = ?", Integer.class, sku);
    }

    @Test
    void reservationWritesOutboxInTheSameTransaction() {
        seed("IT-OUTBOX", 10);
        var result = service.reserve(cmd("IT-OUTBOX", "key-outbox-1", 4));
        assertEquals(4, reserved("IT-OUTBOX"));
        int events = jdbc.queryForObject(
                "select count(*) from outbox where routing_key = 'inventory.stock-reserved' and payload->'payload'->>'reservationId' = ?",
                Integer.class, result.reservationId().toString());
        assertEquals(1, events);
    }

    @Test
    void rejectedReservationRollsBackEverything() {
        seed("IT-ROLLBACK", 5);
        int outboxBefore = jdbc.queryForObject("select count(*) from outbox", Integer.class);
        assertThrows(InsufficientStockException.class, () -> service.reserve(cmd("IT-ROLLBACK", "key-rb-0001", 6)));
        assertEquals(0, reserved("IT-ROLLBACK"));
        assertEquals(outboxBefore, jdbc.queryForObject("select count(*) from outbox", Integer.class));
        // la clave de idempotencia tambien se revirtio: el cliente puede reintentar con una cantidad valida
        assertEquals(3, service.reserve(cmd("IT-ROLLBACK", "key-rb-0001", 3)).quantity());
    }

    @Test
    void sameIdempotencyKeyDoesNotDuplicateReservation() {
        seed("IT-IDEM", 10);
        var first = service.reserve(cmd("IT-IDEM", "key-idem-001", 5));
        var second = service.reserve(cmd("IT-IDEM", "key-idem-001", 5));
        assertEquals(first.reservationId(), second.reservationId());
        assertTrue(second.replay());
        assertEquals(5, reserved("IT-IDEM"));
        var ex = assertThrows(ApiException.class, () -> service.reserve(cmd("IT-IDEM", "key-idem-001", 6)));
        assertEquals(422, ex.status());
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        seed("IT-CONC", 100);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String key = "key-conc-" + String.format("%03d", i);
            Callable<Object> task = () -> {
                try {
                    service.reserve(cmd("IT-CONC", key, 3));
                    ok.incrementAndGet();
                } catch (InsufficientStockException expected) {
                    // esperado cuando el stock se agota
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals(33, ok.get(), "100 unidades / 3 por reserva = 33 reservas exitosas");
        assertEquals(99, reserved("IT-CONC"));
    }
}
