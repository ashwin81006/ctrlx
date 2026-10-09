
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {
    static final int PORT = 6060;
    static final AtomicInteger clients = new AtomicInteger();
    static final long startTime = System.nanoTime();
    static final ExecutorService pool = Executors.newCachedThreadPool();

    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("CTRLX server running on port " + PORT);

            while (true) {
                Socket socket = server.accept();
                pool.submit(() -> handleClient(socket));
            }
        }
    }

    static void handleClient(Socket socket) {
        boolean counted = false;

        try (
            Socket client = socket;
            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    client.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );
            PrintWriter out = new PrintWriter(
                client.getOutputStream(),
                true,
                StandardCharsets.UTF_8
            )
        ) {
            

            String line = in.readLine();

            if (line == null) {
                return;
            }

            JsonObject hello;

            try {
                hello = JsonParser.parseString(line).getAsJsonObject();
            } catch (Exception e) {
                sendError(out, "Invalid JSON object");
                return;
            }

            if (!hello.has("type")
                || !"HELLO".equals(hello.get("type").getAsString())
                || !hello.has("version")
                || !hello.get("version").isJsonPrimitive()
                || !hello.get("version").getAsJsonPrimitive().isNumber()
                || hello.get("version").getAsInt() != 1
                || hello.get("version").getAsDouble() != 1.0) {
                sendError(out, "Invalid handshake");
                return;
            }

            JsonObject welcome = new JsonObject();
            welcome.addProperty("type", "WELCOME");
            welcome.addProperty("version", 1);
            out.println(welcome);

            clients.incrementAndGet();
            counted = true;

            System.out.println("Client connected: " + client.getRemoteSocketAddress());

            while ((line = in.readLine()) != null) {
                JsonObject message;

                try {
                    message = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception e) {
                    sendError(out, "Invalid JSON object");
                    continue;
                }

                if (!message.has("type")
                    || !message.get("type").isJsonPrimitive()) {
                    sendError(out, "Missing message type");
                    continue;
                }

                String type = message.get("type").getAsString();

                if ("PING".equals(type)) {
                    JsonObject pong = new JsonObject();
                    pong.addProperty("type", "PONG");
                    out.println(pong);
                } else if ("STATS".equals(type)) {
                    JsonObject stats = new JsonObject();
                    stats.addProperty("type", "STATS");
                    stats.addProperty("connectedClients", clients.get());
                    stats.addProperty(
                        "uptimeSeconds",
                        (System.nanoTime() - startTime) / 1_000_000_000L
                    );
                    out.println(stats);
                } else {
                    sendError(out, "Unsupported message");
                }

                if (out.checkError()) {
                    break;
                }
            }
        } catch (Exception e) {
            System.out.println("Client disconnected: " + e.getMessage());
        } finally {
            if (counted) {
                clients.decrementAndGet();
            }
        }
    }

    static void sendError(PrintWriter out, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "ERROR");
        error.addProperty("message", message);
        out.println(error);
    }
}
