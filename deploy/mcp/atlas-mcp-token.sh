#!/usr/bin/env bash
# Segredo lido apenas pelo processo Python, nunca expandido pelo shell.
set +x
set -eu
exec python3 - "$@" 3<&0 <<'PY'
import json
import os
import stat
import sys
import urllib.parse
import urllib.request


def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)


if not 2 <= len(sys.argv) <= 3:
    fail('Uso: atlas-mcp-token.sh USUARIO [ARQUIVO_0600]; sem arquivo, segredo no stdin')
try:
    if len(sys.argv) == 3:
        fd = os.open(sys.argv[2], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'r') as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.getuid():
                fail('Segredo exige arquivo regular próprio com modo 0600')
            secret = source.read(65537)
    else:
        with os.fdopen(3, 'r') as source:
            secret = source.read(65537)
    secret = secret.rstrip('\r\n')
    if not secret or len(secret) > 65536:
        fail('Segredo vazio ou grande demais')
    body = urllib.parse.urlencode({'grant_type': 'client_credentials', 'client_id': 'atlas',
                                  'username': sys.argv[1], 'password': secret, 'scope': 'profile'}).encode()
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    # Endpoint fixo HTTPS; não aceita redirect que encaminhe app password a outro host.
    opener = urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request('https://auth.gf2.in/application/o/token/', data=body,
                                    headers={'Content-Type': 'application/x-www-form-urlencoded'})
    with opener.open(request, timeout=30) as response:
        result = json.load(response)
    token = result.get('access_token')
    if not isinstance(token, str) or not token or any(c.isspace() for c in token):
        fail('Resposta sem access_token válido')
    print(token)
except Exception:
    # Não imprimir response/body/exceção: podem conter segredo ou token.
    fail('Falha ao obter token; confira conta, app password, grupo e conexão HTTPS')
PY
