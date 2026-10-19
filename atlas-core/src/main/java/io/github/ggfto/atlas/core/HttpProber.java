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
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    public ProbeResult probe(URI url) {
        HttpRequest req = HttpRequest.newBuilder(url).timeout(timeout).header("User-Agent", "cyberatlas-check").GET().build();
        try {
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return new ProbeResult(resp.statusCode(), resp.headers().firstValue("Location").orElse(null), null);
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
}
