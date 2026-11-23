package server;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Membuat sertifikat PDF (PDF 1.4 valid) tanpa library eksternal.
 * Hasilnya berupa byte[] yang dikirim lewat transfer biner (Pilar 3).
 */
public final class CertificateGenerator {
    private CertificateGenerator() {}

    private static final int W = 842, H = 595;   // A4 landscape

    public static byte[] generate(String name, int score, int rank, int totalPlayers) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
        StringBuilder c = new StringBuilder();

        c.append("3 w 0.10 0.25 0.55 RG 30 30 782 535 re S\n");
        c.append("0.8 w 44 44 754 507 re S\n");
        c.append("0.10 0.25 0.55 rg\n");
        center(c, "F2", 38, 470, "SERTIFIKAT PENCAPAIAN", 0.58);
        c.append("0.2 0.2 0.2 rg\n");
        center(c, "F1", 16, 425, "NetQuiz Arena", 0.50);
        center(c, "F1", 16, 370, "Diberikan kepada:", 0.50);
        c.append("0.10 0.25 0.55 rg\n");
        center(c, "F2", 44, 310, name, 0.58);
        c.append("0.2 0.2 0.2 rg\n");
        center(c, "F1", 18, 250, "atas partisipasi dan perolehan skor " + score + " poin", 0.50);
        center(c, "F2", 22, 210, "Peringkat " + rank + " dari " + totalPlayers + " pemain", 0.55);
        center(c, "F1", 13, 90, "Diterbitkan otomatis oleh NetQuiz Server pada " + date, 0.50);

        String content = c.toString();
        List<String> objs = new ArrayList<>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>");
        objs.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + W + " " + H + "] /Contents 4 0 R "
                + "/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>");
        objs.add("<< /Length " + content.length() + " >>\nstream\n" + content + "endstream");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>");

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objs.size()];
        for (int i = 0; i < objs.size(); i++) {
            offsets[i] = pdf.length();
            pdf.append(i + 1).append(" 0 obj\n").append(objs.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objs.size() + 1).append("\n");
        pdf.append("0000000000 65535 f \n");
        for (int off : offsets) pdf.append(String.format("%010d 00000 n \n", off));
        pdf.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\n");
        pdf.append("startxref\n").append(xref).append("\n%%EOF\n");

        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);   // semua karakter ASCII
    }

    private static void center(StringBuilder c, String font, int size, int y, String text, double widthFactor) {
        double x = Math.max(60, (W - text.length() * size * widthFactor) / 2.0);
        c.append("BT /").append(font).append(' ').append(size).append(" Tf ")
         .append(String.format(Locale.ROOT, "%.1f", x)).append(' ').append(y).append(" Td (")
         .append(esc(text)).append(") Tj ET\n");
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '\\' || ch == '(' || ch == ')') sb.append('\\').append(ch);
            else if (ch >= 32 && ch < 127) sb.append(ch);
            else sb.append('?');
        }
        return sb.toString();
    }
}
