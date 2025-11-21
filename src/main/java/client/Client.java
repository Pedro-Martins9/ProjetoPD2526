package client;

import common.Constants;
import common.Message;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Scanner;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Client {
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private Scanner scanner = new Scanner(System.in);
    private boolean running = true;
    private String userEmail = null;
    private String userRole = null;

    public static void main(String[] args) {
        new Client().start();
    }

    public void start() {
        if (!connectToServer()) {
            System.out.println("Failed to connect to any server. Exiting.");
            return;
        }

        while (running) {
            if (userEmail == null) {
                showLoginMenu();
            } else {
                if ("TEACHER".equals(userRole)) {
                    showTeacherMenu();
                } else {
                    showStudentMenu();
                }
            }
        }
    }

    private boolean connectToServer() {
        try {
            InetSocketAddress primary = getPrimaryServer();
            if (primary == null) {
                System.out.println("Could not find a server.");
                return false;
            }

            System.out.println("Connecting to " + primary);
            socket = new Socket(primary.getAddress(), primary.getPort());
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            System.out.println("Connected!");
            return true;

        } catch (Exception e) {
            System.out.println("Connection failed: " + e.getMessage());
            return false;
        }
    }

    private boolean reconnect() {
        System.out.println("Attempting to reconnect...");
        try {
            if (socket != null)
                socket.close();
        } catch (IOException e) {
            // Ignore
        }

        int retries = 0;
        while (retries < 5) {
            try {
                Thread.sleep(2000); // Wait before retrying
                InetSocketAddress primary = getPrimaryServer();
                if (primary != null) {
                    socket = new Socket(primary.getAddress(), primary.getPort());
                    out = new ObjectOutputStream(socket.getOutputStream());
                    in = new ObjectInputStream(socket.getInputStream());
                    System.out.println("Reconnected to " + primary);
                    return true;
                }
            } catch (Exception e) {
                System.out.println("Reconnect attempt " + (retries + 1) + " failed: " + e.getMessage());
            }
            retries++;
        }
        System.out.println("Could not reconnect to any server.");
        return false;
    }

    private Message sendRequest(Message request) {
        while (true) {
            try {
                if (socket == null || socket.isClosed()) {
                    throw new IOException("Socket closed");
                }
                out.writeObject(request);
                out.flush();
                return (Message) in.readObject();
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Connection lost (" + e.getMessage() + "). Reconnecting...");
                if (!reconnect()) {
                    return null;
                }
                // Loop will retry sending the request
            }
        }
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
            // e.printStackTrace(); // Suppress stack trace for cleaner output during
            // retries
        }
        return null;
    }

    private void showLoginMenu() {
        System.out.println("\n--- LOGIN ---");
        System.out.println("1. Login");
        System.out.println("2. Register");
        System.out.println("0. Exit");
        System.out.print("Option: ");

        String opt = scanner.nextLine();
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
                System.out.println("Invalid option");
        }
    }

    private void login() {
        System.out.print("Email: ");
        String email = scanner.nextLine();
        System.out.print("Password: ");
        String password = scanner.nextLine();

        Message response = sendRequest(new Message(Message.Type.LOGIN_REQUEST, new String[] { email, password }));

        if (response != null && response.getType() == Message.Type.LOGIN_RESPONSE
                && response.getContent() instanceof String) {
            userRole = (String) response.getContent();
            userEmail = email;
            System.out.println("Login successful as " + userRole);
        } else {
            System.out.println("Login failed.");
        }
    }

    private void register() {
        System.out.println("Register as: 1. Student, 2. Teacher");
        String type = scanner.nextLine().equals("2") ? "TEACHER" : "STUDENT";

        System.out.print("Name: ");
        String name = scanner.nextLine();
        System.out.print("Email: ");
        String email = scanner.nextLine();
        System.out.print("Password: ");
        String password = scanner.nextLine();

        String extra = "";
        if ("TEACHER".equals(type)) {
            System.out.print("Teacher Code: ");
            extra = scanner.nextLine();
        } else {
            System.out.print("Student ID: ");
            extra = scanner.nextLine();
        }

        Message response = sendRequest(
                new Message(Message.Type.REGISTER_REQUEST, new String[] { name, email, password, type, extra }));

        if (response != null && response.getType() == Message.Type.REGISTER_RESPONSE
                && (boolean) response.getContent()) {
            System.out.println("Registration successful!");
        } else {
            System.out.println("Registration failed.");
        }
    }

    private void showTeacherMenu() {
        System.out.println("\n--- TEACHER MENU ---");
        System.out.println("1. Create Question");
        System.out.println("2. List Questions");
        System.out.println("3. Export CSV");
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
                exportCsv();
                break;
            case "0":
                userEmail = null;
                break;
            default:
                System.out.println("Invalid option");
        }
    }

    private void exportCsv() {
        System.out.println("\n--- EXPORT CSV ---");
        System.out.print("Access Code: ");
        String accessCode = scanner.nextLine();

        Message response = sendRequest(new Message(Message.Type.EXPORT_CSV, accessCode));

        if (response != null && response.getType() == Message.Type.EXPORT_CSV_RESPONSE) {
            String csvContent = (String) response.getContent();
            if (csvContent == null) {
                System.out.println("Question not found or no answers available.");
            } else {
                String filename = "export_" + accessCode + ".csv";
                try (FileWriter fw = new FileWriter(filename)) {
                    fw.write(csvContent);
                    System.out.println("CSV exported successfully to " + filename);
                } catch (IOException e) {
                    System.out.println("Failed to write CSV file: " + e.getMessage());
                }
            }
        } else {
            System.out.println("Failed to export CSV.");
        }
    }

    private void createQuestion() {
        System.out.println("\n--- CREATE QUESTION ---");

        String prompt;
        while (true) {
            System.out.print("Prompt: ");
            prompt = scanner.nextLine();
            if (!prompt.trim().isEmpty())
                break;
            System.out.println("Prompt cannot be empty.");
        }

        String options;
        while (true) {
            System.out.print("Options (comma separated, at least 2): ");
            options = scanner.nextLine();
            if (options.split(",").length >= 2)
                break;
            System.out.println("Please provide at least 2 options.");
        }

        String correctOption;
        while (true) {
            System.out.print("Correct Option Index (0-based): ");
            correctOption = scanner.nextLine();
            try {
                int idx = Integer.parseInt(correctOption);
                if (idx >= 0 && idx < options.split(",").length)
                    break;
                System.out.println("Index out of bounds.");
            } catch (NumberFormatException e) {
                System.out.println("Invalid number. Please enter a digit.");
            }
        }

        String startTime;
        while (true) {
            System.out.print("Start Time (YYYY-MM-DD HH:MM): ");
            startTime = scanner.nextLine();
            if (isValidDate(startTime))
                break;
            System.out.println("Invalid format. Use YYYY-MM-DD HH:MM");
        }

        String endTime;
        while (true) {
            System.out.print("End Time (YYYY-MM-DD HH:MM): ");
            endTime = scanner.nextLine();
            if (isValidDate(endTime)) {
                if (isEndDateAfterStartDate(startTime, endTime)) {
                    break;
                } else {
                    System.out.println("End time must be after start time.");
                }
            } else {
                System.out.println("Invalid format. Use YYYY-MM-DD HH:MM");
            }
        }

        String accessCode = String.valueOf((int) (Math.random() * 9000) + 1000);
        System.out.println("Generated Access Code: " + accessCode);

        Message response = sendRequest(new Message(Message.Type.CREATE_QUESTION, new String[] {
                prompt, options, correctOption, startTime, endTime, accessCode, userEmail
        }));

        if (response != null && response.getType() == Message.Type.CREATE_QUESTION_RESPONSE
                && (boolean) response.getContent()) {
            System.out.println("Question created successfully!");
        } else {
            System.out.println("Failed to create question.");
        }
    }

    private boolean isValidDate(String date) {
        return date.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    private boolean isEndDateAfterStartDate(String start, String end) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime startDate = LocalDateTime.parse(start, formatter);
            LocalDateTime endDate = LocalDateTime.parse(end, formatter);
            return endDate.isAfter(startDate);
        } catch (Exception e) {
            return false;
        }
    }

    private void listQuestions() {
        Message response = sendRequest(new Message(Message.Type.LIST_QUESTIONS, null));

        if (response != null && response.getType() == Message.Type.LIST_QUESTIONS_RESPONSE) {
            @SuppressWarnings("unchecked")
            List<String> questions = (List<String>) response.getContent();
            System.out.println("\n--- QUESTIONS ---");
            for (String q : questions) {
                System.out.println(q);
            }
        }
    }

    private void showStudentMenu() {
        System.out.println("\n--- STUDENT MENU ---");
        System.out.println("1. Answer Question");
        System.out.println("0. Logout");

        String opt = scanner.nextLine();
        switch (opt) {
            case "1":
                answerQuestion();
                break;
            case "0":
                userEmail = null;
                break;
            default:
                System.out.println("Invalid option");
        }
    }

    private void answerQuestion() {
        System.out.println("\n--- ANSWER QUESTION ---");
        System.out.print("Access Code: ");
        String accessCode = scanner.nextLine();

        // 1. Get Question
        Message response = sendRequest(new Message(Message.Type.GET_QUESTION, accessCode));

        if (response != null && response.getType() == Message.Type.GET_QUESTIONS_RESPONSE
                && response.getContent() != null) {
            String[] data = (String[]) response.getContent();
            String prompt = data[0];
            String optionsStr = data[1];
            String[] options = optionsStr.split(",");

            System.out.println("Question: " + prompt);
            for (int i = 0; i < options.length; i++) {
                System.out.println(i + ": " + options[i].trim());
            }

            String answerIndex;
            while (true) {
                System.out.print("Your Answer (Index): ");
                answerIndex = scanner.nextLine();
                try {
                    int idx = Integer.parseInt(answerIndex);
                    if (idx >= 0 && idx < options.length)
                        break;
                    System.out.println("Index out of bounds.");
                } catch (NumberFormatException e) {
                    System.out.println("Invalid number. Please enter a digit.");
                }
            }

            // 2. Submit Answer
            Message submitResponse = sendRequest(
                    new Message(Message.Type.SUBMIT_ANSWER, new String[] { accessCode, answerIndex, userEmail }));

            if (submitResponse != null && submitResponse.getType() == Message.Type.SUBMIT_ANSWER_RESPONSE
                    && (boolean) submitResponse.getContent()) {
                System.out.println("Answer submitted successfully!");
            } else {
                System.out.println("Failed to submit answer.");
            }
        } else {
            System.out.println("Question not found or invalid.");
        }
    }
}
