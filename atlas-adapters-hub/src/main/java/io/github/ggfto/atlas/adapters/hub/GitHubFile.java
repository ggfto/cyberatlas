package io.github.ggfto.atlas.adapters.hub;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Um arquivo de um repo do GitHub pela API de contents (design §1.3: o services.yml do ggfto/infra com um token
 * fine-grained so {@code contents:read}). Cada leitura manda o ETag da anterior: 304 devolve o que ja tinha e nao
 * gasta rate limit. Rate limit (403/429 com reset ou retry-after) bloqueia ate o reset, sem chamar de novo.
 * Erro e exception com motivo (vira o erro visivel da fonte no hub); o token so vai no header Authorization e
 * nunca aparece em URL, mensagem ou toString.
 */
public final class GitHubFile implements Supplier<String> {

    static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*");

    private final URI url;
    private final String shown;
    private final Optional<String> token;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient http;
    private String etag;
    private String cached;
    private Instant blockedUntil;

    public GitHubFile(URI api, String repo, String path, String ref, Optional<String> token, Duration timeout, Clock clock) {
        if (repo == null || !REPO.matcher(repo).matches()) {
            throw new IllegalArgumentException("repo invalido (dono/nome): " + repo);
        }
        if (path == null || !PATH.matcher(path).matches() || path.contains("..")) {
            throw new IllegalArgumentException("caminho invalido no repo: " + path);
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref obrigatoria (branch, tag ou commit)");
        }
        String base = api.toString().replaceAll("/+$", "");
        this.url = URI.create(base + "/repos/" + repo + "/contents/" + path + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8));
        this.shown = repo + "/" + path + "@" + ref;
        this.token = token.filter(t -> !t.isBlank());
        this.timeout = timeout;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public synchronized String get() {
        Instant now = clock.instant();
        if (blockedUntil != null && now.isBefore(blockedUntil)) {
            throw fail("rate limit do GitHub ate " + blockedUntil);
        }
        blockedUntil = null;
        HttpRequest.Builder req = HttpRequest.newBuilder(url).timeout(timeout).GET()
                .header("Accept", "application/vnd.github.raw+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "cyberatlas-hub");
        token.ifPresent(t -> req.header("Authorization", "Bearer " + t));
        if (etag != null) {
            req.header("If-None-Match", etag);
        }
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw fail("timeout");
        } catch (IOException e) {
            throw fail("falha de rede (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail("interrompido");
        }
        int status = resp.statusCode();
        switch (status) {
            case 200 -> {
                if (resp.body().length > MAX_BYTES) {
                    throw fail("arquivo maior que " + MAX_BYTES + " bytes");
                }
                cached = new String(resp.body(), StandardCharsets.UTF_8);
                etag = resp.headers().firstValue("ETag").orElse(null);
                return cached;
            }
            case 304 -> {
                if (cached == null) {
                    throw fail("HTTP 304 sem leitura anterior");
                }
                return cached;
            }
            case 401 -> throw fail("HTTP 401: token recusado (expirou ou foi revogado?)");
            case 404 -> throw fail("HTTP 404: nao encontrado (repo privado sem acesso do token, ref ou caminho errado)");
            default -> {
                Optional<Instant> until = rateLimitedUntil(resp, now);
                if (until.isPresent()) {
                    blockedUntil = until.get();
                    throw fail("rate limit do GitHub ate " + blockedUntil + " (HTTP " + status + ")");
                }
                throw fail("HTTP " + status);
            }
        }
    }

    /** Primario: 403/429 com x-ratelimit-remaining 0 e o reset; secundario: retry-after em segundos. */
    private static Optional<Instant> rateLimitedUntil(HttpResponse<?> resp, Instant now) {
        if (resp.statusCode() != 403 && resp.statusCode() != 429) {
            return Optional.empty();
        }
        Optional<String> retryAfter = resp.headers().firstValue("retry-after");
        if (retryAfter.isPresent() && retryAfter.get().matches("[0-9]{1,6}")) {
            return Optional.of(now.plusSeconds(Long.parseLong(retryAfter.get())));
        }
        boolean exhausted = resp.headers().firstValue("x-ratelimit-remaining").map("0"::equals).orElse(false);
        Optional<String> reset = resp.headers().firstValue("x-ratelimit-reset").filter(r -> r.matches("[0-9]{1,12}"));
        if (exhausted) {
            return Optional.of(reset.map(r -> Instant.ofEpochSecond(Long.parseLong(r))).orElse(now.plusSeconds(60)));
        }
        return resp.statusCode() == 429 ? Optional.of(now.plusSeconds(60)) : Optional.empty();
    }

    private UncheckedIOException fail(String why) {
        return new UncheckedIOException(new IOException("github " + shown + ": " + why));
    }

    @Override
    public String toString() {
        return "GitHubFile[" + shown + (token.isPresent() ? ", com token" : ", sem token") + "]";
    }
}
