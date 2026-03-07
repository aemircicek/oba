import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Client {
    public static void main(String[] args) {
    	
        String host = "192.168.1.3";
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
			    	if (username.equalsIgnoreCase("admin") || username.equalsIgnoreCase("you")) {
			    		System.out.println("Username cannot be 'admin' or  'you'!");
			    		return;
			    	}

            Thread receiveThread = new Thread(() -> {
                try {
                    String message;
                    while ((message = in.readLine()) != null) {
                        try {
                        	String decryptedMessage = CryptoUtils.decrypt(message);
                        	if (!decryptedMessage.contains(username  + ":")) {
                        		System.out.println(decryptedMessage);
                        	}
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
                	String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH.mm"));
                	String fullMessage = "[" + time + "] "  + username + ": " + clientMessage;
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
