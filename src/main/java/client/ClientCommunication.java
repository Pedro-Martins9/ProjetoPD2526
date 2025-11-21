package client;

import common.Constants;
import common.Message;
import java.io.*;
import java.net.*;

public class ClientCommunication {
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private boolean running = true;
    private final ClientUI ui;

    public ClientCommunication(ClientUI ui) {
        this.ui = ui;
    }

    public boolean connect() {
        try {
            InetSocketAddress primary = getPrimaryServer();
            if (primary == null)
                return false;

            return connectToAddress(primary);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean connectToAddress(InetSocketAddress address) throws IOException {
        socket = new Socket(address.getAddress(), address.getPort());
        out = new ObjectOutputStream(socket.getOutputStream());
        in = new ObjectInputStream(socket.getInputStream());

        // Start listener thread
        new Thread(this::listenForMessages).start();
        return true;
    }

    private void listenForMessages() {
        try {
            while (running && !socket.isClosed()) {
                Message msg = (Message) in.readObject();
                ui.handleMessage(msg);
            }
        } catch (Exception e) {
            if (running) {
                ui.onConnectionLost();
            }
        }
    }

    public void sendRequest(Message request) {
        try {
            out.writeObject(request);
            out.flush();
        } catch (IOException e) {
            ui.onConnectionLost();
        }
    }

    public void reconnect() {
        closeConnection();

        int retries = 0;
        InetSocketAddress lastAddress = null;

        while (retries < 5 && running) {
            try {
                InetSocketAddress primary = getPrimaryServer();

                if (primary != null) {
                    if (primary.equals(lastAddress)) {
                        // Requirement: Wait 20 seconds if same server
                        Thread.sleep(20000);
                    } else {
                        Thread.sleep(2000);
                    }

                    if (connectToAddress(primary)) {
                        ui.onReconnected();
                        return;
                    }
                    lastAddress = primary;
                }
            } catch (Exception e) {
                // Retry
            }
            retries++;
        }
        ui.onFatalError("Could not reconnect to any server.");
    }

    private InetSocketAddress getPrimaryServer() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5000);
            String msg = "GET_SERVER";
            byte[] data = msg.getBytes();
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, InetAddress.getByName("localhost"), Constants.DIRECTORY_SERVICE_UDP_PORT);
            socket.send(packet);

            byte[] buffer = new byte[1024];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            String res = new String(response.getData(), 0, response.getLength());
            if (res.startsWith("SERVER")) {
                String[] parts = res.split(" ");
                return new InetSocketAddress(parts[1], Integer.parseInt(parts[2]));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public void close() {
        running = false;
        closeConnection();
    }

    private void closeConnection() {
        try {
            if (socket != null)
                socket.close();
        } catch (IOException e) {
            // Ignore
        }
    }
}
