import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {
    public static void main(String[] args) {
        int port = 5000;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port);
            System.out.println("Waiting for client...");

            Socket socket = serverSocket.accept();
            System.out.println("Client connected: " + socket.getInetAddress());

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream())
            );

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            BufferedReader keyboard = new BufferedReader(
                    new InputStreamReader(System.in)
            );

            Thread receiveThread = new Thread(() -> {
                try {
                    String message;
                    while ((message = in.readLine()) != null) {
                        try {
                        	String decryptedMessage = CryptoUtils.decrypt(message);
                        	System.out.println("Client: " + decryptedMessage);
                        } catch (Exception e) {
                        	System.out.println("Received invalid encrypted message.");
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Connection closed.");
                }
            });

            receiveThread.start();

            String serverMessage;
            while ((serverMessage = keyboard.readLine()) != null) {
                try {
                	String encryptedMessage = CryptoUtils.encrypt(serverMessage);
                	out.println(encryptedMessage);
                } catch (Exception e) {
                	System.out.println("Encryption error: " + e.getMessage());
                }
            }

            socket.close();

        } catch (Exception e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }
}
