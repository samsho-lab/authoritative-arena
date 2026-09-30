package io.github.samsholab.arena;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        GameServer server = new GameServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.shutdown();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        server.start();
    }
}
