package io.github.samsholab.arena;

import java.util.ArrayDeque;
import java.util.Deque;

/** Server-side state for one connected player. Only touched from the tick thread. */
final class Player {
    final int id;
    double x;
    double y;
    int score;

    /** Highest input sequence number applied so far. Sent back to the client in every snapshot. */
    long lastProcessedSeq;

    /** Token bucket that limits how many movement frames a player can apply per tick. */
    double inputBudget;

    int droppedInputs;

    final Deque<InputCommand> pending = new ArrayDeque<>();

    Player(int id, double x, double y, double initialBudget) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.inputBudget = initialBudget;
    }
}
