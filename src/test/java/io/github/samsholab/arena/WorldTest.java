package io.github.samsholab.arena;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTest {

    private static final double STEP = World.SPEED * World.FRAME_DT;
    private static final double EPS = 1e-9;

    private World world;

    @BeforeEach
    void setUp() {
        world = new World(new Random(42));
        world.addPlayer(1);
    }

    @Test
    void oneInputMovesOneFrame() {
        Player p = world.player(1);
        double startX = p.x;

        world.submit(new InputCommand(1, 1, 1, 0));
        world.step();

        assertEquals(startX + STEP, p.x, EPS);
        assertEquals(1, p.lastProcessedSeq);
    }

    @Test
    void oversizedDirectionIsClampedToFullSpeed() {
        Player p = world.player(1);
        double startX = p.x;

        world.submit(new InputCommand(1, 1, 50, 0)); // client claims 50x speed
        world.step();

        assertEquals(startX + STEP, p.x, EPS);
    }

    @Test
    void diagonalIsNotFasterThanStraight() {
        Player p = world.player(1);
        double sx = p.x;
        double sy = p.y;

        world.submit(new InputCommand(1, 1, 1, 1));
        world.step();

        assertEquals(STEP, Math.hypot(p.x - sx, p.y - sy), EPS);
    }

    @Test
    void floodingInputsDoesNotMakeYouFaster() {
        Player p = world.player(1);
        double startX = p.x;

        // A speed hacker sends 100 frames of input in one tick.
        for (int seq = 1; seq <= 100; seq++) {
            world.submit(new InputCommand(1, seq, 1, 0));
        }
        world.step();

        // Honest client gets 3 frames per tick; the bucket allows at most MAX_BUDGET.
        double moved = p.x - startX;
        assertTrue(moved <= World.MAX_BUDGET * STEP + EPS, "moved " + moved);
    }

    @Test
    void sustainedRateIsCappedAtBudgetPerTick() {
        Player p = world.player(1);
        p.x = World.PLAYER_RADIUS; // start at the left wall so there's room to move right
        double startX = p.x;
        int ticks = 10;

        long seq = 0;
        for (int t = 0; t < ticks; t++) {
            for (int i = 0; i < 10; i++) {
                world.submit(new InputCommand(1, ++seq, 1, 0));
            }
            world.step();
        }

        double maxFrames = World.BUDGET_PER_TICK * (ticks + 1); // +1 for the starting budget
        assertTrue(p.x - startX <= maxFrames * STEP + EPS);
    }

    @Test
    void replayedAndOutOfOrderInputsAreDropped() {
        assertTrue(world.submit(new InputCommand(1, 5, 1, 0)));
        assertFalse(world.submit(new InputCommand(1, 5, 1, 0)), "exact replay");
        assertFalse(world.submit(new InputCommand(1, 3, 1, 0)), "older than the newest queued");
        world.step();
        assertFalse(world.submit(new InputCommand(1, 4, 1, 0)), "older than last processed");
        assertEquals(3, world.player(1).droppedInputs);
    }

    @Test
    void nanAndInfinityAreRejected() {
        assertFalse(world.submit(new InputCommand(1, 1, Double.NaN, 0)));
        assertFalse(world.submit(new InputCommand(1, 2, 0, Double.POSITIVE_INFINITY)));
    }

    @Test
    void inputsForUnknownPlayersAreIgnored() {
        assertFalse(world.submit(new InputCommand(99, 1, 1, 0)));
    }

    @Test
    void playersCannotLeaveTheArena() {
        Player p = world.player(1);
        for (int seq = 1; seq <= 2000; seq++) {
            world.submit(new InputCommand(1, seq, -1, -1));
            if (seq % 3 == 0) {
                world.step();
            }
        }
        world.step();
        assertEquals(World.PLAYER_RADIUS, p.x, EPS);
        assertEquals(World.PLAYER_RADIUS, p.y, EPS);
    }

    @Test
    void coinPickupIsDecidedByServerPositions() {
        Player p = world.player(1);
        world.putCoin(p.x + World.PLAYER_RADIUS, p.y); // within reach
        world.step();
        assertEquals(1, p.score);
        assertEquals(World.COIN_COUNT, world.coins().size(), "coins respawn back to the target count");
    }

    @Test
    void queueIsBounded() {
        int accepted = 0;
        for (int seq = 1; seq <= World.MAX_PENDING + 50; seq++) {
            if (world.submit(new InputCommand(1, seq, 1, 0))) {
                accepted++;
            }
        }
        assertEquals(World.MAX_PENDING, accepted);
    }

    @Test
    void snapshotEchoesLastProcessedSequence() {
        world.submit(new InputCommand(1, 7, 0, 1));
        world.step();
        World.PlayerView view = world.snapshot().players().get(0);
        assertEquals(7, view.lastSeq());
    }
}
