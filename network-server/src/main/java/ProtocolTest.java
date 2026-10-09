
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ProtocolTest {
    public static void main(String[] args) throws Exception {
        try (
            Socket socket = new Socket("127.0.0.1", 6060);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    socket.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );
            PrintWriter out = new PrintWriter(
                socket.getOutputStream(),
                true,
                StandardCharsets.UTF_8
            )
        ) {
            out.println("{\"type\":\"HELLO\",\"version\":1}");
            System.out.println("Handshake: " + in.readLine());

            out.println("{\"type\":\"PING\"}");
            System.out.println("Ping: " + in.readLine());

            out.println("{\"type\":\"STATS\"}");
            System.out.println("Stats: " + in.readLine());

            out.println("{\"type\":\"UNKNOWN\"}");
            System.out.println("Unknown: " + in.readLine());

            out.println("{invalid json}");
            System.out.println("Malformed: " + in.readLine());
        }
    }
}
