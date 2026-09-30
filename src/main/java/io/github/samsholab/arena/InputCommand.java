package io.github.samsholab.arena;

/**
 * One frame of movement intent from a client.
 *
 * <p>The client only says which direction it wants to move. It never sends a
 * position. Where the player actually ends up is decided by the server.
 *
 * @param playerId who sent it (filled in by the server from the connection, not trusted from the payload)
 * @param seq      increasing sequence number, echoed back in snapshots so the client can reconcile
 * @param dx       desired x direction, expected in [-1, 1]
 * @param dy       desired y direction, expected in [-1, 1]
 */
public record InputCommand(int playerId, long seq, double dx, double dy) {
}
