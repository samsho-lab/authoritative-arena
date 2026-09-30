package io.github.samsholab.arena;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The authoritative game simulation.
 *
 * <p>Not thread-safe on purpose. Everything in here runs on the single tick
 * thread, so there are no locks in the game logic. Network threads never call
 * into this class directly. They hand inputs to {@link GameServer}, which
 * passes them in at the start of each tick.
 */
public final class World {

    public static final double WIDTH = 800;
    public static final double HEIGHT = 600;
    public static final double PLAYER_RADIUS = 12;
    public static final double COIN_RADIUS = 8;

    /** Movement speed in units per second. Lives on the server, so a client can't change it. */
    public static final double SPEED = 200;

    /** Clients send one input per rendered frame at this rate. */
    public static final int CLIENT_INPUT_HZ = 60;
    public static final double FRAME_DT = 1.0 / CLIENT_INPUT_HZ;

    public static final int TICK_HZ = 20;

    /** Frames of movement a client earns per tick (60 / 20 = 3), plus how many it can bank for network jitter. */
    static final double BUDGET_PER_TICK = (double) CLIENT_INPUT_HZ / TICK_HZ;
    static final double MAX_BUDGET = BUDGET_PER_TICK * 2;

    /** Hard cap on queued inputs per player, so a flood can't eat server memory. */
    static final int MAX_PENDING = 120;

    static final int COIN_COUNT = 5;

    public record CoinView(int id, double x, double y) { }

    public record PlayerView(int id, double x, double y, int score, long lastSeq) { }

    public record Snapshot(String type, long tick, List<PlayerView> players, List<CoinView> coins) { }

    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final Map<Integer, CoinView> coins = new LinkedHashMap<>();
    private final Random random;
    private long tick;
    private int nextCoinId = 1;

    public World(Random random) {
        this.random = random;
        while (coins.size() < COIN_COUNT) {
            spawnCoin();
        }
    }

    public void addPlayer(int id) {
        players.put(id, new Player(id, WIDTH / 2, HEIGHT / 2, BUDGET_PER_TICK));
    }

    public void removePlayer(int id) {
        players.remove(id);
    }

    /**
     * Queue an input for the next tick. Returns false if the input was thrown away.
     *
     * <p>Rejected inputs:
     * <ul>
     *   <li>unknown player</li>
     *   <li>NaN or infinite direction values</li>
     *   <li>sequence numbers at or below what we've already applied (replays or out-of-order packets)</li>
     *   <li>anything past {@link #MAX_PENDING} queued inputs</li>
     * </ul>
     */
    public boolean submit(InputCommand input) {
        Player p = players.get(input.playerId());
        if (p == null) {
            return false;
        }
        if (!Double.isFinite(input.dx()) || !Double.isFinite(input.dy())) {
            p.droppedInputs++;
            return false;
        }
        long newest = p.pending.isEmpty() ? p.lastProcessedSeq : p.pending.peekLast().seq();
        if (input.seq() <= newest || p.pending.size() >= MAX_PENDING) {
            p.droppedInputs++;
            return false;
        }
        p.pending.addLast(input);
        return true;
    }

    /** Advance the simulation by one tick. */
    public void step() {
        tick++;
        for (Player p : players.values()) {
            p.inputBudget = Math.min(MAX_BUDGET, p.inputBudget + BUDGET_PER_TICK);

            // Speed hack defense: a client that sends 10x the inputs doesn't move
            // 10x faster. Extra inputs wait in the queue until budget frees up.
            while (p.inputBudget >= 1 && !p.pending.isEmpty()) {
                InputCommand in = p.pending.pollFirst();
                applyMovement(p, in.dx(), in.dy());
                p.lastProcessedSeq = in.seq();
                p.inputBudget -= 1;
            }
        }
        collectCoins();
    }

    /**
     * Movement for one frame. The browser client runs the same math for
     * prediction, so if you change this, change client/index.html too.
     */
    static void applyMovement(Player p, double dx, double dy) {
        double len = Math.hypot(dx, dy);
        if (len > 1) {
            // Clamp instead of normalize, so analog input below full tilt still works,
            // but a client sending (50, 0) only gets full speed.
            dx /= len;
            dy /= len;
        }
        p.x = clamp(p.x + dx * SPEED * FRAME_DT, PLAYER_RADIUS, WIDTH - PLAYER_RADIUS);
        p.y = clamp(p.y + dy * SPEED * FRAME_DT, PLAYER_RADIUS, HEIGHT - PLAYER_RADIUS);
    }

    private void collectCoins() {
        double reach = PLAYER_RADIUS + COIN_RADIUS;
        List<Integer> taken = new ArrayList<>();
        for (CoinView c : coins.values()) {
            // First player found in range gets it. Pickups are decided here from
            // server positions, so a client can't just claim it touched a coin.
            for (Player p : players.values()) {
                if (Math.hypot(p.x - c.x(), p.y - c.y()) <= reach) {
                    p.score++;
                    taken.add(c.id());
                    break;
                }
            }
        }
        taken.forEach(coins::remove);
        while (coins.size() < COIN_COUNT) {
            spawnCoin();
        }
    }

    private void spawnCoin() {
        double margin = COIN_RADIUS * 2;
        double x = margin + random.nextDouble() * (WIDTH - margin * 2);
        double y = margin + random.nextDouble() * (HEIGHT - margin * 2);
        int id = nextCoinId++;
        coins.put(id, new CoinView(id, x, y));
    }

    public Snapshot snapshot() {
        List<PlayerView> views = new ArrayList<>();
        for (Player p : players.values()) {
            views.add(new PlayerView(p.id, round(p.x), round(p.y), p.score, p.lastProcessedSeq));
        }
        return new Snapshot("snapshot", tick, views, new ArrayList<>(coins.values()));
    }

    // --- accessors used by tests and the server ---

    public long tick() {
        return tick;
    }

    Player player(int id) {
        return players.get(id);
    }

    Collection<CoinView> coins() {
        return coins.values();
    }

    /** Test hook: place a coin at an exact spot. */
    void putCoin(double x, double y) {
        int id = nextCoinId++;
        coins.put(id, new CoinView(id, x, y));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
