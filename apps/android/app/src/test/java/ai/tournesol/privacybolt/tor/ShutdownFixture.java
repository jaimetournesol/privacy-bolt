package ai.tournesol.privacybolt.tor;

import java.net.ServerSocket;

/** A real child holding a socket while its shutdown hook finishes. */
public final class ShutdownFixture {
    public static void main(String[] args) throws Exception {
        long delay = Long.parseLong(args[0]);
        ServerSocket socket = new ServerSocket(0);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Thread.sleep(delay); } catch (InterruptedException ignored) { }
        }));
        System.out.println(socket.getLocalPort());
        System.out.flush();
        Thread.sleep(60_000);
        socket.close();
    }
}
