import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class Client {
    public static void main(String[] args) {
    	
        String host = "10.0.2.15";
        int port = 5000;

        try (Socket socket = new Socket(host, port)) {
            System.out.println("Connected to server.");

            BufferedReader in = new BufferedReader(
            	new InputStreamReader(socket.getInputStream())
            );

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

			BufferedReader keyboard = new BufferedReader(
			    		new InputStreamReader(System.in)
			    	);
			
			    	System.out.println("Enter username: ");
			    	String username = keyboard.readLine();

            Thread receiveThread = new Thread(() -> {
                try {
                    String message;
                    while ((message = in.readLine()) != null) {
                        try {
                        	String decryptedMessage = CryptoUtils.decrypt(message);
                        	System.out.println("Server: " + decryptedMessage);
                        } catch (Exception e) {
                        	System.out.println("Received invalid encrypted message.");
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Connection closed.");
                }
            });

            receiveThread.start();

            String clientMessage;
            while ((clientMessage = keyboard.readLine()) != null) {
                try {
                	String fullMessage = username + ": " + clientMessage;
                	String encryptedMessage = CryptoUtils.encrypt(fullMessage);
                	out.println(encryptedMessage);
                } catch (Exception e) {
                	System.out.println("Encryption error: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.out.println("Client error: " + e.getMessage());
        }
    }
}
