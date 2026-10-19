package io.github.ggfto.atlas.hub.auth;

import io.github.ggfto.atlas.hub.edge.EdgePkiProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

/** Hub com a porta TLS de edge/break-glass (8444 em teste) e um usuario local cujo hash e gerado na hora. */
public class BreakGlassProfile implements QuarkusTestProfile {

    static final String USER = "socorro";
    static final String PASSWORD = "senha-so-de-teste";

    @Override
    public Map<String, String> getConfigOverrides() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        Map<String, String> c = new HashMap<>(new EdgePkiProfile().getConfigOverrides());
        c.put("atlas.breakglass.user", USER);
        c.put("atlas.breakglass.bcrypt", OpenBSDBCrypt.generate(PASSWORD.toCharArray(), salt, 4));
        return c;
    }
}
