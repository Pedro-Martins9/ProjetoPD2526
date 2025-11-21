package server;

import common.Constants;
import common.Constants;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Server {
    private DatabaseManager dbManager;
    private int tcpPort;
    private int syncPort;
    private boolean isPrimary = false;
    private AtomicBoolean running = new AtomicBoolean(true);
    private String dbPath;

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java server.Server <db_path> <tcp_port> <sync_port>");
            return;
        }
        new Server(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2])).start();
    }

    public Server(String dbPath, int tcpPort, int syncPort) {
        this.dbPath = dbPath;
        this.tcpPort = tcpPort;
        this.syncPort = syncPort;
        this.dbManager = new DatabaseManager(dbPath);
    }

    public void start() {
        try {
            dbManager.connect();

            // Check for existing Primary
            String primaryInfo = getPrimaryFromDirectory();

            if (primaryInfo == null || primaryInfo.equals("NO_SERVERS")) {
                System.out.println("No existing primary found. I am Primary.");
                isPrimary = true;
            } else {
                // Format: SERVER <ip> <tcp_port> <sync_port>
                String[] parts = primaryInfo.split(" ");
                String ip = parts[1];
                int syncPort = Integer.parseInt(parts[3]);

                System.out.println("Found Primary at " + ip + ":" + parts[2]);
                isPrimary = false;
                syncDatabase(ip, syncPort);
            }

            // Start threads
            new Thread(this::sendHeartbeats).start();
            new Thread(this::listenMulticast).start();
            new Thread(this::listenSync).start();
            new Thread(this::listenClients).start();

            // Keep main thread alive
            while (running.get()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getPrimaryFromDirectory() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            String msg = "GET_SERVER";
            byte[] data = msg.getBytes();
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, InetAddress.getByName("localhost"), Constants.DIRECTORY_SERVICE_UDP_PORT);
            socket.send(packet);

            byte[] buffer = new byte[1024];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(response);
                return new String(response.getData(), 0, response.getLength());
            } catch (SocketTimeoutException e) {
                // No response or timeout
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void syncDatabase(String ip, int port) {
        System.out.println("Syncing database from " + ip + ":" + port);
        try (Socket socket = new Socket(ip, port);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            out.writeUTF("SYNC_REQUEST");

            long fileSize = in.readLong();
            if (fileSize > 0) {
                dbManager.close(); // Close DB to allow overwrite

                // Backup existing DB if exists
                File currentDb = new File(dbManager.getDbPath());
                if (currentDb.exists()) {
                    File backup = new File(dbManager.getDbPath() + ".bak");
                    currentDb.renameTo(backup);
                }

                try (FileOutputStream fos = new FileOutputStream(dbManager.getDbPath())) {
                    byte[] buffer = new byte[4096];
                    long totalRead = 0;
                    int read;
                    while (totalRead < fileSize
                            && (read = in.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalRead))) != -1) {
                        fos.write(buffer, 0, read);
                        totalRead += read;
                    }
                }

                dbManager.connect(); // Reopen DB
                System.out.println("Database synced successfully.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendHeartbeats() {
        try (DatagramSocket udpSocket = new DatagramSocket();
                MulticastSocket multicastSocket = new MulticastSocket()) {

            InetAddress directoryAddr = InetAddress.getByName("localhost");
            InetAddress multicastGroup = InetAddress.getByName(Constants.MULTICAST_GROUP);

            while (running.get()) {
                // 1. UDP to Directory Service
                // Format: HEARTBEAT <tcp_port> <db_version> <sync_port>
                String msg = String.format("HEARTBEAT %d %d %d", tcpPort, dbManager.getDbVersion(), syncPort);
                byte[] data = msg.getBytes();

                DatagramPacket packet = new DatagramPacket(
                        data, data.length, directoryAddr, Constants.DIRECTORY_SERVICE_UDP_PORT);
                udpSocket.send(packet);

                // 2. Multicast to Cluster
                DatagramPacket multiPacket = new DatagramPacket(
                        data, data.length, multicastGroup, Constants.MULTICAST_PORT);
                multicastSocket.send(multiPacket);

                Thread.sleep(Constants.SERVER_HEARTBEAT_INTERVAL);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenMulticast() {
        try (MulticastSocket socket = new MulticastSocket(Constants.MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(Constants.MULTICAST_GROUP);
            NetworkInterface netIf = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
            socket.joinGroup(new InetSocketAddress(group, Constants.MULTICAST_PORT), netIf);

            byte[] buffer = new byte[4096];
            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength());
                if (msg.startsWith("UPDATE")) {
                    handleDbUpdate(msg);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleDbUpdate(String msg) {
        if (isPrimary)
            return; // Primary ignores its own updates (or we filter by sender)

        // Parse UPDATE <version> <sql>
        // msg = "UPDATE 5 INSERT INTO ..."
        try {
            int firstSpace = msg.indexOf(' ');
            int secondSpace = msg.indexOf(' ', firstSpace + 1);

            if (firstSpace == -1 || secondSpace == -1)
                return;

            int version = Integer.parseInt(msg.substring(firstSpace + 1, secondSpace));
            String sql = msg.substring(secondSpace + 1);

            int localVersion = dbManager.getDbVersion();

            if (version == localVersion + 1) {
                System.out.println("Applying update version " + version);
                dbManager.executeUpdate(sql); // This increments local version
            } else if (version > localVersion + 1) {
                System.err.println(
                        "Consistency lost! Missed updates (Local: " + localVersion + ", Remote: " + version + ").");
                System.err.println("Shutting down as per consistency requirements.");
                running.set(false);
                System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenSync() {
        try (ServerSocket serverSocket = new ServerSocket(syncPort)) {
            while (running.get()) {
                Socket client = serverSocket.accept();
                new Thread(() -> handleSyncRequest(client)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleSyncRequest(Socket client) {
        try (DataInputStream in = new DataInputStream(client.getInputStream());
                DataOutputStream out = new DataOutputStream(client.getOutputStream())) {

            String request = in.readUTF();
            if ("SYNC_REQUEST".equals(request)) {
                File dbFile = new File(dbManager.getDbPath());
                if (dbFile.exists()) {
                    out.writeLong(dbFile.length());
                    try (FileInputStream fis = new FileInputStream(dbFile)) {
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                } else {
                    out.writeLong(0);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listenClients() {
        try (ServerSocket serverSocket = new ServerSocket(tcpPort)) {
            while (running.get()) {
                Socket client = serverSocket.accept();
                new Thread(new ClientHandler(client, dbManager, this)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void executeUpdate(String sql) {
        try {
            // 1. Execute locally
            dbManager.executeUpdate(sql);

            // 2. Broadcast to cluster
            // Format: UPDATE <version> <sql>
            String msg = "UPDATE " + dbManager.getDbVersion() + " " + sql;
            byte[] data = msg.getBytes();

            try (DatagramSocket socket = new DatagramSocket()) {
                DatagramPacket packet = new DatagramPacket(
                        data, data.length, InetAddress.getByName(Constants.MULTICAST_GROUP), Constants.MULTICAST_PORT);
                socket.send(packet);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
