package io.github.ggfto.atlas.protocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** PEM sem dependencia de provider: chave privada EC em PKCS#8 e certificados X.509. */
public final class Pem {

    private Pem() {
    }

    public static String privateKey(PrivateKey key) {
        return block("PRIVATE KEY", key.getEncoded());
    }

    public static String certificate(X509Certificate cert) throws GeneralSecurityException {
        return block("CERTIFICATE", cert.getEncoded());
    }

    public static PrivateKey readPrivateKey(String pem) throws GeneralSecurityException {
        byte[] der = body(pem, "PRIVATE KEY");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static List<X509Certificate> readCertificates(String pem) throws GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> out = new ArrayList<>();
        for (var c : cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
            out.add((X509Certificate) c);
        }
        if (out.isEmpty()) {
            throw new GeneralSecurityException("nenhum certificado no PEM");
        }
        return out;
    }

    /** Grava a chave so para o dono (0600), sem janela em que o arquivo exista com permissao larga. */
    public static void writePrivateKey(Path file, PrivateKey key) throws IOException {
        Files.deleteIfExists(file);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, privateKey(key), StandardOpenOption.TRUNCATE_EXISTING);
    }

    static String block(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    private static byte[] body(String pem, String type) throws GeneralSecurityException {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int a = pem.indexOf(begin);
        int b = pem.indexOf(end);
        if (a < 0 || b < a) {
            throw new GeneralSecurityException("PEM sem bloco " + type);
        }
        return Base64.getMimeDecoder().decode(pem.substring(a + begin.length(), b));
    }
}
