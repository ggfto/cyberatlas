# Spike: login local de break-glass restrito por porta (F1)

**Pergunta** (design §4): uma `HttpSecurityPolicy` do Quarkus consegue decidir pela **porta local** em que a
requisição chegou? Se consegue, o mesmo processo do hub atende duas coisas:
- a **porta 8080** (pública, atrás do Traefik), que só aceita OIDC;
- a **porta 8443**, que só escuta no WG e no cluster e aceita o login local de break-glass e o WS mTLS dos edges.

**Resposta: sim.** Teste em `atlas-hub/src/test/java/io/github/ggfto/atlas/hub/spike/`.

## O que o teste prova
- `BreakGlassPortPolicy` lê `RoutingContext.request().localAddress().port()` e devolve `PERMIT` só quando essa
  porta é a de break-glass.
- Com HTTP (8081) e HTTPS (8444) ligados no mesmo app (`quarkus.http.insecure-requests=enabled`, certificado
  gerado na hora pelo `smallrye-certificate-generator`, nenhuma chave no repo), a **mesma rota** responde:
  - **403** pela porta HTTP;
  - **200** pela porta HTTPS.
- Rotas sem a permissão (`/q/health`) seguem normais nas duas portas.

## Achados para a F5
- **Sem a extensão `quarkus-security` as permissões HTTP não são aplicadas, em silêncio.** A primeira rodada
  do spike deu 200 nas duas portas. Na F5 o `quarkus-security` (via `quarkus-oidc`) entra em compile, e um
  teste tem de garantir que a permissão está ativa (nunca confiar só na config).
- **O prefixo `atlas.*` é raiz do `@ConfigMapping`** e rejeita chave desconhecida, o que é bom: erro de digitação
  na config falha no boot.
- **A porta vista é a do container.** No k8s o NodePort do WG mapeia para a `containerPort` 8443, então a policy
  compara com 8443 de qualquer jeito. Não depende do IP de origem, que o NAT do kube-proxy pode trocar.
- **TLS no 8443 é obrigatório de qualquer forma** (WS mTLS dos edges). O login local vai por cima desse TLS, nunca
  em HTTP puro.

## Plano B (não necessário)
Seria um segundo processo/Service só para break-glass. Descartado: o spike mostra que um processo basta.
