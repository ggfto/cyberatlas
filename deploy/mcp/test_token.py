"""Teste offline: nunca usa credenciais reais."""
import contextlib
import io
import json
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name('atlas-mcp-token.sh')

class TokenTest(unittest.TestCase):
    def test_success_form_and_stdout(self):
        # Executa payload Python com transporte simulado, sem endpoint real.
        payload = SCRIPT.read_text().split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
        with tempfile.NamedTemporaryFile(mode='w') as secret:
            secret.write('fake-password\n')
            secret.flush()
            output = io.StringIO()
            response = io.StringIO(json.dumps({'access_token': 'fake-token'}))
            with patch('sys.argv', ['-', 'svc-atlas-mcp', secret.name]), \
                 patch('urllib.request.OpenerDirector.open', return_value=response) as transport, \
                 contextlib.redirect_stdout(output):
                exec(compile(payload, str(SCRIPT), 'exec'), {})
            request = transport.call_args.args[0]
            from urllib.parse import parse_qs
            form = parse_qs(request.data.decode())
            self.assertEqual(form['password'], ['fake-password'])
            self.assertEqual(form['scope'], ['profile'])
            self.assertEqual(form['grant_type'], ['client_credentials'])
            self.assertEqual(form['client_id'], ['atlas'])
            self.assertNotIn('client_secret', form)
            self.assertEqual(output.getvalue(), 'fake-token\n')

    def test_transport_error_does_not_leak(self):
        payload = SCRIPT.read_text().split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
        with tempfile.NamedTemporaryFile(mode='w') as secret:
            secret.write('fake-password')
            secret.flush()
            output, errors = io.StringIO(), io.StringIO()
            with patch('sys.argv', ['-', 'svc-atlas-mcp', secret.name]), \
                 patch('urllib.request.OpenerDirector.open', side_effect=RuntimeError('fake-password')), \
                 contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors), \
                 self.assertRaises(SystemExit):
                exec(compile(payload, str(SCRIPT), 'exec'), {})
            self.assertEqual(output.getvalue(), '')
            self.assertNotIn('fake-password', errors.getvalue())

    def test_redirect_307_never_reaches_second_server(self):
        payload = SCRIPT.read_text().split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
        received = []

        class Destination(BaseHTTPRequestHandler):
            def do_GET(self):
                received.append(self.path)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"access_token":"redirected-token"}')

            def log_message(self, *args):
                pass

        with contextlib.ExitStack() as stack:
            destination = ThreadingHTTPServer(('127.0.0.1', 0), Destination)
            stack.callback(destination.server_close)

            class Redirect(Destination):
                def do_GET(self):
                    self.send_response(307)
                    self.send_header('Location', f'http://127.0.0.1:{destination.server_port}/stolen')
                    self.end_headers()

            origin = ThreadingHTTPServer(('127.0.0.1', 0), Redirect)
            stack.callback(origin.server_close)
            for server in (destination, origin):
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                stack.callback(thread.join)
                stack.callback(server.shutdown)
            secret = stack.enter_context(tempfile.NamedTemporaryFile(mode='w'))
            secret.write('fake-password')
            secret.flush()
            real_request = urllib.request.Request

            def local_request(url, *args, **kwargs):
                # Só a URL fixa do Authentik vira servidor local; Request criado
                # pelo urllib ao seguir redirect precisa passar intacto.
                if url != 'https://auth.gf2.in/application/o/token/':
                    return real_request(url, *args, **kwargs)
                # urllib já recusa POST+307 em super(). GET isola nossa NoRedirect
                # em vez de depender dessa salvaguarda.
                return real_request(f'http://127.0.0.1:{origin.server_port}/token',
                                    *args, method='GET', **kwargs)

            output, errors = io.StringIO(), io.StringIO()
            exit_code = 0
            with patch('sys.argv', ['-', 'svc-atlas-mcp', secret.name]), \
                 patch('urllib.request.Request', side_effect=local_request), \
                 contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                try:
                    exec(compile(payload, str(SCRIPT), 'exec'), {})
                except SystemExit as error:
                    exit_code = error.code
            self.assertEqual(received, [], 'redirect destination must receive ZERO requests')
            self.assertNotEqual(exit_code, 0)
            self.assertEqual(output.getvalue(), '')
            self.assertIn('Falha ao obter token', errors.getvalue())
            self.assertNotIn('fake-password', errors.getvalue())

    def test_insecure_file_rejected(self):
        with tempfile.NamedTemporaryFile() as f:
            pathlib.Path(f.name).chmod(0o644)
            result = subprocess.run(['bash', str(SCRIPT), 'svc-atlas-mcp', f.name], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, b'')
            self.assertIn(b'0600', result.stderr)

    def test_empty_stdin_rejected(self):
        result = subprocess.run(['bash', str(SCRIPT), 'svc-atlas-mcp'], input=b'', capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b'')
        self.assertIn(b'vazio', result.stderr)

if __name__ == '__main__':
    unittest.main()
