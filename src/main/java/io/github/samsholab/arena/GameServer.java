package io.github.samsholab.arena;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket front end for {@link World}.
 *
 * <p>Threading model:
 * <ul>
 *   <li>The WebSocket library calls onOpen/onMessage/onClose on its own threads.
 *       Those handlers never touch the World. They only push events onto
 *       lock-free queues.</li>
 *   <li>A single scheduled thread runs the tick: drain the queues, step the
 *       World, broadcast a snapshot.</li>
 * </ul>
 * The game logic never runs on two threads at once, so it needs no locking.
 */
public final class GameServer extends WebSocketServer {

    private static final int MAX_MESSAGE_CHARS = 256;

    private sealed interface Event permits Joined, Left, Input { }

    private record Joined(int playerId, WebSocket conn) implements Event { }

    private record Left(int playerId) implements Event { }

    private record Input(InputCommand command) implements Event { }

    private final Gson gson = new Gson();
    private final World world = new World(new Random());
    private final Queue<Event> events = new ConcurrentLinkedQueue<>();
    private final Map<Integer, WebSocket> connections = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tick");
        t.setDaemon(true);
        return t;
    });

    public GameServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
    }

    @Override
    public void onStart() {
        long periodMs = 1000L / World.TICK_HZ;
        ticker.scheduleAtFixedRate(this::tick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        System.out.printf("arena listening on ws://localhost:%d (%d ticks/sec)%n", getPort(), World.TICK_HZ);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        int id = nextId.getAndIncrement();
        conn.setAttachment(id);
        events.add(new Joined(id, conn));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Integer id = conn.getAttachment();
        if (id != null) {
            events.add(new Left(id));
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Integer id = conn.getAttachment();
        if (id == null || message.length() > MAX_MESSAGE_CHARS) {
            return;
        }
        InputCommand cmd = parseInput(id, message);
        if (cmd != null) {
            events.add(new Input(cmd));
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("socket error: " + ex.getMessage());
    }

    /**
     * Turns a raw client message into an InputCommand, or null if it's malformed.
     * The player id comes from the connection, never from the message, so one
     * client can't send inputs as another player.
     */
    InputCommand parseInput(int playerId, String message) {
        JsonObject obj;
        try {
            obj = gson.fromJson(message, JsonObject.class);
        } catch (JsonParseException e) {
            return null;
        }
        if (obj == null || !isString(obj, "type") || !"input".equals(obj.get("type").getAsString())) {
            return null;
        }
        if (!isNumber(obj, "seq") || !isNumber(obj, "dx") || !isNumber(obj, "dy")) {
            return null;
        }
        return new InputCommand(
                playerId,
                obj.get("seq").getAsLong(),
                obj.get("dx").getAsDouble(),
                obj.get("dy").getAsDouble());
    }

    private static boolean isNumber(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() && obj.getAsJsonPrimitive(key).isNumber();
    }

    private static boolean isString(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() && obj.getAsJsonPrimitive(key).isString();
    }

    private void tick() {
        try {
            Event e;
            while ((e = events.poll()) != null) {
                switch (e) {
                    case Joined j -> {
                        world.addPlayer(j.playerId());
                        connections.put(j.playerId(), j.conn());
                        j.conn().send(gson.toJson(Map.of("type", "welcome", "id", j.playerId())));
                    }
                    case Left l -> {
                        world.removePlayer(l.playerId());
                        connections.remove(l.playerId());
                    }
                    case Input in -> world.submit(in.command());
                }
            }
            world.step();
            broadcast(gson.toJson(world.snapshot()));
        } catch (RuntimeException ex) {
            // One bad tick shouldn't kill the scheduler (an uncaught exception
            // would stop all future ticks silently).
            System.err.println("tick failed: " + ex);
        }
    }

    public void shutdown() throws InterruptedException {
        ticker.shutdownNow();
        stop(1000);
    }
}
