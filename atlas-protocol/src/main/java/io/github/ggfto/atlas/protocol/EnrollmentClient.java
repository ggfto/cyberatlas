package io.github.ggfto.atlas.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;

/**
 * Enrollment do edge (design §1.1): gera o par de chaves aqui, manda o CSR com o token de uso unico e recebe o
 * certificado assinado pela CA do atlas. O token vai no corpo, nao na URL (URL acaba em log de acesso). So
 * confia na CA do atlas recebida na instalacao.
 */
public final class EnrollmentClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EnrollmentClient() {
    }

    public static EdgeCredentials enroll(URI hub, String token, String caPem, Duration timeout) throws IOException,
            GeneralSecurityException, InterruptedException {
        KeyPair kp = Csr.newKeyPair();
        String csr;
        try {
            csr = Csr.pem(kp, "atlas-edge");
        } catch (Exception e) {
            throw new GeneralSecurityException("CSR", e);
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, EdgeCredentials.trustOnly(caPem).getTrustManagers(), null);
        HttpClient http = HttpClient.newBuilder().sslContext(ctx).connectTimeout(timeout).build();
        String body = JSON.writeValueAsString(Map.of("token", token, "csr", csr));
        HttpRequest req = HttpRequest.newBuilder(hub.resolve("/api/enrollments")).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("enrollment recusado: HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode r = JSON.readTree(resp.body());
        return new EdgeCredentials(Pem.privateKey(kp.getPrivate()), r.path("certificatePem").asText(), r.path("caPem").asText());
    }
}
