package io.github.ggfto.atlas.protocol;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * O que o edge guarda depois do enrollment: a propria chave, o certificado emitido pelo hub e a CA do atlas.
 * O {@link SSLContext} apresenta o certificado (mTLS) e so confia na CA do atlas (nada do truststore do sistema).
 */
public record EdgeCredentials(String keyPem, String certPem, String caPem) {

    @Override
    public String toString() {
        return "EdgeCredentials[chave omitida]";
    }

    public SSLContext sslContext() throws GeneralSecurityException {
        try {
            PrivateKey key = Pem.readPrivateKey(keyPem);
            List<X509Certificate> chain = Pem.readCertificates(certPem);
            char[] pass = new char[0];
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, pass);
            ks.setKeyEntry("edge", key, pass, chain.toArray(new X509Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), trustOnly(caPem).getTrustManagers(), null);
            return ctx;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    /** TrustManager que confia so na(s) CA(s) do PEM. */
    public static TrustManagerFactory trustOnly(String caPem) throws GeneralSecurityException, java.io.IOException {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, new char[0]);
        int i = 0;
        for (X509Certificate ca : Pem.readCertificates(caPem)) {
            ts.setCertificateEntry("ca-" + i++, ca);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf;
    }
}
