import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.SQLOutput;
import java.util.*;
import java.util.concurrent.*;

import java.io.*;
import java.net.*;
import java.util.*;


// The class Peer implements the Runnable interface which means it can be used to create a thread
public class Peer implements Runnable {

    // Declaration of class fields
    private final String name;
    private final ServerSocket serverSocket;
    private final Set<Connection> connections;
    private final Set<String> neighbors;
    private Thread thread;
    private boolean shouldTerminate = false;
    private ConcurrentHashMap<String, HashSet<String>> pendingSearches;
    private final ConcurrentHashMap<String, List<String>> files;
    private volatile String filename_keyword;
    private final int port;

    // Peer constructor
    public Peer(String name, int port, String directoryPath) throws IOException {
        this.name = name;
        this.port = port;
        serverSocket = new ServerSocket(port);
        connections = Collections.synchronizedSet(new HashSet<>());
        neighbors = Collections.synchronizedSet(new HashSet<>());
        pendingSearches = new ConcurrentHashMap<>();
        files = new ConcurrentHashMap<>();
        updateFilesFromDirectory(directoryPath);
        this.filename_keyword = null;
    }

    public String getFilename() {
        return filename_keyword;
    }

    public void clearPendingSearches() {
        pendingSearches = new ConcurrentHashMap<>();
    }

    // This function starts a new thread that monitors a directory for changes
    // Initializes a new thread
    // Creates a WatchService to monitor the directory
    // Prints out the current files in the directory
    // Waits for key events (changes in the directory), then updates the files map accordingly
    public void startWatchingDirectory(String directoryPath) {
        thread = new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                Path path = Paths.get(directoryPath);
                path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

                updateFilesFromDirectory(directoryPath);

                while (!shouldTerminate) {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        String fileName = ev.context().toString();
                        updateFilesMap(fileName, readContentFromFile(fileName, path));
                    }
                    key.reset();
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        });
        thread.start();
    }

    //reading contents from file
    private List<String> readContentFromFile(String fileName, Path path) throws IOException {
        Path filePath = path.resolve(fileName);
        String content = new String(Files.readAllBytes(filePath));
        return Arrays.asList(content.split(","));
    }

    private void updateFilesMap(String fileName, List<String> contentList) {
        files.put(fileName, contentList);
    }

    //Periodically update the file map
    private void updateFilesFromDirectory(String directoryPath) throws IOException {
        File directory = new File(directoryPath);
        File[] filesInDirectory = directory.listFiles();
        if (filesInDirectory != null) {
            for (File file : filesInDirectory) {
                if (file.isFile()) {
                    String fileName = file.getName();
                    List<String> contentList = readContentFromFile(fileName, Paths.get(directoryPath));
                    files.put(fileName, contentList);
                }
            }
        }
    }

    // This function starts the peer's thread if it's not already running
    public void start() {
        if (thread == null) {
            thread = new Thread(this);
            thread.start();
        }
    }

    public String getName() {
        return name;
    }
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    // This function runs continuously, listening for new connections and starting new threads for each one
    // Continuously accepts new socket connections and starts new threads for each connection
    @Override
    public void run() {
        try {
            while (!shouldTerminate) {
                Socket socket = serverSocket.accept();
                Connection connection = new Connection(socket,this);
                connections.add(connection);
                new Thread(connection).start();

            }
        } catch (SocketException e) {
            if (shouldTerminate) {
                return;
            } else {
                throw new UncheckedIOException(e);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // This function sends a shutdown signal to the peer, closes all connections, and interrupts the thread
    // Sets the termination flag to true, shuts down all connections, closes the server socket, and interrupts the thread
    public void shutdown() {
        shouldTerminate = true;
        shutdownConnections();
        try {
            this.serverSocket.close();
        } catch (IOException e) {
            System.out.println("Error closing server socket: " + e.getMessage());
        }
        if (thread != null) {
            thread.interrupt();
        }
    }
    private void shutdownConnections() {
        for (Connection connection : connections) {
            connection.shutdown();
        }
    }

    // This function attempts to establish a new connection with a given host and port
    // Creates a new socket and starts a new connection thread
    public void connect(String host, int port) {
        try {
            Socket socket = new Socket(host, port);
            Connection connection = new Connection(socket, this);
            connections.add(connection);
            new Thread(connection).start();
            neighbors.add(host + ":" + port);
        } catch (IOException e) {
            System.out.println("Failed to connect to " + host + ":" + port + ".");
        }
    }

    // This function searches for a file in the peer's directory, then forwards the search to all its connections
    // This method performs a search operation for a file across all connected peers.
    // It first checks if the file exists locally, if it does, it adds the filename to the
    // pending searches and returns the pending searches.
    // If it doesn't exist, it sends a "SEARCH" message to all connected peers.
    // A TimerTask is created that will be executed after the specified hop count * 1000 milliseconds.
    public ConcurrentHashMap<String, HashSet<String>> search(String filename, int hopCount) {
        // Checks if the file exists locally, otherwise forwards the search request to all its connections
        if (files.containsKey(filename)) {
            pendingSearches.put(filename, new HashSet<>());
            System.out.println("File " + filename + " found locally in " + name);
            return pendingSearches;
        }

        if(!pendingSearches.containsKey(filename))  pendingSearches.put(filename, new HashSet<>());  // CHANGED: pendingSearches now holds a list of replies
        for (Connection connection : connections) {
            connection.sendMessage("SEARCH:" + filename + ":" + hopCount);  // CHANGED: Include hop count in message
        }

        // NEW: Timer to print replies after specified period
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {

            }
        }, hopCount * 1000);

        return pendingSearches;
    }

    // This method handles a reply received from a search operation.
    // If the filename is in the pending searches, the reply is added to the set of replies.
    // If the filename is not in the pending searches, the reply is forwarded to other connections.
    public void handleReply(String filename, String reply) {
        if (pendingSearches.containsKey(filename)) {
            pendingSearches.get(filename).add(reply);
        } else {
            for (Connection connection : connections) {
                if (connection.hasSeenMessage(filename)) {
                    connection.sendMessage("REPLY:" + filename + ":" + reply);
                    return;
                }
            }
        }
    }

    // This function acts as a client, sending a file download request to a server and writing the downloaded file to disk
    // Sends a file download request to the server, then writes the downloaded file to disk
    public void client(String filename, String hostname, int port) throws IOException {
        try{
            Socket socketP1 = new Socket(hostname, port);

            BufferedReader inP1 = new BufferedReader(new InputStreamReader(socketP1.getInputStream()));
            PrintWriter outP1 = new PrintWriter(socketP1.getOutputStream(), true);
            outP1.println("FILE_DOWNLOAD");

            outP1.println(filename);
            writeFileContents(filename, inP1);
//            socketP1.close();
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    // This block writes the file content received from the server to a local file
    private static void writeFileContents(String fileName, BufferedReader in) throws IOException {
        try {
            String directoryPath = System.getProperty("user.dir");
            String filename = fileName;
            File directory = new File(directoryPath);
            File file = new File(directory, filename);
            FileWriter fileWriter = new FileWriter(file);

            String line;
            while ((line = in.readLine()) != null) {
                // Terminate the loop if the termination signal is received
                if (line.equals("END_OF_FILE")) {
                    break;
                }
                fileWriter.write(line);
                fileWriter.write(System.lineSeparator());
            }
            fileWriter.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // This block reads a file and sends its contents over the socket
    private static void sendFileContents(String fileName, PrintWriter out) throws IOException {
        try {

            File file = new File(fileName);
            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] buffer = new byte[100];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                out.println(new String(buffer, 0, bytesRead));
            }
            out.println("END_OF_FILE");
            out.flush();
            fileInputStream.close();

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    // Connection class that implements the Runnable interface.
    // A connection object represents a connection with a peer.
    // It contains a socket for communication, a BufferedReader and a BufferedWriter for reading from and writing to the socket,
    // a termination flag to indicate whether the connection should terminate,
    // a set of seen messages to prevent processing the same message multiple times, and
    // a reference to the parent Peer object.
    private class Connection implements Runnable {
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;
        private boolean shouldTerminate = false;
        private final Set<String> seenMessages;  // NEW: Set of seen messages
        private final Peer parent;

        Connection(Socket socket, Peer parent) throws IOException {
            this.socket = socket;
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            seenMessages = Collections.synchronizedSet(new HashSet<>());
            this.parent = parent;
        }

        // Method to check if the connection is active. It returns true if the socket is not closed.
        boolean isActive() {
            return !socket.isClosed();
        }

        // Method to get the address of the connection. It returns a string in the format "hostname:port".
        String getAddress() {
            return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        }

        // Method that sends an update message to all other connections when a new keyword is set.
        // It sets the parent's filename_keyword to the new keyword and sends a "KEYWORD_UPDATE" message to all connections.
        public void sendKeywordUpdate(String newKeyword) {
            parent.filename_keyword = newKeyword;
            for (Connection connection : connections) {
                connection.sendMessage("KEYWORD_UPDATE:" + newKeyword);
            }
        }

        // Method to check if the files contains a search value.
        // If a file contains the search value, it sets the filename_keyword to the filename and sends an update message.
        // If no file contains the search value, it sends an update message with the search value.
        private boolean containsValueInFiles(String searchValue, ConcurrentHashMap<String, List<String>> files) {
            for (Map.Entry<String, List<String>> entry : files.entrySet()) {
                if (entry.getValue().contains(searchValue)) {
                    parent.filename_keyword = entry.getKey();
                    sendKeywordUpdate(entry.getKey());
                    return true;
                }
            }
            sendKeywordUpdate(searchValue);
            return false;
        }

        // The run method is called when the thread for this connection is started.
        // It reads messages from the input stream and processes them based on their type ("SEARCH", "REPLY", "FILE_DOWNLOAD").
        @Override
        public void run() {
            try {
                //The main loop of this method continues until either shouldTerminate
                // becomes true or the socket gets disconnected. It reads a message
                // from the input stream connected to the socket.
                while (!shouldTerminate && socket.isConnected()) {
                    String message = in.readLine();
                    if (message == null) {
                        break;
                    }

                    //If the incoming message is "FILE_DOWNLOAD", the server prepares
                    // to send the requested file back to the client. It gets the filename
                    // from the next line, constructs the file path by combining the current
                    // directory and the filename, and then sends the file contents to the client.
                    if (message.equals("FILE_DOWNLOAD")) {
                        String fileName = in.readLine();
                        String currentDirectory = System.getProperty("user.dir");
                        String fileNameRequest = currentDirectory+"/"+fileName;
                        parent.sendFileContents(fileNameRequest, new PrintWriter(socket.getOutputStream(), true));
                        break;  // Exit the loop after sending the file
                    }

                    //If the message begins with "SEARCH" and if the second part of the message
                    // (considered as a unique identifier) hasn't been processed before, it checks
                    // whether the searched keyword exists in the files or in the value of the files.
                    // If yes, it sends a "REPLY" message containing the keyword, the hostname, and the
                    // port number. If the 'hop count' (the remaining number of nodes this message is
                    // allowed to be forwarded to) is greater than 1, it forwards the search request
                    // to all other connected peers, decreasing the hop count by 1 each time.
                    String[] parts = message.split(":");
                    if (parts[0].equals("SEARCH") && !seenMessages.contains(parts[1])) {
                        seenMessages.add(parts[1]);
                        if (parent.files.containsKey(parts[1]) ||  containsValueInFiles(parts[1], parent.files)) {
                            if(!containsValueInFiles(parts[1], parent.files)){
                                sendKeywordUpdate(parts[1]);
                            }
                            InetAddress localhost = InetAddress.getLocalHost();
                            String hostname = localhost.getHostName();
                            sendMessage("REPLY:" + parts[1] + ":" + hostname + ":" + parent.getPort());
                        }
                        if (Integer.parseInt(parts[2]) > 1) {  // NEW: If hop count is not exhausted, forward the message
                            for (Connection connection : parent.connections) {
                                if (connection != this) {
                                    connection.sendMessage(parts[0] + ":" + parts[1] + ":" + (Integer.parseInt(parts[2]) - 1));  // DECREASE the hop count
                                }
                            }
                        }
                    } else if (parts[0].equals("REPLY")) {
                        parent.handleReply(parts[1], parts[2] + ":" + parts[3]);
                    } else if(parts[0].equals("KEYWORD_UPDATE")) {
                        parent.filename_keyword = parts[1];
                    }
                }
            } catch (IOException e) {
                if (shouldTerminate) {
                    System.out.println("Socket closed as expected due to shutdown");
                } else {
                    throw new UncheckedIOException(e);
                }
            }
        }

        boolean hasSeenMessage(String message) {
            return seenMessages.contains(message);
        }

        void close() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void shutdown() {
            shouldTerminate = true;
            try {
                socket.close();
            } catch (IOException e) {
                System.out.println("Error closing client socket: " + e.getMessage());
            }
        }

        void sendMessage(String message) {
            synchronized (out) {  // ADDED: Synchronization to prevent message mix-ups
                try {
                    out.write(message);
                    out.newLine();
                    out.flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 2 && args.length != 3) {
            System.out.println("Usage: java PeerMain <peer name> <port number> [host:port to connect to]");
            return;
        }

        String peerName = args[0];
        int port = Integer.parseInt(args[1]);

        //Initialsation of Peer instance
        Peer peer = new Peer(peerName, port, System.getProperty("user.dir")+"/");
        peer.start();
        peer.startWatchingDirectory(System.getProperty("user.dir")+"/");

        if (args.length == 3) {
            String[] parts = args[2].split(":");
            String host = parts[0];
            int connectPort = Integer.parseInt(parts[1]);
            peer.connect(host, connectPort);
        }

        Scanner scanner = new Scanner(System.in);

        startLoop: while (true) {

            System.out.println("Enter filename to search from peer");
            String filename = null;

            ConcurrentHashMap<String, HashSet<String>> result = null;
            int hopCount = 1;
            filename = scanner.nextLine();
            if (filename.equalsIgnoreCase("done")) {
                break; // Exit the loop if the user types 'done'
            }
            long startTime = System.currentTimeMillis();

            //Goes in a loop with hop count increasing on each iteration.
            while (hopCount <= 16) {
                result = peer.search(filename, hopCount);

                Thread.sleep(5000);
                if (result != null && !result.isEmpty()) {
                    System.out.println("Search results for peer " + peer.getName() + ":");
                    for (Map.Entry<String, HashSet<String>> entry : result.entrySet()) {
                        System.out.println("Filename: " + entry.getKey() + ", Peers: " + entry.getValue());
                        if (entry.getValue().size() != 0) break;
                        else{
                            System.out.println("No search results.");
                            continue startLoop;
                        }
                    }
                }
                hopCount *= 2;
            }
//            System.out.println("Hop-count is:"+ Integer.toString(hopCount));

            long endTime = System.currentTimeMillis();
            long timeElapsed = endTime - startTime;
            System.out.println("Execution time in milliseconds: " + timeElapsed);
            peer.clearPendingSearches();
            Thread.sleep(5000);
            filename = peer.getFilename();

            Scanner scanner_int = new Scanner(System.in);
            System.out.println("Enter the index of file transfer");
            int selection = scanner_int.nextInt();

            List<HashSet<String>> valuesList = new ArrayList<>(result.values());
            int index = selection;
            String selectedString = null;
            if (index >= 0 && index < valuesList.get(0).size()) {
                HashSet<String> selectedSet = valuesList.get(0);
                int i = 0;
                System.out.println("HashSet elements at index " + index + ":");
                for (String element : selectedSet) {
                    if (i == index) selectedString = element;
                    i += 1;
                }
            } else {
                System.out.println("Index out of range.");
            }
            System.out.println(selectedString);
            int portServer = Integer.parseInt(selectedString.split(":")[1]);
            peer.client(filename, selectedString.split(":")[0], portServer);
            System.out.println("Done");
        }
        scanner.close();
    }
}

