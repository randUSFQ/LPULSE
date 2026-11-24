package ec.usfq.smartinventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReorderPointRuleTest {

    private final ReplenishmentRule rule = new ReorderPointRule();

    @Test
    void suggestsTopUpToTargetWhenBelowReorderPoint() {
        var s = rule.evaluate("SKU-1", 40, 60, 300).orElseThrow();
        assertEquals(260, s.quantity());
    }

    @Test
    void noSuggestionAtOrAboveReorderPoint() {
        assertTrue(rule.evaluate("SKU-1", 60, 60, 300).isEmpty());
        assertTrue(rule.evaluate("SKU-1", 61, 60, 300).isEmpty());
    }

    @Test
    void zeroStockSuggestsFullTarget() {
        assertEquals(300, rule.evaluate("SKU-1", 0, 60, 300).orElseThrow().quantity());
    }
}
