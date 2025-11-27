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

public class ClientHandler implements Runnable {
    private Socket socket;
    private DatabaseManager dbManager;
    private Server server;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    public ClientHandler(Socket socket, DatabaseManager dbManager, Server server) {
        this.socket = socket;
        this.dbManager = dbManager;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            //Avisar o servidor que este cliente entrou
            server.addClient(this);

            while (!socket.isClosed()) {
                Message request = (Message) in.readObject();
                Message response = handleRequest(request);

                sendMessage(response);
            }
        } catch (EOFException e) {
            // Client disconnected
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            //Avisar o servidor que este cliente saiu
            server.removeClient(this);
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private Message handleRequest(Message request) {
        try {
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
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, email);
            pstmt.setString(2, password);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                String role = rs.getString("role");
                System.out.println("User " + email + " logged in as " + role);
                return new Message(Message.Type.LOGIN_RESPONSE, role);
            } else {
                System.out.println("Login failed for " + email);
            }
        }
        return new Message(Message.Type.LOGIN_RESPONSE, null);
    }

    private Message handleRegister(String[] data) throws SQLException {
        String name = escapeSql(data[0]);
        String email = escapeSql(data[1]);
        String password = escapeSql(data[2]);
        String role = escapeSql(data[3]);

        if (email == null || !email.contains("@")) {
            System.out.println("Invalid email format: " + email);
            return new Message(Message.Type.REGISTER_RESPONSE, false);
        }

        String studentId = null;
        String teacherCode = null;

        if ("STUDENT".equals(role)) {
            String rawId = data[4];
            if (rawId == null || !rawId.matches("\\d+")) {
                System.out.println("Invalid student ID (must be numeric): " + rawId);
                return new Message(Message.Type.REGISTER_RESPONSE, false);
            }
            studentId = escapeSql(rawId);
        } else {
            // Validate Teacher Code
            String inputCode = data[4].trim();
            String hashedCode = hash(inputCode);
            // Hardcoded valid hash for teachers
            // SHA-256("PROFE123")
            String validHash = hash("PROFE123");

            if (!validHash.equals(hashedCode)) {
                System.out.println("Invalid teacher code provided: " + inputCode);
                return new Message(Message.Type.REGISTER_RESPONSE, false);
            }
            teacherCode = hashedCode;
        }

        String query = String.format(
                "INSERT INTO users (name, email, password, role, student_id, teacher_code_hash) VALUES ('%s', '%s', '%s', '%s', '%s', '%s')",
                name, email, password, role, studentId != null ? studentId : "NULL",
                teacherCode != null ? teacherCode : "NULL");

        server.executeUpdate(query);
        return new Message(Message.Type.REGISTER_RESPONSE, true);
    }

    private Message handleCreateQuestion(String[] data) throws SQLException {
        String prompt = escapeSql(data[0]);
        String options = escapeSql(data[1]);
        String correctOption = data[2]; // Integer as string, safe
        String startTimeStr = data[3];
        String endTimeStr = data[4];
        String accessCode = escapeSql(data[5]);
        String creatorEmail = escapeSql(data[6]);

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
        // Notificar todos os alunos
        String notificationMsg = "ATENÇÃO: Nova pergunta disponível -> " + data[0];
        server.broadcast(new Message(Message.Type.NOTIFICATION, notificationMsg), this);

        return new Message(Message.Type.CREATE_QUESTION_RESPONSE, true);
    }

    private Message handleListQuestions(String filter) throws SQLException {
        String sql = "SELECT * FROM questions";


        if (filter == null) filter = "ALL";

        long now = System.currentTimeMillis() / 1000;


        if ("ACTIVE".equals(filter)) {
            sql += " WHERE start_time <= " + now + " AND end_time >= " + now;
        } else if ("EXPIRED".equals(filter)) {
            sql += " WHERE end_time < " + now;
        } else if ("FUTURE".equals(filter)) {
            sql += " WHERE start_time > " + now;
        }


        List<String> questions = new ArrayList<>();
        Connection conn = dbManager.getConnection();
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                questions.add(
                        rs.getString("id") + ": " + rs.getString("prompt") +
                                " (" + rs.getString("access_code") + ")");
            }
        }
        return new Message(Message.Type.LIST_QUESTIONS_RESPONSE, questions);
    }
    private Message handleGetQuestion(String accessCode) throws SQLException {
        String sql = "SELECT * FROM questions WHERE access_code = ?";
        Connection conn = dbManager.getConnection();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, accessCode);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                String prompt = rs.getString("prompt");
                String options = rs.getString("options");
                return new Message(Message.Type.GET_QUESTIONS_RESPONSE, new String[] { prompt, options });
            }
        }
        return new Message(Message.Type.GET_QUESTIONS_RESPONSE, null);
    }

    private Message handleSubmitAnswer(String[] data) throws SQLException {
        String accessCode = data[0];
        String answerIndex = data[1];
        String studentEmail = data[2];

        String sqlId = "SELECT id FROM questions WHERE access_code = ?";
        int questionId = -1;

        Connection conn = dbManager.getConnection();
        try (PreparedStatement pstmt = conn.prepareStatement(sqlId)) {
            pstmt.setString(1, accessCode);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next())
                questionId = rs.getInt("id");
        }

        if (questionId == -1)
            return new Message(Message.Type.SUBMIT_ANSWER_RESPONSE, false);

        // Check if already answered
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

        String query = String.format(
                "INSERT INTO answers (question_id, student_email, answer_index, timestamp) VALUES (%d, '%s', %s, %d)",
                questionId, escapeSql(studentEmail), answerIndex, System.currentTimeMillis());

        server.executeUpdate(query);
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
            }
        }

        if (questionId == -1) {
            return new Message(Message.Type.EXPORT_CSV_RESPONSE, null);
        }

        // Format Dates
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

        LocalDateTime startDateTime = LocalDateTime.ofEpochSecond(startTime, 0, ZoneOffset.UTC);
        LocalDateTime endDateTime = LocalDateTime.ofEpochSecond(endTime, 0, ZoneOffset.UTC);

        String dateStr = startDateTime.format(dateFormatter);
        String startTimeStr = startDateTime.format(timeFormatter);
        String endTimeStr = endDateTime.format(timeFormatter);

        // Parse Options
        String[] options = optionsStr.split(",");
        String correctOptionLabel = String.valueOf((char) ('a' + correctOptionIndex));

        // Section 1: Question Info
        csv.append("\"dia\";\"hora inicial\";\"hora final\";\"enunciado da pergunta\";\"opção certa\"\n");
        csv.append(String.format("\"%s\";\"%s\";\"%s\";\"%s\";\"%s\"\n",
                dateStr, startTimeStr, endTimeStr, prompt.replace("\"", "\"\""), correctOptionLabel));

        // Section 2: Options
        csv.append("\"opção\";\"texto da opção\"\n");
        for (int i = 0; i < options.length; i++) {
            String optLabel = String.valueOf((char) ('a' + i));
            String optText = options[i].trim();
            if (optText.matches("^[a-zA-Z]:.*")) {
                optText = optText.substring(2).trim();
            }
            csv.append(String.format("\"%s\";\"%s\"\n", optLabel, optText.replace("\"", "\"\"")));
        }

        // Section 3: Answers
        csv.append("\"número de estudante\"; \"nome\"; \"e-mail\";\"resposta\"\n");

        String sqlAnswers = "SELECT a.answer_index, a.student_email, u.name, u.student_id " +
                "FROM answers a " +
                "JOIN users u ON a.student_email = u.email " +
                "WHERE a.question_id = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sqlAnswers)) {
            pstmt.setInt(1, questionId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                int answerIndex = rs.getInt("answer_index");
                String answerLabel = String.valueOf((char) ('a' + answerIndex));
                String studentId = rs.getString("student_id");
                String name = rs.getString("name");
                String email = rs.getString("student_email");

                csv.append(String.format("\"%s\";\"%s\";\"%s\";\"%s\"\n",
                        studentId != null ? studentId : "",
                        name != null ? name : "",
                        email,
                        answerLabel));
            }
        }

        return new Message(Message.Type.EXPORT_CSV_RESPONSE, csv.toString());
    }

    private Message handleDeleteQuestion(String accessCode) throws SQLException {
        Connection conn = dbManager.getConnection();

        // Obter ID da pergunta
        int questionId = -1;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM questions WHERE access_code = ?")) {
            ps.setString(1, accessCode);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) questionId = rs.getInt("id");
        }

        if (questionId == -1) return new Message(Message.Type.DELETE_QUESTION_RESPONSE, "Pergunta não encontrada.");

        // Verificar se já tem respostas
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM answers WHERE question_id = ?")) {
            ps.setInt(1, questionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                return new Message(Message.Type.DELETE_QUESTION_RESPONSE, "Não pode eliminar: A pergunta já tem respostas.");
            }
        }

        // Se chegou aqui, pode apagar. Usamos o server.executeUpdate para replicar para o cluster
        String sql = "DELETE FROM questions WHERE id = " + questionId;
        server.executeUpdate(sql);

        return new Message(Message.Type.DELETE_QUESTION_RESPONSE, "Pergunta eliminada com sucesso.");
    }


    private Message handleEditQuestion(String[] data) throws SQLException {
        // Data: [oldAccessCode, prompt, options, correctOption, startTime, endTime]
        String accessCode = data[0];

        // check existence and if it has answers
        Connection conn = dbManager.getConnection();
        int questionId = -1;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM questions WHERE access_code = ?")) {
            ps.setString(1, accessCode);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) questionId = rs.getInt("id");
        }

        if (questionId == -1) return new Message(Message.Type.EDIT_QUESTION_RESPONSE, false);

        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM answers WHERE question_id = ?")) {
            ps.setInt(1, questionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                // Return false or mensage of erro
                return new Message(Message.Type.EDIT_QUESTION_RESPONSE, false);
            }
        }


        String prompt = escapeSql(data[1]);
        String options = escapeSql(data[2]);
        String correctOption = data[3];
        long start = parseDateToTimestamp(data[4]);
        long end = parseDateToTimestamp(data[5]);


        String sql = String.format(
                "UPDATE questions SET prompt='%s', options='%s', correct_option=%s, start_time=%d, end_time=%d WHERE id=%d",
                prompt, options, correctOption, start, end, questionId
        );

        server.executeUpdate(sql);
        return new Message(Message.Type.EDIT_QUESTION_RESPONSE, true);
    }


    private Message handleGetQuestionAnswers(String accessCode) throws SQLException {
        Connection conn = dbManager.getConnection();
        List<String> report = new ArrayList<>();

        //  Check is it exists and if it is expired
        int questionId = -1;
        long endTime = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, end_time FROM questions WHERE access_code = ?")) {
            ps.setString(1, accessCode);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                questionId = rs.getInt("id");
                endTime = rs.getLong("end_time");
            }
        }

        if (questionId == -1) return new Message(Message.Type.GET_QUESTION_ANSWERS_RESPONSE, null);


        long now = System.currentTimeMillis() / 1000;
        if (System.currentTimeMillis()/1000 < endTime) {
            report.add("Aviso: A pergunta ainda não expirou.");
        }

        String sql = "SELECT u.name, u.student_id, a.answer_index FROM answers a " +
                "JOIN users u ON a.student_email = u.email " +
                "WHERE a.question_id = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, questionId);
            ResultSet rs = ps.executeQuery();
            while(rs.next()) {
                String line = String.format("Aluno: %s (%s) - Resposta: %s",
                        rs.getString("name"),
                        rs.getString("student_id"),
                        String.valueOf((char)('a' + rs.getInt("answer_index")))
                );
                report.add(line);
            }
        }

        if (report.isEmpty()) report.add("Sem respostas submetidas.");

        return new Message(Message.Type.GET_QUESTION_ANSWERS_RESPONSE, report);
    }

    private Message handleGetStudentHistory(String[] data) throws SQLException {
        // data[0] = email, data[1] = filtro
        String email = data[0];
        String filter = data[1]; // "ALL", "CORRECT", "INCORRECT", "LAST_24H"

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT q.prompt, q.options, q.correct_option, a.answer_index, a.timestamp ");
        sql.append("FROM answers a ");
        sql.append("JOIN questions q ON a.question_id = q.id ");
        sql.append("WHERE a.student_email = ?");

        // Apply Filters
        if ("CORRECT".equals(filter)) {
            sql.append(" AND a.answer_index = q.correct_option");
        } else if ("INCORRECT".equals(filter)) {
            sql.append(" AND a.answer_index != q.correct_option");
        } else if ("LAST_24H".equals(filter)) {

            long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            sql.append(" AND a.timestamp >= ").append(oneDayAgo);
        }

        List<String> history = new ArrayList<>();

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

                // Converter índice para letra (0 -> a, 1 -> b...)
                char myAnswerChar = (char) ('a' + myAnswerIdx);
                char correctChar = (char) ('a' + correctIdx);

                // Formatar Data
                String dateStr = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                        .format(java.time.LocalDateTime.ofEpochSecond(timestamp / 1000, 0, java.time.ZoneOffset.UTC));

                // Construir linha de resumo
                String line = String.format("%s | %s | Sua resp: %c | Correta: %c | Data: %s",
                        result, prompt, myAnswerChar, correctChar, dateStr);

                history.add(line);
            }
        }

        if (history.isEmpty()) {
            history.add("Nenhum registo encontrado com esse filtro.");
        }

        return new Message(Message.Type.GET_STUDENT_HISTORY_RESPONSE, history);
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
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

    private String escapeSql(String input) {
        if (input == null)
            return null;
        return input.replace("'", "''");
    }

    private long parseDateToTimestamp(String dateStr) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime dateTime = LocalDateTime.parse(dateStr, formatter);
            return dateTime.toEpochSecond(ZoneOffset.UTC);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    // Metodo novo para evitar conflitos entre threads
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
