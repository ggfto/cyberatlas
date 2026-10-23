package io.github.ggfto.atlas.hub;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// O Quinoa vem desligado em teste; este perfil liga e roda o `npm test` (Vitest) do atlas-web no build.
@QuarkusTest
@TestProfile(QuinoaTestProfiles.EnableAndRunTests.class)
class HubHealthTest {

    @Test
    void healthIsUp() {
        given().when().get("/q/health").then().statusCode(200).body("status", is("UP"));
    }

    @Test
    void servesTheWebBundle() {
        given().auth().oauth2(Tokens.viewer()).when().get("/").then().statusCode(200)
                .body(containsString("<div id=\"root\"></div>"));
    }

    /**
     * A CSP sai do hub como header HTTP (issue #18): em {@code <meta>} o browser ignora frame-ancestors, e sem isto
     * so o X-Frame-Options do Traefik impedia enquadrar o atlas.
     */
    @Test
    void htmlCarriesTheCspAsARealHeader() {
        given().auth().oauth2(Tokens.viewer()).when().get("/").then().statusCode(200)
                .contentType(containsString("text/html"))
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'"))
                .header("Content-Security-Policy", containsString("default-src 'self'"))
                // img-src 'self' data: e condicao da exportacao do mapa (issue #25): sem data: nao da pra
                // acionar download via <a href="data:...">, e sem recusar o resto o botao vazaria conteudo externo.
                .header("Content-Security-Policy", containsString("img-src 'self' data:"))
                .header("X-Content-Type-Options", is("nosniff"));
    }

    /**
     * Issue #19: so o /embed (so leitura) e enquadravel, e so pelo Home Assistant, origem exata https. Sem
     * X-Frame-Options nenhum (DENY anularia o frame-ancestors; ALLOW-FROM e obsoleto).
     */
    @ParameterizedTest
    @ValueSource(strings = {"/embed", "/embed/", "/embed?focus=service:vps/x&theme=light"})
    void embedIsFrameableOnlyByHomeAssistant(String path) {
        given().auth().oauth2(Tokens.viewer()).urlEncodingEnabled(false).when().get(path).then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("<div id=\"root\"></div>"))
                .header("Content-Security-Policy", containsString("frame-ancestors https://ha.gf2.in"))
                .header("Content-Security-Policy", not(containsString("frame-ancestors 'none'")))
                .header("Content-Security-Policy", containsString("default-src 'self'"))
                .header("X-Frame-Options", nullValue());
    }

    /**
     * Qualquer outra rota HTML, inclusive as parecidas com /embed, continua sem moldura nenhuma. ({@code //embed} fica
     * de fora: chega aqui como /embed, como chega pelo Traefik, que normaliza o path; por isso o atlas-web monta o
     * Embed, e nunca o App, sempre que estiver enquadrado.)
     */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/embedx", "/embed/x", "/x/embed", "/qualquer"})
    void everyOtherRouteStaysUnframeable(String path) {
        given().auth().oauth2(Tokens.viewer()).urlEncodingEnabled(false).when().get(path).then().statusCode(200)
                .contentType(containsString("text/html"))
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'"))
                .header("Content-Security-Policy", not(containsString("ha.gf2.in")));
    }
}
