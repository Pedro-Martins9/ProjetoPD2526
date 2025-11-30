package server;

import java.sql.*;
import java.io.File;

/*
Responsável pela gestão da base de dados
*/
public class DatabaseManager {
    private String dbPath;
    private Connection connection;
    private int dbVersion = 0;

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    public void connect() throws SQLException {
        // abre ou cria ficheiro de base de dados
        File dbFile = new File(dbPath);
        if (dbFile.getParentFile() != null) {
            dbFile.getParentFile().mkdirs();
        }

        String url = "jdbc:sqlite:" + dbPath;
        connection = DriverManager.getConnection(url);
        System.out.println("Base de dados iniciada: " + dbPath);

        initialize();
    }

    private void initialize() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // cria tabela de configuração para gerir versões
            stmt.execute("CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT)");

            // verifica a versão
            ResultSet rs = stmt.executeQuery("SELECT value FROM config WHERE key = 'version'");
            if (rs.next()) {
                dbVersion = Integer.parseInt(rs.getString("value"));
            } else {
                stmt.execute("INSERT INTO config (key, value) VALUES ('version', '0')");
                dbVersion = 0;
            }

            // cria tabela de users (docentes e estudantes)
            // Docente: name, email, password, hash_code
            // Estudante: id, name, email, password

            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "email TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "password TEXT NOT NULL, " +
                    "role TEXT NOT NULL, " + // docente ou estudante
                    "student_id TEXT, " + // para estudantes
                    "teacher_code_hash TEXT" + // para docentes
                    ")");

            // cria tabela de perguntas
            stmt.execute("CREATE TABLE IF NOT EXISTS questions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "prompt TEXT NOT NULL, " +
                    "options TEXT NOT NULL, " +
                    "correct_option INTEGER NOT NULL, " +
                    "start_time INTEGER NOT NULL, " +
                    "end_time INTEGER NOT NULL, " +
                    "access_code TEXT UNIQUE NOT NULL, " +
                    "creator_email TEXT NOT NULL, " +
                    "FOREIGN KEY(creator_email) REFERENCES users(email)" +
                    ")");

            // cria tabela de respostas
            stmt.execute("CREATE TABLE IF NOT EXISTS answers (" +
                    "question_id INTEGER, " +
                    "student_email TEXT, " +
                    "answer_index INTEGER NOT NULL, " +
                    "timestamp INTEGER NOT NULL, " +
                    "PRIMARY KEY (question_id, student_email), " +
                    "FOREIGN KEY(question_id) REFERENCES questions(id), " +
                    "FOREIGN KEY(student_email) REFERENCES users(email)" +
                    ")");
        }
    }

    public int getDbVersion() {
        return dbVersion;
    }

    public synchronized void executeUpdate(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            dbVersion++;
            stmt.execute("UPDATE config SET value = '" + dbVersion + "' WHERE key = 'version'");
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    public String getDbPath() {
        return dbPath;
    }
}
