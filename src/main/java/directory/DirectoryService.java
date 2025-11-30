package directory;

import common.Constants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
    Classe que representa o serviço de diretoria
 */
public class DirectoryService {
    private static final int MAX_PACKET_SIZE = 1024;
    // Mapa de servidores ativos, chave = ip:porta, valor = ServerInfo
    private final ConcurrentHashMap<String, ServerInfo> servers = new ConcurrentHashMap<>();
    private boolean running = true;

    public static void main(String[] args) {
        new DirectoryService().start();
    }

    public void start() {
        System.out.println("Servico de diretoria iniciado na porta " + Constants.DIRECTORY_SERVICE_UDP_PORT);

        // inicia thread para gerir os heartbeats
        new Thread(this::monitorHeartbeats).start();

        // inicia socket para receber packets com port UDP pré definido
        try (DatagramSocket socket = new DatagramSocket(Constants.DIRECTORY_SERVICE_UDP_PORT)) {
            byte[] buffer = new byte[MAX_PACKET_SIZE];

            while (running) { // loop da thread principal apenas para receber packets
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                // cria thread para processar o packet
                new Thread(() -> handleRequest(socket, packet)).start();
            }
        } catch (IOException e) { // fecha socket e lança exception basico em caso de erro
            e.printStackTrace();
        }
    }

    private void handleRequest(DatagramSocket socket, DatagramPacket packet) {
        String message = new String(packet.getData(), 0, packet.getLength()).trim();
        String[] parts = message.split(" ");
        String command = parts[0];

        System.out.println("Recebido: " + message + " de " + packet.getAddress() + ":" + packet.getPort());

        switch (command) { // switch com base no tipo de mensagem
            case "HEARTBEAT":
                handleHeartbeat(parts, packet.getAddress(), packet.getPort());
                break;
            case "GET_SERVER": // comando para obter o servidor mais antigo
                handleGetServer(socket, packet);
                break;
            default:
                System.out.println("Comando desconhecido: " + command);
        }
    }

    private void handleHeartbeat(String[] parts, InetAddress address, int port) {
        if (parts.length < 4)
            return; // Mensagem diferente do esperado:
                    // HEARTBEAT <tcp_port> <db_version> <sync_port>

        try {
            int tcpPort = Integer.parseInt(parts[1]);
            int dbVersion = Integer.parseInt(parts[2]);
            int syncPort = Integer.parseInt(parts[3]);

            // chave do servidor que enviou o heartbeat
            String key = address.getHostAddress() + ":" + tcpPort;
            servers.compute(key, (k, v) -> {
                if (v == null) { // caso o servidor nao esteja registado
                    System.out.println("Novo servidor registado: " + key);
                    return new ServerInfo(address, tcpPort, syncPort, dbVersion, System.currentTimeMillis(),
                            System.currentTimeMillis());
                } else {
                    // caso o servidor esteja registado, atualiza os dados
                    v.lastHeartbeat = System.currentTimeMillis();
                    v.dbVersion = dbVersion;
                    return v;
                }
            });
        } catch (NumberFormatException e) {
            System.err.println("Heartbeat invalido");
        }
    }

    private void handleGetServer(DatagramSocket socket, DatagramPacket packet) {
        ServerInfo bestServer = getOldestServer();
        String response;

        // se existir um servidor registado devolve-o ou então devolve NO_SERVERS
        if (bestServer != null) {
            response = "SERVER " + bestServer.address.getHostAddress() + " " + bestServer.tcpPort + " "
                    + bestServer.syncPort;
        } else {
            response = "NO_SERVERS";
        }

        // prepara e envia a resposta
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
        // devolve o servidor mais antigo ou null se nao houver
        return servers.values().stream()
                .min((s1, s2) -> Long.compare(s1.registrationTime, s2.registrationTime))
                .orElse(null);
    }

    private void monitorHeartbeats() {
        while (running) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, ServerInfo>> it = servers.entrySet().iterator(); // itera sobre os servidores
                                                                                        // registados

            while (it.hasNext()) { // enquanto houver mais servidores registados
                Map.Entry<String, ServerInfo> entry = it.next();
                // se o servidor nao tiver enviado um heartbeat no espaco de tempo pre definido
                if (now - entry.getValue().lastHeartbeat > Constants.DIRECTORY_SERVICE_TIMEOUT) {
                    it.remove();
                    System.out.println("Servidor removido: " + entry.getKey());
                }
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
