package client;

import common.Message;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/* 
 Responsável pela interface do cliente

*/
public class ClientUI {
    private ClientCommunication comm; // instancia da camada de comunicação
    private Scanner scanner = new Scanner(System.in); // scanner para ler comandos do terminal
    private boolean running = true;
    private String userEmail = null; // email do utilizador atual
    private String userRole = null; // student ou teacher

    // blocking queue para poder esperar por respostas antes de enviar novos pedidos
    private BlockingQueue<Message> responseQueue = new LinkedBlockingQueue<>();

    public static void main(String[] args) {
        new ClientUI().start();
    }

    public void start() {
        comm = new ClientCommunication(this); // instancia a camada de comunicação
        if (!comm.connect()) {
            System.out.println("Nao foi possivel conectar ao servidor.");
            return;
        }

        System.out.println("Conexao estabelecida com o servidor.");

        while (running) { // loop principal do UI
            if (userEmail == null) {
                showLoginMenu();
            } else {
                if ("TEACHER".equalsIgnoreCase(userRole)) {
                    showTeacherMenu();
                } else {
                    showStudentMenu();
                }
            }
        }
        comm.close();
    }

    // recebe mensagens do ClientCommunication
    public void handleMessage(Message msg) {
        // Adiciona qualquer mensagens à queue de respostas para ser processado
        // posteriormente
        responseQueue.offer(msg);
    }

    public void onConnectionLost() {
        System.out.println("\nConexao perdida. A tentar reconectar...");
        comm.reconnect();
    }

    public void onReconnected() {
        System.out.println("Conexao estabelecida");
    }

    public void onFatalError(String msg) {
        System.out.println("Erro fatal: " + msg);
        running = false; // termina o loop principal
        System.exit(1);
    }

    private Message sendRequestAndWait(Message req) { // envia pedido e espera pela resposta
        comm.sendRequest(req);
        try {
            return responseQueue.take(); // espera até receber uma resposta
        } catch (InterruptedException e) {
            return null;
        }
    }

    private void showLoginMenu() {
        System.out.println("\n--- LOGIN ---");
        System.out.println("1. Login");
        System.out.println("2. Registar");
        System.out.println("0. Fechar");
        System.out.print("Option: ");

        String opt = scanner.nextLine(); // obtem resposta do utilizador
        switch (opt) {
            case "1":
                login();
                break;
            case "2":
                register();
                break;
            case "0":
                running = false;
                break;
            default:
                System.out.println("Digita uma opcao valida (1, 2 ou 0).");
        }
    }

    private void login() { // obtem detalhes e realiza login do utilizador
        System.out.print("Email: ");
        String email = scanner.nextLine();
        System.out.print("Password: ");
        String password = scanner.nextLine();

        Message response = sendRequestAndWait( // envia pedido de login e espera pela resposta
                new Message(Message.Type.LOGIN_REQUEST, new String[] { email, password })); // cria nova mensagem de
                                                                                            // login com email e
                                                                                            // password

        if (response != null && response.getType() == Message.Type.LOGIN_RESPONSE
                && response.getContent() instanceof String) { // se a resposta for válida, de login e for uma string
            userRole = (String) response.getContent();
            userEmail = email;
            System.out.println("Login realizado como " + userRole);
        } else {
            System.out.println("Falha no login.");
        }
    }

    private void register() {
        System.out.println("Registar como: 1. Estudante, 2. Professor"); // obtem o tipo de utilizador
        String type = scanner.nextLine().equalsIgnoreCase("2") ? "TEACHER" : "STUDENT";

        // obtem detalhes do utilizador
        System.out.print("Nome: ");
        String name = scanner.nextLine();
        System.out.print("Email: ");
        String email = scanner.nextLine();
        System.out.print("Password: ");
        String password = scanner.nextLine();

        String extra = ""; // obtem informação extra dependendo do tipo de utilizador codigo de professor
                           // ou numero de estudante
        if ("TEACHER".equalsIgnoreCase(type)) {
            System.out.print("Codigo de Professor: ");
            extra = scanner.nextLine();
        } else {
            System.out.print("Numero de estudante: ");
            extra = scanner.nextLine();
        }

        Message response = sendRequestAndWait( // envia pedido de registo e espera pela resposta
                new Message(Message.Type.REGISTER_REQUEST, new String[] { name, email, password, type, extra }));
        // cria nova mensagem de registo com os detalhes do utilizador

        if (response != null && response.getType() == Message.Type.REGISTER_RESPONSE
                && (boolean) response.getContent()) {
            System.out.println("Registado!");
        } else {
            System.out.println("Erro ao realizar registro.");
        }
    }

    private void showTeacherMenu() {
        System.out.println("\n--- MENU PROFESSOR ---");
        System.out.println("1. Criar pergunta");
        System.out.println("2. Listar perguntas");
        System.out.println("3. Editar pergunta");
        System.out.println("4. Eliminar pergunta");
        System.out.println("5. Ver Respostas (Relatório)");
        System.out.println("6. Exportar CSV");
        System.out.println("7. Editar Perfil");
        System.out.println("0. Logout");

        String opt = scanner.nextLine();
        switch (opt) {
            case "1":
                createQuestion();
                break;
            case "2":
                listQuestions();
                break;
            case "3":
                editQuestion();
                break;
            case "4":
                deleteQuestion();
                break;
            case "5":
                showQuestionAnswers();
                break;
            case "6":
                exportCsv();
                break;
            case "7":
                editProfile();
                break;
            case "0":
                userEmail = null;
                userRole = null;
                break;
            default:
                System.out.println("Digita uma opcao valida (1, 2, 3, 4, 5, 6, 7 ou 0).");
        }
    }

    private void createQuestion() {
        System.out.println("\n--- Criar Pergunta ---");
        String prompt;
        while (true) {
            System.out.print("Pergunta: ");
            prompt = scanner.nextLine();
            if (!prompt.trim().isEmpty())
                break;
            System.out.println("Digita uma pergunta");
        }

        String options;
        while (true) {
            System.out.print("Opcoes (pelo menos duas separadas por , ): ");
            options = scanner.nextLine();
            if (options.split(",").length >= 2)
                break;
            System.out.println("Digita pelo menos duas opcoes.");
        }

        String correctOption;
        while (true) {
            System.out.print("Indice da resposta correta (inicia com 0): ");
            correctOption = scanner.nextLine();
            try {
                int idx = Integer.parseInt(correctOption);
                if (idx >= 0 && idx < options.split(",").length)
                    break;
                System.out.println("Indice invalido");
            } catch (NumberFormatException e) {
                System.out.println("Input invalido, digita um numero.");
            }
        }

        String startTime;
        while (true) {
            System.out.print("Data e hora de inicio (YYYY-MM-DD HH:MM): ");
            startTime = scanner.nextLine();
            if (isValidDate(startTime)) // verifica se o formato é válido
                break;
            System.out.println("Formato invalido. Digita YYYY-MM-DD HH:MM");
        }

        String endTime;
        while (true) {
            System.out.print("Data e hora do fim (YYYY-MM-DD HH:MM): ");
            endTime = scanner.nextLine();
            if (isValidDate(endTime)) { // verifica se o formato é válido
                if (isEndDateAfterStartDate(startTime, endTime)) // verifica se a data de fim é depois da data de inicio
                    break;
                else
                    System.out.println("Data de fim deve ser depois da data de inicio.");
            } else {
                System.out.println("Formato invalido. Digita YYYY-MM-DD HH:MM");
            }
        }

        String accessCode = String.valueOf((int) (Math.random() * 9000) + 1000); // gera um código de acesso aleatório
                                                                                 // de 4 dígitos
        System.out.println("Codigo da pergunta: " + accessCode);

        Message response = sendRequestAndWait(new Message(Message.Type.CREATE_QUESTION, new String[] {
                prompt, options, correctOption, startTime, endTime, accessCode, userEmail
        }));

        if (response != null && response.getType() == Message.Type.CREATE_QUESTION_RESPONSE
                && (boolean) response.getContent()) {
            System.out.println("Pergunta criada!");
        } else {
            System.out.println("Erro ao criar a pergunta.");
        }
    }

    private void listQuestions() {
        System.out.println("Filtro: 1. Todas, 2. Ativas, 3. Expiradas, 4. Futuras");
        String opt = scanner.nextLine();
        String filter = "ALL";

        switch (opt) {
            case "2":
                filter = "ACTIVE";
                break;
            case "3":
                filter = "EXPIRED";
                break;
            case "4":
                filter = "FUTURE";
                break;
            default:
                filter = "ALL";
                break;
        }

        Message response = sendRequestAndWait(new Message(Message.Type.LIST_QUESTIONS, filter));
        if (response != null && response.getType() == Message.Type.LIST_QUESTIONS_RESPONSE) {
            @SuppressWarnings("unchecked")
            List<String> questions = (List<String>) response.getContent();
            if (questions.isEmpty()) {
                System.out.println("Nenhuma pergunta encontrada.");
            }
            System.out.println("\n--- Perguntas ---");
            for (String q : questions)
                System.out.println(q);
        }
    }

    private void deleteQuestion() {
        System.out.print("Codigo da pergunta a eliminar: ");
        String code = scanner.nextLine();
        Message response = sendRequestAndWait(new Message(Message.Type.DELETE_QUESTION, code));
        if (response != null) {
            System.out.println(response.getContent());
        }
    }

    private void showQuestionAnswers() {
        System.out.print("Codigo da pergunta: ");
        String code = scanner.nextLine();
        Message response = sendRequestAndWait(new Message(Message.Type.GET_QUESTION_ANSWERS, code));

        if (response != null && response.getType() == Message.Type.GET_QUESTION_ANSWERS_RESPONSE) {
            @SuppressWarnings("unchecked")
            List<String> report = (List<String>) response.getContent();
            if (report == null)
                System.out.println("Pergunta nao encontrada.");
            else {
                System.out.println("\n--- Respostas ---");
                for (String line : report)
                    System.out.println(line);
            }
        }
    }

    private void editQuestion() {
        System.out.println("\n--- EDITAR PERGUNTA ---");
        System.out.print("Codigo da pergunta a editar: ");
        String accessCode = scanner.nextLine();

        System.out.println("Insira os novos dados (deixe vazio para manter o valor atual):");

        // Enunciado
        System.out.print("Novo Enunciado: ");
        String prompt = scanner.nextLine();

        // Opções
        String options;
        while (true) {
            System.out.print("Novas Opcoes (separadas por , ): ");
            options = scanner.nextLine();
            // Se estiver vazio, aceita (mantém o antigo). Se tiver texto, valida se tem virgulas.
            if (options.trim().isEmpty() || options.split(",").length >= 2) {
                break;
            }
            System.out.println("Se quiser alterar, digite pelo menos duas opcoes.");
        }

        // Opção Correta
        String correctOption;
        while (true) {
            System.out.print("Novo Indice da resposta correta (inicia com 0): ");
            correctOption = scanner.nextLine();
            if (correctOption.trim().isEmpty()) break; // Aceita vazio
            try {
                int idx = Integer.parseInt(correctOption);
                if (idx >= 0) break; // Aceita numero valido (validar maximo seria ideal mas n temos as opcoes aqui)
                System.out.println("Indice invalido.");
            } catch (NumberFormatException e) {
                System.out.println("Input invalido, digite um numero ou Enter para manter.");
            }
        }

        // Data Inicio
        String startTime;
        while (true) {
            System.out.print("Nova Data e hora de inicio (YYYY-MM-DD HH:MM): ");
            startTime = scanner.nextLine();
            if (startTime.trim().isEmpty() || isValidDate(startTime)) break;
            System.out.println("Formato invalido. Digite YYYY-MM-DD HH:MM");
        }

        // Data Fim
        String endTime;
        while (true) {
            System.out.print("Nova Data e hora do fim (YYYY-MM-DD HH:MM): ");
            endTime = scanner.nextLine();
            if (endTime.trim().isEmpty() || isValidDate(endTime)) break;
            System.out.println("Formato invalido. Digite YYYY-MM-DD HH:MM");
        }

        if (prompt.isEmpty() && options.isEmpty() && correctOption.isEmpty() && startTime.isEmpty() && endTime.isEmpty()) {
            System.out.println("Nenhuma alteração solicitada. Operação cancelada.");
            return;
        }

        // Envia tudo (mesmo o que estiver vazio) para o servidor tratar
        Message response = sendRequestAndWait(new Message(Message.Type.EDIT_QUESTION, new String[] {
                accessCode, prompt, options, correctOption, startTime, endTime
        }));

        if (response != null && response.getType() == Message.Type.EDIT_QUESTION_RESPONSE) {
            boolean success = (boolean) response.getContent();
            if (success) {
                System.out.println("Pergunta editada com sucesso!");
            } else {
                System.out.println("Falha ao editar (Verifique o codigo ou se a pergunta ja tem respostas).");
            }
        } else {
            System.out.println("Erro de comunicacao.");
        }
    }

    private void exportCsv() {
        System.out.println("\n--- EXPORTAR CSV ---");
        System.out.print("Codigo da pergunta: ");
        String accessCode = scanner.nextLine();

        Message response = sendRequestAndWait(new Message(Message.Type.EXPORT_CSV, accessCode));

        if (response != null && response.getType() == Message.Type.EXPORT_CSV_RESPONSE) {
            String csvContent = (String) response.getContent();
            if (csvContent == null) {
                System.out.println("Pergunta nao encontrada.");
            } else {
                String filename = "exportado_" + accessCode + ".csv";
                try (FileWriter fw = new FileWriter(filename)) {
                    fw.write(csvContent);
                    System.out.println("Ficheiro CSV exportado: " + filename);
                } catch (IOException e) {
                    System.out.println("Erro ao criar ficheiro CSV: " + e.getMessage());
                }
            }
        } else {
            System.out.println("Erro ao exportar o ficheiro CSV.");
        }
    }

    private void showStudentMenu() {
        System.out.println("\n--- MENU ESTUDANTE ---");
        System.out.println("1. Responder a pergunta");
        System.out.println("2. Consultar Historico");
        System.out.println("3. Editar Perfil");
        System.out.println("0. Logout");

        String opt = scanner.nextLine();
        switch (opt) {
            case "1":
                answerQuestion();
                break;
            case "2":
                checkHistory();
                break;
            case "3":
                editProfile();
                break;
            case "0":
                userEmail = null;
                break;

            default:
                System.out.println("Digita uma opcao valida (1, 2, 3 ou 0).");
        }
    }

    private void answerQuestion() {
        System.out.println("\n--- RESPONDER A PERGUNTA ---");
        System.out.print("Codigo da pergunta: ");
        String accessCode = scanner.nextLine();

        Message response = sendRequestAndWait(new Message(Message.Type.GET_QUESTION, accessCode));

        if (response != null && response.getType() == Message.Type.GET_QUESTIONS_RESPONSE) {
            Object content = response.getContent();

            // CASO DE ERRO (Recebeu uma String simples com o aviso)
            if (content instanceof String) {
                System.out.println(content); // Imprime: "ERRO: A pergunta já expirou."
                return;
            }

            // CASO DE SUCESSO (Recebeu String[] com enunciado e opções)
            if (content instanceof String[]) {
                String[] data = (String[]) content;
                String prompt = data[0];
                String optionsStr = data[1];
                String[] options = optionsStr.split(",");

                System.out.println("Pergunta: " + prompt);
                for (int i = 0; i < options.length; i++) {
                    System.out.println(i + ": " + options[i].trim());
                }

                String answerIndex;
                while (true) {
                    System.out.print("Indice da resposta ( comeca com 0): ");
                    answerIndex = scanner.nextLine();
                    try {
                        int idx = Integer.parseInt(answerIndex);
                        if (idx >= 0 && idx < options.length)
                            break;
                        System.out.println("Indice invalido");
                    } catch (NumberFormatException e) {
                        System.out.println("Input invalido, digita um numero.");
                    }
                }

                Message submitResponse = sendRequestAndWait(
                        new Message(Message.Type.SUBMIT_ANSWER, new String[] { accessCode, answerIndex, userEmail }));

                if (submitResponse != null && submitResponse.getType() == Message.Type.SUBMIT_ANSWER_RESPONSE
                        && (boolean) submitResponse.getContent()) {
                    System.out.println("Resposta submetida!");
                } else {
                    System.out.println("Erro ao enviar resposta (Verifique se ja respondeu ou se o tempo acabou).");
                }
            }
        } else {
            System.out.println("Erro de comunicacao.");
        }
    }

    private void checkHistory() {
        System.out.println("\n--- HISTORICO ---");
        System.out.println("Filtro: 1. Tudo, 2. Apenas Certas, 3. Apenas Erradas, 4. Ultimas 24h");
        String opt = scanner.nextLine();

        String filter = "ALL";
        if ("2".equals(opt))
            filter = "CORRECT";
        else if ("3".equals(opt))
            filter = "INCORRECT";
        else if ("4".equals(opt))
            filter = "LAST_24H";

        // Enviar pedido: [Email, Filtro]
        Message response = sendRequestAndWait(
                new Message(Message.Type.GET_STUDENT_HISTORY, new String[] { userEmail, filter }));

        if (response != null && response.getType() == Message.Type.GET_STUDENT_HISTORY_RESPONSE) {
            @SuppressWarnings("unchecked")
            List<String> history = (List<String>) response.getContent();

            System.out.println("\n--- Resultados ---");
            for (String line : history) {
                System.out.println(line);
            }
        } else {
            System.out.println("Erro ao obter historico.");
        }
    }

    private void editProfile() {
        System.out.println("\n--- EDITAR PERFIL ---");
        System.out.println("(Deixe vazio para manter o valor atual)");

        System.out.print("Novo Nome: ");
        String newName = scanner.nextLine();

        System.out.print("Nova Password: ");
        String newPass = scanner.nextLine();

        System.out.print("Novo Email: ");
        String newEmail = scanner.nextLine();

        String newStudentId = "";

        if ("STUDENT".equalsIgnoreCase(userRole)) {
            System.out.print("Novo Nº Estudante: ");
            newStudentId = scanner.nextLine();
        }

        if (newName.isEmpty() && newPass.isEmpty() && newEmail.isEmpty() && newStudentId.isEmpty()) {
            System.out.println("Cancelado: Nenhuma alteração inserida.");
            return;
        }

        // Envia: [Email Atual, Novo Nome, Nova Pass, Novo Email, Novo ID]
        Message response = sendRequestAndWait(new Message(
                Message.Type.EDIT_PROFILE,
                new String[]{userEmail, newName, newPass, newEmail, newStudentId}
        ));

        if (response != null && response.getType() == Message.Type.EDIT_PROFILE_RESPONSE) {
            String result = (String) response.getContent();

            if ("SUCESSO".equals(result)) {
                System.out.println("Perfil atualizado com sucesso!");

                if (!newEmail.isEmpty()) {
                    this.userEmail = newEmail;
                    System.out.println("O seu email de sessao foi atualizado.");
                }
            } else if ("EMAIL_DUPLICADO".equals(result)) {
                System.out.println("Erro: Esse email ja está a ser utilizado por outro utilizador.");
            } else {
                System.out.println("Erro ao atualizar perfil.");
            }
        }
    }

    private boolean isValidDate(String date) {
        return date.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }
    // funçao auxiliar utilizada para verificar se a data é valida

    private boolean isEndDateAfterStartDate(String start, String end) { // funcao auxiliar para verificar se a data de
                                                                        // fim é depois da data de inicio
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime startDate = LocalDateTime.parse(start, formatter);
            LocalDateTime endDate = LocalDateTime.parse(end, formatter);
            return endDate.isAfter(startDate);
        } catch (Exception e) {
            return false;
        }
    }

    public void showNotification(String msg) {
        System.out.println("\n\n************************************************");
        System.out.println("[NOTIFICACAO]: " + msg);
        System.out.println("************************************************");
        System.out.print("Opcao: ");
    }

}
