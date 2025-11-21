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

public class ClientUI {
    private ClientCommunication comm;
    private Scanner scanner = new Scanner(System.in);
    private boolean running = true;
    private String userEmail = null;
    private String userRole = null;

    // Queue to handle synchronous responses while allowing async notifications
    private BlockingQueue<Message> responseQueue = new LinkedBlockingQueue<>();

    public static void main(String[] args) {
        new ClientUI().start();
    }

    public void start() {
        comm = new ClientCommunication(this);
        if (!comm.connect()) {
            System.out.println("Failed to connect to server.");
            return;
        }

        System.out.println("Connected to server.");

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
        comm.close();
    }

    // Callback from Communication Layer
    public void handleMessage(Message msg) {
        // If it's a notification (no request ID logic for now, assuming types)
        // Ideally we'd separate async events from request responses.
        // For this simple protocol, we'll assume everything is a response unless
        // specified.

        // In a real async system, we'd check msg.getType()
        // If it's NEW_QUESTION, print immediately.
        // If it's LOGIN_RESPONSE, put in queue.

        // Since the protocol is simple request-response, we put everything in queue
        // EXCEPT explicit notifications if we add them later.
        responseQueue.offer(msg);
    }

    public void onConnectionLost() {
        System.out.println("\nConnection lost. Attempting to reconnect...");
        comm.reconnect();
    }

    public void onReconnected() {
        System.out.println("Reconnected!");
    }

    public void onFatalError(String msg) {
        System.out.println("Fatal Error: " + msg);
        running = false;
        System.exit(1);
    }

    private Message sendRequestAndWait(Message req) {
        comm.sendRequest(req);
        try {
            // Wait for response
            return responseQueue.take();
        } catch (InterruptedException e) {
            return null;
        }
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

        Message response = sendRequestAndWait(
                new Message(Message.Type.LOGIN_REQUEST, new String[] { email, password }));

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

        Message response = sendRequestAndWait(
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
                if (isEndDateAfterStartDate(startTime, endTime))
                    break;
                else
                    System.out.println("End time must be after start time.");
            } else {
                System.out.println("Invalid format. Use YYYY-MM-DD HH:MM");
            }
        }

        String accessCode = String.valueOf((int) (Math.random() * 9000) + 1000);
        System.out.println("Generated Access Code: " + accessCode);

        Message response = sendRequestAndWait(new Message(Message.Type.CREATE_QUESTION, new String[] {
                prompt, options, correctOption, startTime, endTime, accessCode, userEmail
        }));

        if (response != null && response.getType() == Message.Type.CREATE_QUESTION_RESPONSE
                && (boolean) response.getContent()) {
            System.out.println("Question created successfully!");
        } else {
            System.out.println("Failed to create question.");
        }
    }

    private void listQuestions() {
        Message response = sendRequestAndWait(new Message(Message.Type.LIST_QUESTIONS, null));
        if (response != null && response.getType() == Message.Type.LIST_QUESTIONS_RESPONSE) {
            @SuppressWarnings("unchecked")
            List<String> questions = (List<String>) response.getContent();
            System.out.println("\n--- QUESTIONS ---");
            for (String q : questions)
                System.out.println(q);
        }
    }

    private void exportCsv() {
        System.out.println("\n--- EXPORT CSV ---");
        System.out.print("Access Code: ");
        String accessCode = scanner.nextLine();

        Message response = sendRequestAndWait(new Message(Message.Type.EXPORT_CSV, accessCode));

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

        Message response = sendRequestAndWait(new Message(Message.Type.GET_QUESTION, accessCode));

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

            Message submitResponse = sendRequestAndWait(
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
}
