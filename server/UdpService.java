package server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * PILAR 5 - UDP Telemetry & Heartbeat Subsystem.
 * Satu DatagramSocket melayani:
 *   REG  <user>  : klien mendaftarkan alamat UDP-nya      -> REG_OK / REG_FAIL
 *   HB   <user>  : heartbeat berkala (cek koneksi hidup)   -> HB_ACK
 *   BUZZ <user>  : buzzer tercepat                         -> BUZZ_WIN / BUZZ_LATE / BUZZ_LOCKED / BUZZ_NOROUND
 * Server juga MENGIRIM "TICK <detik>" ke setiap klien terdaftar (timer countdown).
 * Klien menangani paket hilang dengan setSoTimeout + retransmisi di lapisan aplikasi.
 */
public final class UdpService implements Runnable {
    private final DatagramSocket socket;
    private volatile boolean running = true;

    public UdpService(int port) throws SocketException {
        this.socket = new DatagramSocket(port);
    }

    @Override
    public void run() {
        ServerLogger.log("[UDP] Layanan UDP aktif pada port " + socket.getLocalPort());
        byte[] buf = new byte[512];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
                handle(packet);
            } catch (IOException e) {
                if (running) ServerLogger.log("[UDP] Error terima paket: " + e.getMessage());
            } catch (Exception e) {
                ServerLogger.log("[UDP] Paket diabaikan (" + e + ")");   // paket aneh tidak boleh mematikan layanan
            }
        }
    }

    private void handle(DatagramPacket p) {
        String msg = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8).trim();
        String[] parts = msg.split("\\s+");
        if (parts.length != 2) { reply(p.getSocketAddress(), "ERR format"); return; }
        String cmd = parts[0].toUpperCase();
        String user = parts[1];
        SocketAddress from = p.getSocketAddress();

        switch (cmd) {
            case "REG":
                if (ServerState.users.containsKey(user)) {
                    ServerState.udpEndpoints.put(user, from);
                    ServerState.lastHeartbeat.put(user, System.currentTimeMillis());
                    ServerLogger.log("[UDP] " + user + " terdaftar dari " + from);
                    reply(from, "REG_OK");
                } else {
                    reply(from, "REG_FAIL");
                }
                break;
            case "HB":
                if (from.equals(ServerState.udpEndpoints.get(user))) {
                    ServerState.lastHeartbeat.put(user, System.currentTimeMillis());
                    reply(from, "HB_ACK");
                } else {
                    reply(from, "ERR not_registered");
                }
                break;
            case "BUZZ":
                if (!from.equals(ServerState.udpEndpoints.get(user))) { reply(from, "ERR not_registered"); break; }
                QuizRound r = ServerState.activeRound();
                reply(from, "BUZZ_" + (r == null ? "NOROUND" : r.buzz(user)));
                break;
            default:
                reply(from, "ERR unknown");
        }
    }

    private void reply(SocketAddress to, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException e) {
            ServerLogger.log("[UDP] Gagal kirim ke " + to + ": " + e.getMessage());
        }
    }

    /** Kirim paket ke pemain terdaftar (dipakai timer countdown). */
    public void sendTo(String user, String text) {
        SocketAddress to = ServerState.udpEndpoints.get(user);
        if (to != null) reply(to, text);
    }

    public void close() {
        running = false;
        socket.close();
    }
}
