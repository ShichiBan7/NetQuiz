package server;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;

public class ServerLogger {
    private static final String LOG_FILE = "server_activity.log";

    public static synchronized void log(String message) {
        String logEntry = "[" + new Date() + "] " + message;
        System.out.println(logEntry); // Tampilkan di konsol server
        
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            bw.write(logEntry);
            bw.newLine();
        } catch (IOException e) {
            System.err.println("Gagal menulis log server: " + e.getMessage());
        }
    }
}