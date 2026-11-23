# NetQuiz Arena

**Final Project Pemrograman Jaringan [TIS31263] - Kelompok 3**
Multiplayer Live Battle & Trivia Platform: kuis real-time dengan lobby multiplayer, leaderboard
sinkron, penentu buzzer tercepat (UDP), dan ekspor sertifikat PDF.

## Anggota Kelompok

| No | Nama | NIM |
|----|------|-----|
| 1 | Naufal Huseini | 2407114249 |
| 2 | Muhammad Faiz Al Khairi | 2407134861 |
| 3 | Naufa Alya Nafisa | 2407126391 |

---

## Pendahuluan

### Deskripsi
NetQuiz Arena adalah aplikasi kuis terdistribusi berbasis arsitektur klien-server. Pemain dapat
mengerjakan **soal solo** (poin pribadi) atau masuk ke **lobby multiplayer** untuk berebut menjawab
soal yang sama secara serentak. Pemain tercepat menekan tombol **BUZZ!** (paket UDP yang tiba lebih
dulu di server) berhak menjawab. Poin multiplayer masuk ke leaderboard dan dicetak pada sertifikat PDF.

### Fitur utama
- Soal solo dan ronde multiplayer, soal diambil dari Open Trivia Database (REST API).
- Lobby multiplayer: masuk/keluar lobby, pengaturan kategori & kesulitan **disinkronkan** ke seluruh anggota lobby, ronde baru dapat dimulai bila minimal 2 pemain.
- Buzzer tercepat dan timer countdown 20 detik lewat UDP.
- Skor **terpisah**: poin solo (pribadi) dan leaderboard multiplayer.
- Chat: siaran ke semua pemain, chat privat, dan chat khusus anggota lobby.
- Unduh sertifikat PDF (berisi poin dan peringkat multiplayer) lewat transfer file biner.
- Dua antarmuka klien: **GUI Web (HTML/CSS/JS)** dan **CLI**.

---

## Spesifikasi Arsitektur

### Struktur proyek
```
NetQuizV3/
├── server/          # Logika inti server & pemrosesan kuis
│   ├── NetQuizServer.java
│   ├── ClientHandler.java
│   ├── ServerState.java
│   ├── QuizRound.java
│   ├── UdpService.java
│   ├── TriviaApiFetcher.java
│   ├── QuestionBank.java
│   ├── Question.java
│   ├── CertificateGenerator.java
│   ├── TriviaResponseDTO.java
│   ├── TriviaQuestionDTO.java
│   ├── TextUtil.java
│   └── ServerLogger.java
├── client/          # Aplikasi klien (CLI & jembatan Web)
│   ├── NetQuizClient.java         # Antarmuka Command Line (CLI)
│   ├── NetQuizConnection.java     # Mesin koneksi jaringan (TCP/UDP)
│   ├── NetQuizWebBridge.java      # Jembatan komunikasi ke GUI Web
│   └── ClientLogger.java
├── web/             # Antarmuka pengguna berbasis web (GUI)
│   ├── index.html
│   ├── style.css
│   └── app.js
├── scripts/         # Skrip otomatisasi build & eksekusi (.bat & .sh)
│   ├── build.*
│   ├── run-server.*
│   └── run-web.*
└── lib/             # Dependensi eksternal
    └── gson-2.15.0.jar

```

### Arsitektur
```
 Browser (HTML/CSS/JS)
        │
        │  HTTP + Server-Sent Events (localhost)
        ▼
 NetQuizWebBridge ─── NetQuizConnection ──┐          ┌──────────────────────────┐
                                          ├─ TCP 12345 ─▶│                          │
 NetQuizClient (CLI) ─────────────────────┤          │      NetQuizServer       │
                                          ├─ UDP 12346 ─▶│  (thread pool 20 klien) │
                                          └─ TCP acak ──▶│  Open Trivia DB (HTTPS)  │
                                                     └──────────────────────────┘
```

### Port
| Port | Protokol | Fungsi |
|---|---|---|
| 12345 | TCP | Protokol aplikasi (perintah teks + pesan `PUSH`) |
| 12346 | UDP | Registrasi, heartbeat, timer `TICK`, buzzer |
| acak (sementara) | TCP | Soket data unduhan sertifikat PDF |
| 8888 (dapat diubah) | HTTP | Antara browser dan `NetQuizWebBridge` (lokal) |

### Aturan permainan
- **Solo:** jawaban benar +10 poin (poin solo). Tidak tersedia bagi pemain yang berada di lobby.
- **Multiplayer:** pemain di lobby berebut `BUZZ!`; pemenang buzzer menjawab. Benar: `10 + sisa waktu / 2` poin dan ronde selesai. Salah: -5 poin, pemain terkunci, buzzer dibuka kembali untuk pemain lain. Ronde berakhir bila waktu habis atau semua anggota lobby terkunci.
- Ronde hanya dapat dimulai oleh anggota lobby dan minimal ada 2 pemain.
- Pemain tidak dapat masuk lobby saat ronde berlangsung.

---

## Kompilasi & Menjalankan

**Prasyarat:** JDK 8 atau lebih baru (`javac -version`). Library `lib/gson-2.15.0.jar` sudah disertakan.

### Windows (CMD / PowerShell, dari folder proyek)
```
scripts\build.bat
scripts\run-server.bat          :: jendela 1
scripts\run-web.bat 8888        :: jendela 2, lalu buka http://localhost:8888
```
Di PowerShell tulis `.\scripts\build.bat`. Argumen port pada `run-web.bat` opsional (bawaan 8888).

### Linux / macOS
```
chmod +x scripts/*.sh
scripts/build.sh
scripts/run-server.sh           # terminal 1
scripts/run-web.sh 8888         # terminal 2, lalu buka http://localhost:8888
```

### Tanpa skrip
```
javac -cp lib/gson-2.15.0.jar -d out server/*.java client/*.java
java -cp out:lib/gson-2.15.0.jar server.NetQuizServer              # Windows: pemisah ';'
java -cp out:lib/gson-2.15.0.jar client.NetQuizWebBridge 8888
java -cp out:lib/gson-2.15.0.jar client.NetQuizClient [host]       # klien CLI
```

### Memakai GUI Web
1. Jalankan server, lalu jembatan web, dan buka alamat yang ditampilkan.
2. Isi alamat server (`localhost` bila satu komputer), nama pemain, dan port (bawaan 12345 / 12346).
3. **Satu tab browser = satu pemain.** Buka beberapa tab dengan nama berbeda untuk uji multiplayer.
4. Untuk bermain multiplayer: klik **Masuk Lobby Multiplayer**, atur kategori/kesulitan, lalu **Mulai Ronde Multi** (minimal 2 pemain di lobby). Tekan **BUZZ!** lalu pilih jawaban.

### Demo antar-komputer
Jalankan server di satu komputer dan izinkan TCP 12345, UDP 12346, serta port TCP sementara di firewall.
Di komputer lain, jalankan jembatan web lalu isi kolom "Alamat server" dengan IP komputer server.