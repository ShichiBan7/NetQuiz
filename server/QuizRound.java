package server;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Satu ronde kuis multiplayer.
 *  - Soal disiarkan ke anggota LOBBY lewat TCP (pemain di luar lobby tidak ikut).
 *  - Hitung mundur disiarkan lewat UDP (TICK) setiap detik (Pilar 5).
 *  - Buzzer: paket UDP BUZZ yang TIBA PERTAMA di server menang. Semua metode state
 *    bersifat synchronized sehingga hanya satu pemenang (bebas race condition).
 */
public final class QuizRound {
    private final Question question;
    private int remaining;
    private boolean active = true;
    private String buzzWinner = null;
    private final Set<String> locked = new HashSet<>();   // pemain yang sudah salah
    private ScheduledFuture<?> timerTask;

    QuizRound(Question q, int seconds) {
        this.question = q;
        this.remaining = seconds;
    }

    public Question getQuestion() { return question; }

    public synchronized boolean isActive() { return active; }

    void begin() {
        ServerState.broadcastLobby("PUSH QUESTION " + question.format()
                + " || (Tekan BUZZ dulu, lalu jawab. Waktu: " + remaining + " detik)");
        ServerLogger.log("[RONDE] Dimulai (" + question.source + "). Jawaban benar: " + question.correctText());
        timerTask = ServerState.scheduler.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                try { tick(); } catch (Throwable t) {          // jangan sampai timer mati diam-diam
                    ServerLogger.log("[ERROR] Timer ronde: " + t);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private synchronized void tick() {
        if (!active) return;
        remaining--;
        UdpService udp = ServerState.udp;
        if (udp != null) {
            for (String user : ServerState.lobby) udp.sendTo(user, "TICK " + remaining);
        }
        if (remaining <= 0) finish("Waktu habis!");
    }

    /** Dipanggil oleh UdpService. Mengembalikan WIN | LATE | LOCKED | NOROUND. */
    public synchronized String buzz(String user) {
        if (!active) return "NOROUND";
        if (!ServerState.lobby.contains(user)) return "NOLOBBY";
        if (locked.contains(user)) return "LOCKED";
        if (buzzWinner == null) {
            buzzWinner = user;
            ServerLogger.log("[BUZZER] Tercepat: " + user);
            ServerState.broadcastLobby("PUSH BUZZER " + user + " menekan buzzer paling cepat! Hanya "
                    + user + " yang boleh menjawab.");
            return "WIN";
        }
        return user.equals(buzzWinner) ? "WIN" : "LATE";   // paket retransmisi pemenang tetap WIN
    }

    /** Dipanggil handler TCP. Mengembalikan baris respons lengkap. */
    public synchronized String answer(String user, int index) {
        if (!active) return "400 ERR Ronde sudah berakhir.";
        if (!ServerState.lobby.contains(user)) return "403 ERR Anda tidak berada di lobby ronde ini.";
        if (!user.equals(buzzWinner)) {
            return "403 ERR Anda belum memenangkan buzzer. Tekan BUZZ (menu 6) lebih dulu.";
        }
        if (index == question.correctIndex) {
            int points = 10 + Math.max(0, remaining) / 2;      // bonus kecepatan
            int total = ServerState.addScore(user, points);
            finish(user + " menjawab BENAR (+" + points + ")");
            return "200 CORRECT Benar! +" + points + " poin. Total skor: " + total;
        }
        int total = ServerState.addScore(user, -5);
        locked.add(user);
        buzzWinner = null;
        ServerLogger.log("[RONDE] " + user + " salah. Buzzer dibuka kembali.");
        ServerState.broadcastLobby("PUSH BUZZER_OPEN " + user + " salah (-5). Buzzer dibuka lagi untuk pemain lain!");
        if (allLocked()) finish("Semua pemain menjawab salah");
        return "200 WRONG Salah! -5 poin. Total skor: " + total;
    }

    /** Dipanggil saat pemain keluar di tengah ronde. */
    public synchronized void onUserLeft(String user) {
        if (!active) return;
        locked.remove(user);
        if (user.equals(buzzWinner)) {
            buzzWinner = null;
            ServerState.broadcastLobby("PUSH BUZZER_OPEN " + user + " keluar lobby. Buzzer dibuka kembali!");
        }
        if (ServerState.lobby.isEmpty()) finish("Semua pemain keluar");
        else if (allLocked()) finish("Semua pemain menjawab salah");
    }

    private boolean allLocked() {
        if (ServerState.lobby.isEmpty()) return true;
        for (String u : ServerState.lobby) if (!locked.contains(u)) return false;
        return true;
    }

    private void finish(String reason) {
        if (!active) return;
        active = false;
        if (timerTask != null) timerTask.cancel(false);
        ServerLogger.log("[RONDE] Selesai: " + reason);
        ServerState.broadcastLobby("PUSH ROUND_END " + reason + ". Jawaban benar: " + question.correctText()
                + " || " + ServerState.scoreboardText());
    }
}
