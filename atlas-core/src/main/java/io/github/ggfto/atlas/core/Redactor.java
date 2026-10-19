package io.github.ggfto.atlas.core;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import java.util.List;

/**
 * Redacao de linha de log (design §4): defesa extra, nao substitui o papel que restringe quem ve log. Tudo em
 * RE2J, tempo linear no tamanho da linha.
 */
public final class Redactor {

    private record Rule(String name, Pattern pattern, boolean keepKey) {
    }

    public static final Redactor DEFAULT = new Redactor(List.of(
            new Rule("pem", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), false),
            new Rule("github", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"), false),
            new Rule("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), false),
            new Rule("bearer", Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}"), false),
            new Rule("apikey", Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"), false),
            new Rule("aws", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), false),
            new Rule("kv", Pattern.compile(
                    "(?i)((?:password|passwd|pwd|token|secret|api[_-]?key)[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"'&,;}]+)"), true)));

    private final List<Rule> rules;

    private Redactor(List<Rule> rules) {
        this.rules = rules;
    }

    public String redact(String line) {
        String out = line;
        for (Rule r : rules) {
            Matcher m = r.pattern().matcher(out);
            if (!m.find()) {
                continue;
            }
            out = r.keepKey()
                    ? m.replaceAll("$1[redacted:" + r.name() + "]")
                    : m.replaceAll("[redacted:" + r.name() + "]");
        }
        return out;
    }
}
