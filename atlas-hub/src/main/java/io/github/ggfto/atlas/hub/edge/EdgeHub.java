package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.hub.AtlasConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/** Estado dos edges no hub: a CA/enrollment (se configurada; edges e tokens no Postgres) e o registro de sessoes. */
@ApplicationScoped
public class EdgeHub {

    private static final Logger LOG = Logger.getLogger(EdgeHub.class);

    private final Optional<EnrollmentService> enrollment;
    private final EdgeRegistry registry = new EdgeRegistry();
    private final AtlasConfig.Edges config;

    EdgeHub(AtlasConfig config, DataSource db) {
        this.config = config.edges();
        Optional<EnrollmentService> e = Optional.empty();
        if (this.config.caCert().isPresent() && this.config.caKey().isPresent()) {
            try {
                AtlasCa ca = AtlasCa.load(Files.readString(Path.of(this.config.caCert().get())),
                        Files.readString(Path.of(this.config.caKey().get())));
                e = Optional.of(new EnrollmentService(ca, db, Instant::now));
            } catch (IOException | java.security.GeneralSecurityException ex) {
                LOG.errorf("CA do atlas ilegivel: edges desligados (%s)", ex.getClass().getSimpleName());
            }
        }
        this.enrollment = e;
    }

    @Scheduled(every = "1h")
    void purgeExpiredTokens() {
        enrollment.ifPresent(e -> {
            try {
                e.purgeExpiredTokens();
            } catch (IllegalStateException ex) {
                LOG.warnf("limpeza de tokens de enrollment vencidos falhou: %s", ex.getMessage());
            }
        });
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
