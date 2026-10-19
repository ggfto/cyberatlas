package io.github.ggfto.atlas.core;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/** GET simples, sem cookie e sem seguir redirect (o redirect e o que a expectativa julga). Usado pelo hub e pelo edge. */
public final class HttpProber {

    private final HttpClient client;
    private final Duration timeout;

    public HttpProber(Duration timeout) {
        this(timeout, null);
    }

    /** {@code ssl} nulo = truststore padrao do JDK (rotas publicas e *.local com certificado de CA publica). */
    public HttpProber(Duration timeout, javax.net.ssl.SSLContext ssl) {
        this.timeout = timeout;
        HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout);
        if (ssl != null) {
            b.sslContext(ssl);
        }
        this.client = b.build();
    }

    public ProbeResult probe(URI url) {
        HttpRequest req = HttpRequest.newBuilder(url).timeout(timeout).header("User-Agent", "cyberatlas-check").GET().build();
        try {
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return new ProbeResult(resp.statusCode(), resp.headers().firstValue("Location").orElse(null), null, certDaysLeft(resp));
        } catch (HttpTimeoutException e) {
            return new ProbeResult(null, null, "timeout");
        } catch (ConnectException e) {
            return new ProbeResult(null, null, "conexao recusada");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(null, null, "interrompido");
        } catch (Exception e) {
            return new ProbeResult(null, null, e.getClass().getSimpleName());
        }
    }

    private static Integer certDaysLeft(HttpResponse<?> resp) {
        return resp.sslSession().map(session -> {
            try {
                var cert = (java.security.cert.X509Certificate) session.getPeerCertificates()[0];
                return (int) Duration.between(java.time.Instant.now(), cert.getNotAfter().toInstant()).toDays();
            } catch (javax.net.ssl.SSLPeerUnverifiedException | ClassCastException e) {
                return null;
            }
        }).orElse(null);
    }
}
