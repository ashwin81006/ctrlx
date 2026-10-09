
import java.io.*;
import java.net.*;

public class Client {
    public static void main(String[] args) {
        try (Socket socket = new Socket("127.0.0.1", 6060);
             BufferedReader in = new BufferedReader(
                 new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(
                 socket.getOutputStream(), true);
             BufferedReader console = new BufferedReader(
                 new InputStreamReader(System.in))) {

            out.println("{\"type\":\"HELLO\",\"version\":1}");

            String response = in.readLine();
            System.out.println("Server: " + response);

            if (!"{\"type\":\"WELCOME\",\"version\":1}".equals(response)) {
                System.out.println("Handshake failed");
                return;
            }

            System.out.println("CTRLX connected. Type ping or exit.");

            String input;

            while ((input = console.readLine()) != null) {
                if (input.equalsIgnoreCase("exit")) {
                    break;
                }

                if (input.equalsIgnoreCase("ping")) {
                    out.println("{\"type\":\"PING\"}");
                    System.out.println("Server: " + in.readLine());
                }
            }
        } catch (IOException e) {
            System.out.println("Connection failed: " + e.getMessage());
        }
    }
}
