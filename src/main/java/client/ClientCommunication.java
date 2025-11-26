package client;

import common.Constants;
import common.Message;
import java.io.*;
import java.net.*;

/**
 * Responsável pela comunicação do cliente com o servidor.
 */

public class ClientCommunication {
    private Socket socket; // socket TCP para comunicação com o servidor
    private ObjectOutputStream output; //output stream para enviar objetos para o servidor
    private ObjectInputStream input; // input stream para receber objetos do servidor
    private boolean running = true;
    private final ClientUI ui; // referência para a interface do cliente

    public ClientCommunication(ClientUI ui) { // construtor que recebe a interface do cliente
        this.ui = ui;
    }

    public boolean connect() { // função principal para conectar ao servidor
        try {
            InetSocketAddress primary = getPrimaryServer(); // recebe o endereço do socket do servidor principal
            if (primary == null)
                return false;

            return connectToAddress(primary);
        } catch (Exception e) {
            return false;
        }
    }

    // função auxiliar para conectar ao servidor
    private boolean connectToAddress(InetSocketAddress address) throws IOException { // recebe o endereço do socket do servidor
        socket = new Socket(address.getAddress(), address.getPort());
        output = new ObjectOutputStream(socket.getOutputStream());
        input = new ObjectInputStream(socket.getInputStream());

        // thread para esperar mensagens do servidor
        new Thread(this::listenForMessages).start();
        return true;
    }

    private void listenForMessages() { // função dada à thread para esperar mensagens do servidor
        try {
            while (running && !socket.isClosed()) { //se o cliente e a socket estiverem ativos
                Message msg = (Message) input.readObject(); // utiliza o input stream como buffer para ler a mensagem do servidor
                ui.handleMessage(msg); // passa a mensagem para a interface do cliente
            }
        } catch (Exception e) { //se ocorrer um erro na leitura das mensagens
            if (running) {
                ui.onConnectionLost(); //se o cliente estiver ativo, notifica a interface do cliente que a conexão foi perdida
            }
        }
    }

    public void sendRequest(Message request) { //envia mensagem para o servidor
        try {
            output.writeObject(request); // utiliza o output como buffer para enviar a mensagem ao servidor
            output.flush(); // limpa o output stream
        } catch (IOException e) { //se ocorrer um erro ao enviar a mensagem
            ui.onConnectionLost();
        }
    }

    public void reconnect() { // função para reconectar ao servidor
        closeConnection(); // fecha a conexão atual

        int retries = 0;
        InetSocketAddress lastAddress = null;

        while (retries < 5 && running) { //tenta reconectar 5 vezes
            try {
                InetSocketAddress primary = getPrimaryServer(); //obtem o endereço do servidor primário atual

                if (primary != null) { //se houver servidor primario ativo
                    if (primary.equals(lastAddress)) {
                        // Caso seja o mesmo servidor espera 20s até tentar novamente
                        Thread.sleep(20000);
                    } else {
                        Thread.sleep(5000); // se for um servidor diferente espera 5s até tentar novamente
                    }

                    if (connectToAddress(primary)) { //se conectar com sucesso
                        ui.onReconnected(); //notifica a interface do cliente que reconectou com sucesso
                        return;
                    }
                    lastAddress = primary;
                }
            } catch (Exception e) {
                // Retry
            }
            retries++;
        }
        ui.onFatalError("Nao foi possivel reconectar nenhum servidor"); // notifica a interface do cliente que não conseguiu reconectar
    }

    private InetSocketAddress getPrimaryServer() { // função para obter o endereço do servidor principal
        try (DatagramSocket socket = new DatagramSocket()) { // cria um socket UDP
            socket.setSoTimeout(5000); // define um timeout de 5 segundos
            String msg = "GET_SERVER"; //cria mensagem para a diretoria
            byte[] data = msg.getBytes(); //obtem o tamanho da mensagem em bytes
            DatagramPacket packet = new DatagramPacket( // cria um novo packet para enviar a mensagem à diretoria
                    data, data.length, InetAddress.getByName("localhost"), Constants.DIRECTORY_SERVICE_UDP_PORT);
                    // obtem o endereço da diretoria atraves do localhost e a porta UDP definida nas constantes
            socket.send(packet); //envia o packet

            byte[] buffer = new byte[1024]; // buffer para receber a resposta
            DatagramPacket response = new DatagramPacket(buffer, buffer.length); // packet para receber a resposta
            socket.receive(response); // espera pela resposta da diretoria

            String res = new String(response.getData(), 0, response.getLength()); // obtem a resposta como string
            if (res.startsWith("SERVER")) { // se a resposta começar com "SERVER", significa que obteve o endereço do servidor
                String[] parts = res.split(" "); // divide a resposta em partes (SERVER IP PORT)
                return new InetSocketAddress(parts[1], Integer.parseInt(parts[2])); // devolve o endereço do socket do servidor
            }
        } catch (Exception e) { //em caso de erro devolve null
            return null;
        }
        return null;
    }

    public void close() { // função para fechar a comunicação do cliente
        running = false;
        closeConnection();
    }

    private void closeConnection() { // função auxiliar para fechar a conexão
        try {
            if (socket != null)
                socket.close(); //termina o socket
        } catch (IOException e) {
            //em caso de erro ao fechar o socket não faz nada
        }
    }
}
