import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Server {
    public static void main(String[] args) {
        int port = 5000;

        try {
            BufferedReader keyboard = new BufferedReader(
                    new InputStreamReader(System.in)
            );

            System.out.print("Enter server username: ");
            String serverUsername = keyboard.readLine();
            if (serverUsername.equalsIgnoreCase("admin") || serverUsername.equalsIgnoreCase("you")) {
            	System.out.println("Username cannot be 'admin' or 'you'!");
            	return;
            }

            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("Server started on port " + port);
                System.out.println("Waiting for client...");

                Socket socket = serverSocket.accept();
                System.out.println("Client connected: " + socket.getInetAddress());

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream())
                );

                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

                Thread receiveThread = new Thread(() -> {
                    try {
                        String message;
                        while ((message = in.readLine()) != null) {
                            try {
                                String decryptedMessage = CryptoUtils.decrypt(message);
                                System.out.println(decryptedMessage);
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
                        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                        String fullMessage = "[" + time + "] " + "[host] " + serverUsername + ": " + serverMessage;
                        String encryptedMessage = CryptoUtils.encrypt(fullMessage);
                        out.println(encryptedMessage);
                    } catch (Exception e) {
                        System.out.println("Encryption error: " + e.getMessage());
                    }
                }

                socket.close();
            }

        } catch (Exception e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }
}
