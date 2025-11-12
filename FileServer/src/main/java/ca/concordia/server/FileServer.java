package ca.concordia.server;
import ca.concordia.filesystem.FileSystemManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

public class FileServer {

    private FileSystemManager fsManager;
    private int port;
    private static final AtomicInteger threadCounter = new AtomicInteger(0);
    public FileServer(int port, String fileSystemName, int totalSize){
        // Initialize the FileSystemManager
        FileSystemManager fsManager = new FileSystemManager(fileSystemName,
                10*128 );
        this.fsManager = fsManager;
        this.port = port;
    }

    public void start(){
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started. Listening on port " + port + "...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                int threadNum = threadCounter.incrementAndGet();
                System.out.println("Handling client: " + clientSocket + " (thread #" + threadNum + ")");

                // create a new thread to handle the client and pass the thread number
                Thread clientHandler = new Thread(() -> handleClient(clientSocket, threadNum), "ClientHandler-" + threadNum);
                clientHandler.start();
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Could not start server on port " + port);
        }
    }


    private void handleClient(Socket clientSocket, int threadNum) {
        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[thread #" + threadNum + "] Received from client: " + line);
                String[] parts = line.split(" ");
                String command = parts[0].toUpperCase();

                switch (command) {

                    case "CREATE":
                        if (parts.length < 2) {
                            writer.println("ERROR: Filename not provided");
                            break;
                        }
                        try {
                            fsManager.createFile(parts[1]);
                            writer.println("File created: " + parts[1]);
                        } catch (IllegalArgumentException e) {
                            writer.println("ERROR: " + e.getMessage());
                        }
                        writer.flush();
                        break;

                    case "DELETE":
                        if (parts.length < 2) {
                            writer.println("ERROR: Filename not provided");
                            break;
                        }
                        try {
                            fsManager.deleteFile(parts[1]);
                            writer.println("File deleted: " + parts[1]);
                        } catch (IllegalArgumentException e) {
                            writer.println("ERROR: " + e.getMessage());
                        }
                        writer.flush();
                        break;
                                        case "WRITE":
                        // WRITE <filename> <base64_contents>
                        if (parts.length < 3) {
                            writer.println("ERROR: Usage: WRITE <filename> <base64_contents>");
                            break;
                        }
                        try {
                            byte[] data = Base64.getDecoder().decode(parts[2]);
                            fsManager.writeFile(parts[1], data);
                            writer.println("File written: " + parts[1]);
                        } catch (IllegalArgumentException e) {
                            writer.println("ERROR: " + e.getMessage());
                        } catch (Exception e) {
                            writer.println("ERROR: Write failed: " + e.getMessage());
                        }
                        writer.flush();
                        break;

                    case "READ":
                        // READ <filename> -> returns base64 payload on single line
                        if (parts.length < 2) {
                            writer.println("ERROR: Filename not provided");
                            break;
                        }
                        try {
                            byte[] out = fsManager.readFile(parts[1]);
                            String b64 = Base64.getEncoder().encodeToString(out);
                            writer.println("DATA " + b64);
                        } catch (IllegalArgumentException e) {
                            writer.println("ERROR: " + e.getMessage());
                        } catch (Exception e) {
                            writer.println("ERROR: Read failed: " + e.getMessage());
                        }
                        writer.flush();
                        break;
    

                    case "LIST":
                        String[] filesArray = fsManager.listFiles();
                        String files = String.join(", ", filesArray);
                        writer.println("SUCCESS: File list: " + files);
                        writer.flush();
                        break;

                    case "QUIT":
                        writer.println("SUCCESS: Disconnecting.");
                        return;

                    default:
                        writer.println("ERROR: Unknown command.");
                        break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();

        } finally {
            try {
                clientSocket.close();
            } catch (Exception e) {
                // Ignore
            }
        }

    }

}

