# MCP por bearer

Endpoint: `https://atlas.gf2.in/mcp`, Streamable HTTP. Sem cookie de navegador; ausência de bearer retorna 401, não redirecionamento. Token sem `atlas-viewers` ou `atlas-admins` retorna 403. As cinco tools são somente leitura: `get_topology`, `get_node`, `list_problems`, `get_history`, `list_sources`.

`list_problems` lista OFFLINE, DEGRADED ou drift. UNKNOWN aparece apenas como contador, não como problema por si só.

## Conta de serviço

Administrador deve criar conta dedicada não superuser, somente membro de `atlas-viewers`, e app password com finalidade `app_password`. Não reutilizar senha humana, conta admin nem client secret do provider. Nenhuma credencial é criada pelo script.

Blueprint sugerido (aplicar pelo administrador; app password fica fora do Git):

```yaml
version: 1
metadata:
  name: atlas-mcp-service-account
entries:
  - model: authentik_core.user
    identifiers:
      username: svc-atlas-mcp
    attrs:
      name: Atlas MCP somente leitura
      type: service_account
      is_active: true
      groups:
        - !Find [authentik_core.group, [name, atlas-viewers]]
```

## Obter token

Requer Bash e Python 3, HTTPS com certificado válido. Endpoint Authentik fixo; redirects recusados. Fornecer app password por arquivo regular próprio modo 0600 ou stdin. Nunca colocar segredo em argumento, variável de ambiente, histórico ou log. Não executar com tracing externo (`bash -x`, ferramentas de gravação de terminal).

```sh
./deploy/mcp/atlas-mcp-token.sh svc-atlas-mcp /caminho/privado/app-password
# Alternativa: gerenciador de segredos escreve diretamente no stdin do script.
```

Stdout contém somente access token; direcioná-lo para armazenamento privado do cliente MCP, nunca log compartilhado. Stderr contém erro genérico sem corpo da resposta. Não salvar token no repositório. Cliente deve enviar `Authorization: Bearer <token>` e renovar via mesmo script antes da expiração; não há refresh token nesse grant.

## Registrar no Claude Code

Caminho recomendado: `headersHelper` gera headers JSON a cada conexão/reconexão, sem persistir bearer na configuração. Suporte HTTP e contrato documentados em https://code.claude.com/docs/en/mcp#use-dynamic-headers-for-custom-authentication. O helper tem limite de 10 segundos; conexão lenta pode expirar antes do timeout de 30 segundos do script de token.

Crie fora do repositório um helper privado executável (modo 0700), com caminhos absolutos ajustados à instalação:

```sh
#!/usr/bin/env bash
set +x
set -eu
TOKEN="$(/caminho/absoluto/atlas-mcp-token.sh svc-atlas-mcp /caminho/privado/app-password)"
printf '%s' "$TOKEN" | python3 -c 'import json,sys; print(json.dumps({"Authorization": "Bearer " + sys.stdin.read()}))'
unset TOKEN
```

O app password deve continuar em arquivo regular próprio modo 0600. O bearer passa por stdin ao serializador, não por argv. Não execute o helper manualmente em terminal gravado: stdout contém credencial.

Na configuração MCP privada do Claude Code, adicione:

```json
{
  "mcpServers": {
    "atlas": {
      "type": "http",
      "url": "https://atlas.gf2.in/mcp",
      "headersHelper": "/caminho/privado/atlas-mcp-headers.sh"
    }
  }
}
```

Proteja configuração e helper contra alterações por outros usuários. Para configuração local/de projeto, aceite confiança apenas após revisar o comando; `headersHelper` executa shell. O cliente executa novamente o helper por conexão, sem cache, e tenta renovar uma vez após 401/403 de tool call. Solicite `list_problems`.

### Alternativa: header estático

Para clientes sem `headersHelper`, em terminal privado, sem tracing, carregar token apenas em variável de shell não exportada:

```sh
TOKEN="$(./deploy/mcp/atlas-mcp-token.sh svc-atlas-mcp /caminho/privado/app-password)"
claude mcp add --transport http atlas https://atlas.gf2.in/mcp --header "Authorization: Bearer $TOKEN"
unset TOKEN
```

Atenção: o comando de registro passa o header por argv e o cliente pode persistir o token na configuração. Execute somente em máquina confiável, proteja o arquivo de configuração e não compartilhe sua saída. O app password nunca entra nesse comando. Quando expirar, remova o registro com `claude mcp remove atlas` e repita com token novo. Solicite ao cliente chamar `list_problems`.

Tools: `get_topology` retorna nós e arestas sanitizados; `get_node(key)` retorna nó, estado, fontes e drift; `list_problems` retorna problemas e contadores; `get_history(key)` retorna mudanças; `list_sources` retorna fontes. Nenhuma retorna logs ou oferece escrita.

## Diagnóstico Authentik 2025.2.4

Fontes upstream na tag `version/2025.2.4`: `authentik/providers/oauth2/models.py` declara `access_token_validity=hours=1` e `include_claims_in_id_token=True`; `blueprints/system/providers-oauth2.yaml` define mapping `profile` com nomes dos grupos. `views/token.py` aceita `client_credentials` com username e app password; `id_token.py` monta audience pelo client_id, aplica mappings e preserva claims no access token.

Blueprint infra `vps/authentik/blueprints/80-atlas-oidc.yaml` em `origin/stable` usa client_id `atlas`, mapping profile e não sobrescreve esses defaults. Isso demonstra configuração declarada, não estado runtime: conferir `expires_in` e claims em ambiente administrativo sem publicar token. TTL esperado é 3600 segundos. O script pede `scope=profile`, não precisa client secret e não solicita offline_access.

Limitação herdada: respostas 401/403 da camada HTTP podem incluir nome da classe da exceção. Não foi instalado failure handler global: isso alteraria também autenticação REST, OIDC e break-glass fora desta fatia. Tratar sanitização desses erros em mudança própria com testes dos três fluxos; não confundir com vazamento de app password pelo script.

401: conferir validade, issuer/audience, assinatura e horário. 403: conferir grupo da conta e binding da aplicação. Não publicar respostas de token nem nomes de exceções. Testes locais usam OIDC simulado; não provam emissão real em produção.

## Validação

```sh
python3 -m unittest discover -s deploy/mcp -p 'test_*.py'
mvn -pl atlas-hub -am test -Dtest=McpTest,SecurityTest -Dsurefire.failIfNoSpecifiedTests=false -Dquarkus.quinoa.enable=false
```

Suíte completa fica para CI. Nenhuma credencial real necessária nos testes.
