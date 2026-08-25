package com.devanshedutech.model.crm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a walk-in sits in the funnel.
 *
 * <p>Somebody who travels to the office without booking anything has spent more than a phone
 * call's worth of intent, and before this stage existed they sat in Contacted until they
 * registered for a demo — so the board showed a visit as nothing at all, and a counsellor
 * scanning it could not tell them apart from a lead who had only answered the phone.</p>
 */
class StageOrderTest {

    @Test
    @DisplayName("a visit counts as further along than a phone call")
    void visitingBeatsBeingContacted() {
        assertTrue(Stage.VISITED.getFunnelDepth() > Stage.CONTACTED.getFunnelDepth(),
                "somebody who came to the office has gone further than somebody who answered");
    }

    @Test
    @DisplayName("a visit comes before booking a demo, which is the order it happens in")
    void visitingComesBeforeTheDemo() {
        // They visit, then register for a demo while they are standing there.
        assertTrue(Stage.VISITED.getFunnelDepth() < Stage.DEMO_BOOKED.getFunnelDepth());
    }

    @Test
    @DisplayName("every funnel stage still has a distinct depth after the insert")
    void depthsRemainUnique() {
        // Inserting a stage shifted every later depth. Two stages sharing a depth would make
        // "deepest stage reached" ambiguous, and the funnel is computed from exactly that.
        long distinct = java.util.Arrays.stream(Stage.values())
                .filter(Stage::isInFunnel)
                .map(Stage::getFunnelDepth)
                .distinct().count();
        long inFunnel = java.util.Arrays.stream(Stage.values()).filter(Stage::isInFunnel).count();

        assertEquals(inFunnel, distinct, "two stages share a funnel depth");
    }

    @Test
    @DisplayName("the funnel runs unbroken from nought upwards")
    void depthsAreContiguous() {
        // A gap would draw a funnel step that no lead can ever occupy.
        int[] depths = java.util.Arrays.stream(Stage.values())
                .filter(Stage::isInFunnel)
                .mapToInt(Stage::getFunnelDepth)
                .sorted().toArray();

        for (int i = 0; i < depths.length; i++) {
            assertEquals(i, depths[i], "funnel depths must run 0,1,2… with no gaps");
        }
    }

    @Test
    @DisplayName("lost stays outside the funnel rather than becoming its last step")
    void lostIsStillAnExit() {
        assertFalse(Stage.LOST.isInFunnel());
        assertEquals(-1, Stage.LOST.getFunnelDepth());
    }

    @Test
    @DisplayName("recording a visit moves the lead there and grades it hot")
    void theOutcomeMovesTheLead() {
        assertEquals(Stage.VISITED, OutcomeCode.VISITED.getStage());
        assertEquals(Grade.HOT, OutcomeCode.VISITED.getGrade(),
                "somebody who came in person is not a warm lead");
        assertFalse(OutcomeCode.VISITED.isCountsAsAttempt(),
                "nobody was rung, so it is not a call attempt");
    }
}
