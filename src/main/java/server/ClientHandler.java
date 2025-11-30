package server;

import common.Message;
import java.io.*;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/*  Vertente do servidor responsável pela gestão dos clientes */

public class ClientHandler implements Runnable {
    // runnable para conseguir gerir multiplos clientes ao mesmo tempo
    // atraves de varias threads, funcao run() corre para cada cliente
    private Socket socket;
    private DatabaseManager dbManager;
    private Server server;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    // recebe dados da mainclass server
    public ClientHandler(Socket socket, DatabaseManager dbManager, Server server) {
        this.socket = socket;
        this.dbManager = dbManager;
        this.server = server;
    }

    @Override
    public void run() { // corre para cada cliente
        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            // Avisar o servidor que este cliente entrou
            server.addClient(this); // passa ao servidor a sua propria instancia

            while (!socket.isClosed()) { // enquanto a socket estiver aberta
                Message request = (Message) in.readObject(); // le a mensagem do cliente
                Message response = handleRequest(request); // processa a mensagem

                sendMessage(response); // envia a resposta
            }
        } catch (EOFException e) {
            // cliente desconectou
        } catch (Exception e) {
            e.printStackTrace(); // outros erros
        } finally {
            // avisa o servidor que este cliente saiu
            server.removeClient(this);
            try {
                socket.close(); // fecha o socket
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private Message handleRequest(Message request) {
        try { // redireciona requests para o seu respetivo handler
              // com base no tipo da mensagem
            switch (request.getType()) {
                case LOGIN_REQUEST:
                    return handleLogin((String[]) request.getContent());
                case REGISTER_REQUEST:
                    return handleRegister((String[]) request.getContent());
                case CREATE_QUESTION:
                    return handleCreateQuestion((String[]) request.getContent());
                case LIST_QUESTIONS:
                    return handleListQuestions((String) request.getContent());
                case GET_QUESTION:
                    return handleGetQuestion((String) request.getContent());
                case SUBMIT_ANSWER:
                    return handleSubmitAnswer((String[]) request.getContent());
                case EXPORT_CSV:
                    return handleExportCsv((String) request.getContent());
                case EDIT_QUESTION:
                    return handleEditQuestion((String[]) request.getContent());
                case DELETE_QUESTION:
                    return handleDeleteQuestion((String) request.getContent());
                case GET_QUESTION_ANSWERS:
                    return handleGetQuestionAnswers((String) request.getContent());
                case GET_STUDENT_HISTORY:
                    return handleGetStudentHistory((String[]) request.getContent());
                default:
                    return new Message(Message.Type.LOGIN_RESPONSE, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new Message(Message.Type.LOGIN_RESPONSE, null);
        }
    }

    private Message handleLogin(String[] credentials) throws SQLException {
        String email = credentials[0];
        String password = credentials[1];

        String sql = "SELECT * FROM users WHERE email = ? AND password = ?";
        Connection conn = dbManager.getConnection();
        // cria um prepared statement para a query com a string sql
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            // define os valores dos parametros, identificados com ?
            pstmt.setString(1, email);
            pstmt.setString(2, password);
            // executa a query
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) { // next para verificar se existe resultado
                String role = rs.getString("role");
                System.out.println("Utilizador " + email + " logged in como " + role);
                return new Message(Message.Type.LOGIN_RESPONSE, role);
                // devolve a role do utilizador
            } else {
                System.out.println("Login invalido com: " + email);
            }
        }
        // se nao existir resultado devolve null, com mesagem do mesmo tipo
        return new Message(Message.Type.LOGIN_RESPONSE, null);
    }

    private Message handleRegister(String[] data) throws SQLException {
        // escape sql utilizado para normalizar o input do cliente
        // prepara a string para ser usada na query
        String name = normSql(data[0]);
        String email = normSql(data[1]);
        String password = normSql(data[2]);
        String role = normSql(data[3]);

        if (email == null || !email.contains("@")) {
            System.out.println("Email invalido: " + email);
            return new Message(Message.Type.REGISTER_RESPONSE, false);
        }

        String studentId = null;
        String teacherCode = null;

        if ("STUDENT".equals(role)) {
            String rawId = data[4];
            if (rawId == null || !rawId.matches("\\d+")) { // verifica se o id é um numero
                System.out.println("Numero de aluno invalido: " + rawId);
                return new Message(Message.Type.REGISTER_RESPONSE, false);
            }
            studentId = normSql(rawId);
        } else {
            // se for professor valida o codigo
            String inputCode = data[4].trim();
            String hashedCode = hash(inputCode); // hash do codigo introduzido
            // transforma o codigo esperado em hash
            String validHash = hash("PROFE123");

            if (!validHash.equals(hashedCode)) { // compara os hashs dos codigos
                System.out.println("Codigo invalido: " + inputCode);
                return new Message(Message.Type.REGISTER_RESPONSE, false);
            }
            teacherCode = hashedCode;
        }

        String query = String.format( // prepara a query com os dados do utilizador
                "INSERT INTO users (name, email, password, role, student_id, teacher_code_hash) VALUES ('%s', '%s', '%s', '%s', '%s', '%s')",
                name, email, password, role, studentId != null ? studentId : "NULL",
                teacherCode != null ? teacherCode : "NULL");

        server.executeUpdate(query); // envia a query preparada para o servidor
        return new Message(Message.Type.REGISTER_RESPONSE, true);
    }

    private Message handleCreateQuestion(String[] data) throws SQLException {
        String prompt = normSql(data[0]);
        String options = normSql(data[1]);
        String correctOption = data[2];
        String startTimeStr = data[3];
        String endTimeStr = data[4];
        String accessCode = normSql(data[5]);
        String creatorEmail = normSql(data[6]);

        long startTime = parseDateToTimestamp(startTimeStr);
        long endTime = parseDateToTimestamp(endTimeStr);

        if (endTime <= startTime) {
            System.out.println("Validation failed: End time must be after start time.");
            return new Message(Message.Type.CREATE_QUESTION_RESPONSE, false);
        }

        String query = String.format(
                "INSERT INTO questions (prompt, options, correct_option, start_time, end_time, access_code, creator_email) VALUES ('%s', '%s', %s, %d, %d, '%s', '%s')",
                prompt, options, correctOption, startTime, endTime, accessCode, creatorEmail);

        server.executeUpdate(query);
        // envia pedido de notificaçao ao servidor
        String notificationMsg = "ATENCAO: Nova pergunta disponivel -> " + data[0];
        server.broadcast(new Message(Message.Type.NOTIFICATION, notificationMsg), this);

        return new Message(Message.Type.CREATE_QUESTION_RESPONSE, true);
    }

    private Message handleListQuestions(String filter) throws SQLException {
        // devolve lista de perguntas
        String sql = "SELECT * FROM questions";

        if (filter == null) // se o cliente nao tiver enviado um filtro
            filter = "ALL";

        long now = System.currentTimeMillis() / 1000;

        // define a query sql de acordo com o filtro
        if ("ATUAL".equalsIgnoreCase(filter)) {
            sql += " WHERE start_time <= " + now + " AND end_time >= " + now;
        } else if ("EXPIRADO".equalsIgnoreCase(filter)) {
            sql += " WHERE end_time < " + now;
        } else if ("FUTURO".equalsIgnoreCase(filter)) {
            sql += " WHERE start_time > " + now;
        }
        List<String> questions = new ArrayList<>();
        Connection conn = dbManager.getConnection();
        // prepara e envia a query
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
                ResultSet rs = pstmt.executeQuery()) {
            // percorre o resultado da query
            while (rs.next()) {
                questions.add(
                        rs.getString("id") + ": " + rs.getString("prompt") +
                                " (" + rs.getString("access_code") + ")");
            }
        } // devolve as perguntas encontradas
        return new Message(Message.Type.LIST_QUESTIONS_RESPONSE, questions);
    }

    private Message handleGetQuestion(String accessCode) throws SQLException {
        // devolve uma pergunta con base no seu codigo de acesso
        String sql = "SELECT * FROM questions WHERE access_code = ?";
        Connection conn = dbManager.getConnection();

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, accessCode); // codigo de acesso introduzido pelo utilizador
            ResultSet rs = pstmt.executeQuery(); // executa a query

            if (rs.next()) {
                String prompt = rs.getString("prompt");
                String options = rs.getString("options");
                return new Message(Message.Type.GET_QUESTIONS_RESPONSE, new String[] { prompt, options });
            }
        } // devolve resultados
        return new Message(Message.Type.GET_QUESTIONS_RESPONSE, null);
    }

    private Message handleSubmitAnswer(String[] data) throws SQLException {
        // separa os dados recebidos
        String accessCode = data[0];
        String answerIndex = data[1];
        String studentEmail = data[2];

        String sqlId = "SELECT id FROM questions WHERE access_code = ?";
        int questionId = -1;

        Connection conn = dbManager.getConnection();
        try (PreparedStatement pstmt = conn.prepareStatement(sqlId)) {
            pstmt.setString(1, accessCode); // codigo de acesso introduzido pelo utilizador
            ResultSet rs = pstmt.executeQuery(); // executa a query
            if (rs.next())
                questionId = rs.getInt("id"); // id da pergunta com base no codigo de acesso
        }

        if (questionId == -1) // verifica se o id da pergunta existe
            return new Message(Message.Type.SUBMIT_ANSWER_RESPONSE, false);

        // verifica se o aluno ja respondeu a pergunta
        String checkSql = "SELECT * FROM answers WHERE question_id = ? AND student_email = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(checkSql)) {
            pstmt.setInt(1, questionId);
            pstmt.setString(2, studentEmail);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                System.out.println("Student " + studentEmail + " already answered question " + questionId);
                return new Message(Message.Type.SUBMIT_ANSWER_RESPONSE, false);
            }
        }
        // prepara a query para inserir a resposta
        String query = String.format(
                "INSERT INTO answers (question_id, student_email, answer_index, timestamp) VALUES (%d, '%s', %s, %d)",
                questionId, normSql(studentEmail), answerIndex, System.currentTimeMillis());

        server.executeUpdate(query); // executa a query
        // devolve TRUE apenas quando a resposta é corretamente adicionada
        return new Message(Message.Type.SUBMIT_ANSWER_RESPONSE, true);
    }

    private Message handleExportCsv(String accessCode) throws SQLException {
        StringBuilder csv = new StringBuilder();

        String sqlQuestion = "SELECT * FROM questions WHERE access_code = ?";
        int questionId = -1;
        String prompt = "";
        String optionsStr = "";
        int correctOptionIndex = -1;
        long startTime = 0;
        long endTime = 0;
        // prepara um statement para receber os dados
        Connection conn = dbManager.getConnection();
        try (PreparedStatement pstmt = conn.prepareStatement(sqlQuestion)) {
            pstmt.setString(1, accessCode);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                questionId = rs.getInt("id");
                prompt = rs.getString("prompt");
                optionsStr = rs.getString("options");
                correctOptionIndex = rs.getInt("correct_option");
                startTime = rs.getLong("start_time");
                endTime = rs.getLong("end_time");
                // preenche variaveis com os dados recebidos
            }
        }

        if (questionId == -1) { // verifica que pergunta existe
            return new Message(Message.Type.EXPORT_CSV_RESPONSE, null);
        }

        // formatar as datas
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

        // converte os timestamps para datas
        LocalDateTime startDateTime = LocalDateTime.ofEpochSecond(startTime, 0, ZoneOffset.UTC);
        LocalDateTime endDateTime = LocalDateTime.ofEpochSecond(endTime, 0, ZoneOffset.UTC);

        // formata as datas para as suas variaveis finais
        String dateStr = startDateTime.format(dateFormatter);
        String startTimeStr = startDateTime.format(timeFormatter);
        String endTimeStr = endDateTime.format(timeFormatter);

        // variaveis para construir o csv
        String[] options = optionsStr.split(",");
        String correctOptionLabel = String.valueOf((char) ('a' + correctOptionIndex));

        // preenche o csv com as informacoes da pergunta
        csv.append("\"dia\";\"hora inicial\";\"hora final\";\"enunciado da pergunta\";\"opção certa\"\n");
        csv.append(String.format("\"%s\";\"%s\";\"%s\";\"%s\";\"%s\"\n",
                dateStr, startTimeStr, endTimeStr, prompt.replace("\"", "\"\""), correctOptionLabel));

        // preenche o csv com as opcoes
        csv.append("\"opção\";\"texto da opção\"\n");
        for (int i = 0; i < options.length; i++) {
            String optLabel = String.valueOf((char) ('a' + i));
            String optText = options[i].trim();
            if (optText.matches("^[a-zA-Z]:.*")) {
                optText = optText.substring(2).trim();
            }
            csv.append(String.format("\"%s\";\"%s\"\n", optLabel, optText.replace("\"", "\"\"")));
        }

        // preenche o csv com as respostas
        csv.append("\"número de estudante\"; \"nome\"; \"e-mail\";\"resposta\"\n");
        // prepara a query para obter as respostas
        String sqlAnswers = "SELECT a.answer_index, a.student_email, u.name, u.student_id " +
                "FROM answers a " +
                "JOIN users u ON a.student_email = u.email " +
                "WHERE a.question_id = ?";
        // executa a query
        try (PreparedStatement pstmt = conn.prepareStatement(sqlAnswers)) {
            pstmt.setInt(1, questionId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                int answerIndex = rs.getInt("answer_index");
                String answerLabel = String.valueOf((char) ('a' + answerIndex));
                String studentId = rs.getString("student_id");
                String name = rs.getString("name");
                String email = rs.getString("student_email");
                // adiciona as respostas ao csv
                csv.append(String.format("\"%s\";\"%s\";\"%s\";\"%s\"\n",
                        studentId != null ? studentId : "",
                        name != null ? name : "",
                        email,
                        answerLabel));
            }
        }
        // devolve o csv para ser exportado pelo servidor
        return new Message(Message.Type.EXPORT_CSV_RESPONSE, csv.toString());
    }

    private Message handleDeleteQuestion(String accessCode) throws SQLException {
        Connection conn = dbManager.getConnection();

        // prepara a query para obter o id da pergunta
        int questionId = -1;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM questions WHERE access_code = ?")) {
            ps.setString(1, accessCode);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) // tenta obter o id da pergunta
                questionId = rs.getInt("id");
        }

        if (questionId == -1) // se a pergunta nao existir
            return new Message(Message.Type.DELETE_QUESTION_RESPONSE, "Pergunta nao encontrada.");

        // verifica se a pergunta tem respostas
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM answers WHERE question_id = ?")) {
            ps.setInt(1, questionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) { // se a pergunta tiver respostas nao pode eliminar
                return new Message(Message.Type.DELETE_QUESTION_RESPONSE,
                        "Nao pode eliminar: A pergunta ja tem respostas.");
            }
        }

        // elimina a pergunta
        String sql = "DELETE FROM questions WHERE id = " + questionId;
        server.executeUpdate(sql);

        return new Message(Message.Type.DELETE_QUESTION_RESPONSE, "Pergunta eliminada com sucesso.");
    }

    private Message handleEditQuestion(String[] data) throws SQLException {
        // Data: [codigo de acesso, enunciado, opcoes, opcao correta, startTime,
        // endTime]
        String accessCode = data[0];

        // verifica se a pergunta existe
        Connection conn = dbManager.getConnection();
        int questionId = -1;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM questions WHERE access_code = ?")) {
            ps.setString(1, accessCode);
            ResultSet rs = ps.executeQuery();
            if (rs.next())
                questionId = rs.getInt("id");
        }

        if (questionId == -1) // se a pergunta nao existir
            return new Message(Message.Type.EDIT_QUESTION_RESPONSE, false);

        // verifica se a pergunta tem respostas
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM answers WHERE question_id = ?")) {
            ps.setInt(1, questionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                // se a pergunta tiver respostas nao pode editar
                return new Message(Message.Type.EDIT_QUESTION_RESPONSE, false);
            }
        }
        // prepara variaveis para query de edicao
        String prompt = normSql(data[1]);
        String options = normSql(data[2]);
        String correctOption = data[3];
        long start = parseDateToTimestamp(data[4]);
        long end = parseDateToTimestamp(data[5]);

        String sql = String.format( // prepara query de edicao e preenche os valores
                "UPDATE questions SET prompt='%s', options='%s', correct_option=%s, start_time=%d, end_time=%d WHERE id=%d",
                prompt, options, correctOption, start, end, questionId);

        server.executeUpdate(sql); // executa a query
        return new Message(Message.Type.EDIT_QUESTION_RESPONSE, true);
    }

    private Message handleGetQuestionAnswers(String accessCode) throws SQLException {
        Connection conn = dbManager.getConnection();
        List<String> report = new ArrayList<>();

        // verifica se a pergunta existe e se esta expirada
        int questionId = -1;
        long endTime = 0;
        try (PreparedStatement ps = conn.prepareStatement( // prepara query de verificacao
                "SELECT id, end_time FROM questions WHERE access_code = ?")) {
            ps.setString(1, accessCode);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                questionId = rs.getInt("id");
                endTime = rs.getLong("end_time");
            }
        }

        if (questionId == -1) // se a pergunta nao existir
            return new Message(Message.Type.GET_QUESTION_ANSWERS_RESPONSE, null);

        long now = System.currentTimeMillis() / 1000; // obtem o timestamp atual
        if (now < endTime) { // se a pergunta ainda nao expirou
            report.add("Aviso: A pergunta ainda nao expirou."); // adiciona a mensagem de aviso ao relatorio
        }
        // prepara a query de selecao
        String sql = "SELECT u.name, u.student_id, a.answer_index FROM answers a " +
                "JOIN users u ON a.student_email = u.email " +
                "WHERE a.question_id = ?";
        // executa a query
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, questionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) { // percorre o resultado
                String line = String.format("Aluno: %s (%s) - Resposta: %s",
                        rs.getString("name"),
                        rs.getString("student_id"),
                        String.valueOf((char) ('a' + rs.getInt("answer_index"))));
                report.add(line); // adiciona a linha ao relatorio
            }
        }

        if (report.isEmpty()) // se nao houver respostas
            report.add("Sem respostas submetidas.");

        return new Message(Message.Type.GET_QUESTION_ANSWERS_RESPONSE, report);
    }

    private Message handleGetStudentHistory(String[] data) throws SQLException {
        // data = [email, filtro]
        String email = data[0];
        String filter = data[1]; // "TUDO", "CORRETO", "INCORRETO", "ULTIMAS_24H"

        StringBuilder sql = new StringBuilder(); // cria a query
        sql.append("SELECT q.prompt, q.options, q.correct_option, a.answer_index, a.timestamp ");
        sql.append("FROM answers a ");
        sql.append("JOIN questions q ON a.question_id = q.id ");
        sql.append("WHERE a.student_email = ?");

        // Aplica os filtros
        if ("CORRETO".equalsIgnoreCase(filter)) {
            sql.append(" AND a.answer_index = q.correct_option");
        } else if ("INCORRETO".equalsIgnoreCase(filter)) {
            sql.append(" AND a.answer_index != q.correct_option");
        } else if ("ULTIMAS_24H".equalsIgnoreCase(filter)) {
            long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            sql.append(" AND a.timestamp >= ").append(oneDayAgo);
        }

        List<String> history = new ArrayList<>(); // cria lista para armazenar o historico

        Connection conn = dbManager.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String prompt = rs.getString("prompt");
                int myAnswerIdx = rs.getInt("answer_index");
                int correctIdx = rs.getInt("correct_option");
                long timestamp = rs.getLong("timestamp");

                boolean isCorrect = (myAnswerIdx == correctIdx);
                String result = isCorrect ? "[CERTO]" : "[ERRADO]";

                // converte o indice para letra
                char myAnswerChar = (char) ('a' + myAnswerIdx);
                char correctChar = (char) ('a' + correctIdx);

                // Formatar Data
                String dateStr = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                        .format(java.time.LocalDateTime.ofEpochSecond(timestamp / 1000, 0, java.time.ZoneOffset.UTC));

                // Construir linha de resumo
                String line = String.format("%s | %s | Sua resp: %c | Correta: %c | Data: %s",
                        result, prompt, myAnswerChar, correctChar, dateStr);

                history.add(line); // adiciona a linha construida ao historico
            }
        }

        if (history.isEmpty()) {
            history.add("Nenhum registo encontrado com esse filtro.");
        }

        return new Message(Message.Type.GET_STUDENT_HISTORY_RESPONSE, history);
    }

    private String hash(String input) { // funcao auxiliar para gerar um hash
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); // define o tipo de hash
            byte[] hash = digest.digest(input.getBytes()); // gera o hash
            StringBuilder hexString = new StringBuilder(); // cria uma string para armazenar o hash
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    private String normSql(String input) { // funcao auxiliar para normalizar as aspas
        if (input == null)
            return null;
        return input.replace("'", "''");
    }

    private long parseDateToTimestamp(String dateStr) {
        try { // funcao auxiliar para converter uma string de data para um timestamp
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime dateTime = LocalDateTime.parse(dateStr, formatter);
            return dateTime.toEpochSecond(ZoneOffset.UTC);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    // metodo para enviar mensagens
    public synchronized void sendMessage(Message msg) {
        try {
            if (!socket.isClosed() && out != null) {
                out.writeObject(msg);
                out.flush();
            }
        } catch (IOException e) {
            // Se der erro, o servidor trata de remover o cliente depois
        }
    }
}
