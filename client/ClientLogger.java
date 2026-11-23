package client;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;

public class ClientLogger {
    private static final String LOG_FILE = "client_activity.log";

    public static synchronized void log(String message) {
        String logEntry = "[" + new Date() + "] " + message;
        System.out.println(logEntry); // Tampilkan di konsol klien
        
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            bw.write(logEntry);
            bw.newLine();
        } catch (IOException e) {
            System.err.println("Gagal menulis log klien: " + e.getMessage());
        }
    }
}