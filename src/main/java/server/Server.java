package server;

import common.Constants;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;

public class Server {
    private DatabaseManager dbManager;
    private int tcpPort;
    private int syncPort;
    private boolean isPrimary = false;
    private AtomicBoolean running = new AtomicBoolean(true);
    private String dbPath;

    // Lista para guardar os clientes ativos
    private final java.util.List<ClientHandler> activeClients = java.util.Collections
            .synchronizedList(new java.util.ArrayList<>());

    private final Set<Integer> activeQuestionIds = Collections.synchronizedSet(new HashSet<>());

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Uso: java server.Server <db_path> <tcp_port> <sync_port>");
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

            // verifica se existe um servidor mais antigo
            String primaryInfo = getPrimaryFromDirectory();

            if (primaryInfo == null || primaryInfo.equals("NO_SERVERS")) {
                System.out.println("Nao existem mais servidores, este e o servidor principal");
                isPrimary = true;
            } else {
                // se existir um servidor principal
                String[] parts = primaryInfo.split(" ");
                String ip = parts[1];
                int syncPort = Integer.parseInt(parts[3]);
                // avisa o utilizador
                System.out.println("Encontrado servidor principal em " + ip + ":" + parts[2]);
                isPrimary = false;
                syncDatabase(ip, syncPort); // copia a base de dados do servidor principal
            }

            long now = System.currentTimeMillis() / 1000;
            try {
                for (DatabaseManager.QuestionTimerData q : dbManager.getAllQuestionTimers()) {
                    if (q.startTime <= now && q.endTime > now) {
                        activeQuestionIds.add(q.id); // Marca como ativa silenciosamente
                    }
                }
            } catch (Exception e) {
                System.out.println("Erro ao carregar estado inicial dos timers: " + e.getMessage());
            }

            // inicia threads
            new Thread(this::checkTimers).start();
            new Thread(this::sendHeartbeats).start();
            new Thread(this::listenMulticast).start();
            new Thread(this::listenSync).start();
            new Thread(this::listenClients).start();

            // mantem thread principal a correr
            while (running.get()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // funcao auxiliar para obter o servidor principal da diretoria
    private String getPrimaryFromDirectory() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            String msg = "GET_SERVER"; // mensagem para obter o servidor principal da diretoria
            byte[] data = msg.getBytes();
            DatagramPacket packet = new DatagramPacket( // cria e envia o packet
                    data, data.length, InetAddress.getByName("localhost"), Constants.DIRECTORY_SERVICE_UDP_PORT);
            socket.send(packet);

            byte[] buffer = new byte[1024];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length); // packet para a resposta
            try {
                socket.receive(response);
                return new String(response.getData(), 0, response.getLength()); // devolve a resposta
            } catch (SocketTimeoutException e) {
                // sem respota
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    // funcao auxiliar para sincronizar a base de dados
    private void syncDatabase(String ip, int port) {
        System.out.println("A obter base de dados do servidor principal " + ip + ":" + port);
        try (Socket socket = new Socket(ip, port);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            out.writeUTF("SYNC_REQUEST"); // envia a pedido de sincronizacao ao servidor principal

            long fileSize = in.readLong();
            if (fileSize > 0) {
                dbManager.close(); // fecha a base de dados para conseguir escrever

                // copia base de dados principal para backup
                File currentDb = new File(dbManager.getDbPath());
                if (currentDb.exists()) {
                    File backup = new File(dbManager.getDbPath() + ".bak");
                    currentDb.renameTo(backup);
                }
                // copia a base de dados do servidor principal
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

                dbManager.connect(); // reabre a base de dados
                System.out.println("Base de dados sincronizada com sucesso.");
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
                // mensagem por udp para a diretoria
                // Formato: HEARTBEAT <tcp_port> <db_version> <sync_port>
                String msg = String.format("HEARTBEAT %d %d %d", tcpPort, dbManager.getDbVersion(), syncPort);
                byte[] data = msg.getBytes();

                DatagramPacket packet = new DatagramPacket(
                        data, data.length, directoryAddr, Constants.DIRECTORY_SERVICE_UDP_PORT);
                udpSocket.send(packet);

                // mensagem por multicast para o grupo
                DatagramPacket multiPacket = new DatagramPacket(
                        data, data.length, multicastGroup, Constants.MULTICAST_PORT);
                multicastSocket.send(multiPacket);

                Thread.sleep(Constants.SERVER_HEARTBEAT_INTERVAL);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenMulticast() { // escuta por mensagens do grupo de servidores
        try (MulticastSocket socket = new MulticastSocket(Constants.MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(Constants.MULTICAST_GROUP);
            NetworkInterface netIf = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
            socket.joinGroup(new InetSocketAddress(group, Constants.MULTICAST_PORT), netIf);

            byte[] buffer = new byte[4096];
            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength());
                if (msg.startsWith("UPDATE")) { // se for uma mensagem de update
                    handleDbUpdate(msg); // atualiza a base de dados
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleDbUpdate(String msg) { // funcao auxiliar para atualizar a base de dados
        if (isPrimary)
            return; // se for o servidor principal, ignora
        try { // processa a mensagem de update formato UPDATE <versao> <sql>
            int firstSpace = msg.indexOf(' ');
            int secondSpace = msg.indexOf(' ', firstSpace + 1);

            if (firstSpace == -1 || secondSpace == -1)
                return;

            int version = Integer.parseInt(msg.substring(firstSpace + 1, secondSpace));
            String sql = msg.substring(secondSpace + 1);

            int localVersion = dbManager.getDbVersion();

            if (version == localVersion + 1) {
                System.out.println("Applying update version " + version);
                dbManager.executeUpdate(sql); // aplica a atualizacao a base de dados local
            } else if (version > localVersion + 1) {
                System.err.println(
                        "Atualizacoes perdidas (Local: " + localVersion + ", Remoto: " + version + ").");
                System.err.println("Servidor encerrado.");
                running.set(false);
                System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void listenSync() { // escuta por pedidos de sincronizacao
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
                    out.writeLong(dbFile.length()); // envia o tamanho do ficheiro
                    try (FileInputStream fis = new FileInputStream(dbFile)) {
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, read); // envia o ficheiro
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

    private void listenClients() { // funcao para escutar por clientes atraves do client handler
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
            // Atualiza a base de dados local
            dbManager.executeUpdate(sql);

            // envia mensagem de update para o grupo
            // Formato: UPDATE <versao> <sql>
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

    public void addClient(ClientHandler client) {
        activeClients.add(client);
        System.out.println("Cliente conectado. Total: " + activeClients.size());
    }

    public void removeClient(ClientHandler client) {
        activeClients.remove(client);
        System.out.println("Cliente desconectado. Total: " + activeClients.size());
    }

    // funcao para enviar mensagem para todos os clientes
    public void broadcast(common.Message msg, ClientHandler sender) {
        synchronized (activeClients) { // bloqueia a lista para iterar
            for (ClientHandler client : activeClients) {
                if (client != sender) {
                    client.sendMessage(msg);
                }
            }
        }
    }

    private void checkTimers() {
        System.out.println("Thread de verificacao temporal iniciada...");
        while (running.get()) {
            try {
                // Obtém todas as perguntas
                List<DatabaseManager.QuestionTimerData> questions = dbManager.getAllQuestionTimers();
                long now = System.currentTimeMillis() / 1000;

                for (DatabaseManager.QuestionTimerData q : questions) {
                    boolean isActiveTime = (q.startTime <= now && q.endTime > now);
                    boolean isExpiredTime = (q.endTime <= now);
                    boolean wasActiveInMemory = activeQuestionIds.contains(q.id);

                    // CASO 1: A pergunta acabou de entrar no horário de início
                    if (isActiveTime && !wasActiveInMemory) {
                        activeQuestionIds.add(q.id);
                        String msg = "A pergunta '" + q.prompt + "' acabou de começar! Boa sorte.";
                        System.out.println("[TIMER] Notificando inicio: " + q.prompt);
                        // Envia para todos
                        broadcast(new common.Message(common.Message.Type.NOTIFICATION, msg), null);
                    }

                    // CASO 2: A pergunta acabou de expirar
                    else if (isExpiredTime && wasActiveInMemory) {
                        activeQuestionIds.remove(q.id);
                        String msg = "A pergunta '" + q.prompt + "' terminou. O tempo esgotou-se.";
                        System.out.println("[TIMER] Notificando fim: " + q.prompt);
                        broadcast(new common.Message(common.Message.Type.NOTIFICATION, msg), null);
                    }
                }

                // Verifica a cada 5 segundos
                Thread.sleep(5000);

            } catch (Exception e) {
                System.err.println("Erro na thread de timer: " + e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ex) {} // Espera antes de tentar de novo
            }
        }
    }

}
