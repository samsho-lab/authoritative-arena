package io.github.samsholab.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ParseInputTest {

    private final GameServer server = new GameServer(0); // never started, just using the parser

    @Test
    void parsesAValidInput() {
        InputCommand cmd = server.parseInput(3, "{\"type\":\"input\",\"seq\":12,\"dx\":0.5,\"dy\":-1}");
        assertNotNull(cmd);
        assertEquals(3, cmd.playerId());
        assertEquals(12, cmd.seq());
        assertEquals(0.5, cmd.dx());
        assertEquals(-1, cmd.dy());
    }

    @Test
    void playerIdInPayloadIsIgnored() {
        InputCommand cmd = server.parseInput(3, "{\"type\":\"input\",\"playerId\":1,\"seq\":1,\"dx\":1,\"dy\":0}");
        assertNotNull(cmd);
        assertEquals(3, cmd.playerId(), "id must come from the connection, not the message");
    }

    @Test
    void rejectsGarbage() {
        assertNull(server.parseInput(1, "not json"));
        assertNull(server.parseInput(1, "[]"));
        assertNull(server.parseInput(1, "{}"));
        assertNull(server.parseInput(1, "{\"type\":\"chat\",\"seq\":1,\"dx\":1,\"dy\":0}"));
        assertNull(server.parseInput(1, "{\"type\":\"input\",\"seq\":\"one\",\"dx\":1,\"dy\":0}"));
        assertNull(server.parseInput(1, "{\"type\":\"input\",\"seq\":1,\"dx\":{},\"dy\":0}"));
        assertNull(server.parseInput(1, "{\"type\":\"input\",\"seq\":1,\"dx\":1}"));
    }
}
