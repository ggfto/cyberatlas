"""Teste offline: nunca usa credenciais reais."""
import contextlib
import io
import json
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
