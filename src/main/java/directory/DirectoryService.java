package directory;

import common.Constants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DirectoryService {
    private static final int MAX_PACKET_SIZE = 1024;
    private final ConcurrentHashMap<String, ServerInfo> servers = new ConcurrentHashMap<>();
    private boolean running = true;

    public static void main(String[] args) {
        new DirectoryService().start();
    }

    public void start() {
        System.out.println("Directory Service started on port " + Constants.DIRECTORY_SERVICE_UDP_PORT);

        // Start heartbeat monitor thread
        new Thread(this::monitorHeartbeats).start();

        try (DatagramSocket socket = new DatagramSocket(Constants.DIRECTORY_SERVICE_UDP_PORT)) {
            byte[] buffer = new byte[MAX_PACKET_SIZE];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                new Thread(() -> handleRequest(socket, packet)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleRequest(DatagramSocket socket, DatagramPacket packet) {
        String message = new String(packet.getData(), 0, packet.getLength()).trim();
        String[] parts = message.split(" ");
        String command = parts[0];

        System.out.println("Received: " + message + " from " + packet.getAddress() + ":" + packet.getPort());

        switch (command) {
            case "REGISTER":
            case "HEARTBEAT":
                handleHeartbeat(parts, packet.getAddress(), packet.getPort());
                break;
            case "GET_SERVER":
                handleGetServer(socket, packet);
                break;
            default:
                System.out.println("Unknown command: " + command);
        }
    }

    private void handleHeartbeat(String[] parts, InetAddress address, int port) {
        if (parts.length < 4)
            return; // Expected: HEARTBEAT <tcp_port> <db_version> <sync_port>

        try {
            int tcpPort = Integer.parseInt(parts[1]);
            int dbVersion = Integer.parseInt(parts[2]);
            int syncPort = Integer.parseInt(parts[3]);

            String key = address.getHostAddress() + ":" + tcpPort;
            servers.compute(key, (k, v) -> {
                if (v == null) {
                    System.out.println("New server registered: " + key);
                    return new ServerInfo(address, tcpPort, syncPort, dbVersion, System.currentTimeMillis(),
                            System.currentTimeMillis());
                } else {
                    v.lastHeartbeat = System.currentTimeMillis();
                    v.dbVersion = dbVersion;
                    return v;
                }
            });
        } catch (NumberFormatException e) {
            System.err.println("Invalid heartbeat format");
        }
    }

    private void handleGetServer(DatagramSocket socket, DatagramPacket packet) {
        ServerInfo bestServer = getOldestServer();
        String response;

        if (bestServer != null) {
            response = "SERVER " + bestServer.address.getHostAddress() + " " + bestServer.tcpPort + " "
                    + bestServer.syncPort;
        } else {
            response = "NO_SERVERS";
        }

        byte[] data = response.getBytes();
        DatagramPacket responsePacket = new DatagramPacket(
                data, data.length, packet.getAddress(), packet.getPort());

        try {
            socket.send(responsePacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private ServerInfo getOldestServer() {
        return servers.values().stream()
                .min((s1, s2) -> Long.compare(s1.registrationTime, s2.registrationTime))
                .orElse(null);
    }

    private void monitorHeartbeats() {
        while (running) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, ServerInfo>> it = servers.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<String, ServerInfo> entry = it.next();
                if (now - entry.getValue().lastHeartbeat > Constants.DIRECTORY_SERVICE_TIMEOUT) {
                    System.out.println("Removing dead server: " + entry.getKey());
                    it.remove();
                }
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class ServerInfo {
        InetAddress address;
        int tcpPort;
        int syncPort;
        int dbVersion;
        long lastHeartbeat;
        long registrationTime;

        public ServerInfo(InetAddress address, int tcpPort, int syncPort, int dbVersion, long lastHeartbeat,
                long registrationTime) {
            this.address = address;
            this.tcpPort = tcpPort;
            this.syncPort = syncPort;
            this.dbVersion = dbVersion;
            this.lastHeartbeat = lastHeartbeat;
            this.registrationTime = registrationTime;
        }
    }
}
