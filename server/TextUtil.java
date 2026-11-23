package server;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilitas pembersih teks: decode HTML entity dari OpenTDB dalam SATU pass (anti double-decode). */
public final class TextUtil {
    private TextUtil() {}

    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9A-Fa-f]+|#[0-9]+|[A-Za-z0-9]+);");
    private static final Map<String, String> NAMED = new HashMap<>();
    static {
        NAMED.put("quot", "\""); NAMED.put("amp", "&"); NAMED.put("lt", "<"); NAMED.put("gt", ">");
        NAMED.put("apos", "'"); NAMED.put("nbsp", " "); NAMED.put("ldquo", "\""); NAMED.put("rdquo", "\"");
        NAMED.put("lsquo", "'"); NAMED.put("rsquo", "'"); NAMED.put("hellip", "..."); NAMED.put("ndash", "-");
        NAMED.put("mdash", "-"); NAMED.put("eacute", "\u00e9"); NAMED.put("egrave", "\u00e8");
        NAMED.put("aacute", "\u00e1"); NAMED.put("oacute", "\u00f3"); NAMED.put("iacute", "\u00ed");
        NAMED.put("uacute", "\u00fa"); NAMED.put("ntilde", "\u00f1"); NAMED.put("uuml", "\u00fc");
        NAMED.put("ouml", "\u00f6"); NAMED.put("auml", "\u00e4"); NAMED.put("ccedil", "\u00e7");
        NAMED.put("deg", "\u00b0");
    }

    public static String decode(String s) {
        if (s == null) return "";
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group(1);
            String rep = m.group();
            try {
                if (g.startsWith("#x") || g.startsWith("#X")) {
                    rep = new String(Character.toChars(Integer.parseInt(g.substring(2), 16)));
                } else if (g.startsWith("#")) {
                    rep = new String(Character.toChars(Integer.parseInt(g.substring(1))));
                } else if (NAMED.containsKey(g)) {
                    rep = NAMED.get(g);
                }
            } catch (IllegalArgumentException ignored) { /* biarkan entity apa adanya */ }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
