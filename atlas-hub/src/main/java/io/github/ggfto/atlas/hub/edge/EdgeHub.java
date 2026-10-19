package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.hub.AtlasConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/** Estado dos edges no hub: a CA/enrollment (se configurada) e o registro de sessoes. */
@ApplicationScoped
public class EdgeHub {

    private static final Logger LOG = Logger.getLogger(EdgeHub.class);

    private final Optional<EnrollmentService> enrollment;
    private final EdgeRegistry registry = new EdgeRegistry();
    private final AtlasConfig.Edges config;

    EdgeHub(AtlasConfig config) {
        this.config = config.edges();
        Optional<EnrollmentService> e = Optional.empty();
        if (this.config.caCert().isPresent() && this.config.caKey().isPresent()) {
            try {
                AtlasCa ca = AtlasCa.load(Files.readString(Path.of(this.config.caCert().get())),
                        Files.readString(Path.of(this.config.caKey().get())));
                e = Optional.of(new EnrollmentService(ca, Instant::now));
            } catch (IOException | java.security.GeneralSecurityException ex) {
                LOG.errorf("CA do atlas ilegivel: edges desligados (%s)", ex.getClass().getSimpleName());
            }
        }
        this.enrollment = e;
    }

    public Optional<EnrollmentService> enrollment() {
        return enrollment;
    }

    public EdgeRegistry registry() {
        return registry;
    }

    public AtlasConfig.Edges config() {
        return config;
    }
}
