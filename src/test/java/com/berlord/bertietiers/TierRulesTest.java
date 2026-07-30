package com.berlord.bertietiers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.berlord.bertietiers.logic.Decision;
import com.berlord.bertietiers.logic.TierRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The four-branch decision order, isolated from everything else. */
class TierRulesTest {

    @Test
    @DisplayName("a block that is not configured is left alone")
    void unlistedBlockPasses() {
        assertEquals(Decision.PASS, TierRules.decide(null, false, 400));
        assertEquals(Decision.PASS, TierRules.decide(null, false, null));
        assertEquals(Decision.PASS, TierRules.decide(null, true, null));
    }

    @Test
    @DisplayName("equal tier levels mine")
    void sameTierAllows() {
        assertEquals(Decision.ALLOW, TierRules.decide(200, false, 200));
    }

    @Test
    @DisplayName("a higher tool level mines a lower block level")
    void higherToolAllows() {
        assertEquals(Decision.ALLOW, TierRules.decide(200, false, 400));
    }

    @Test
    @DisplayName("a lower tool level is refused")
    void lowerToolDenies() {
        assertEquals(Decision.DENY, TierRules.decide(400, false, 200));
    }

    @Test
    @DisplayName("a tool in no tier is refused on a controlled block")
    void unassignedToolDenies() {
        assertEquals(Decision.DENY, TierRules.decide(200, false, null));
    }

    @Test
    @DisplayName("a point exception beats the numbers, including for an unassigned tool")
    void exceptionWins() {
        assertEquals(Decision.ALLOW, TierRules.decide(400, true, 200));
        assertEquals(Decision.ALLOW, TierRules.decide(400, true, null));
    }
}
