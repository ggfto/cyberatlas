#!/usr/bin/env bash
# Regressao do -march amd64: o binario nativo tem que subir em CPU x86-64-v2 SEM AVX
# (ex.: Celeron N5095/Jasper Lake). Roda cada binario sob qemu-user emulando Nehalem
# (SSE4.2+POPCNT, sem AVX/AVX2/BMI/FMA) e exige, para cada um:
#   1. NAO aparecer a recusa de CPU do GraalVM ("does not support all of the following CPU features");
#   2. o codigo Java ter rodado (log "[io.quarkus" no stdout/stderr): a checagem de CPU e anterior a ele,
#      entao isso prova que o binario passou dela. Subir de verdade nao e exigido: o hub sem PKI/banco
#      falha na partida (saida 1), o que vale; o edge fica de pe ate o timeout (124), o que tambem vale;
#   3. o processo nao ter morrido por sinal (SIGILL de instrucao fora do x86-64-v2, SIGSEGV do qemu)
#      nem o timeout/qemu ter falhado: codigo de saida >= 125 e falha.
# Precisa de qemu-x86_64 >= 10 (o 8.2 do ubuntu 24.04 da SIGSEGV interno com binario GraalVM).
# Uso: cpu-sem-avx.sh <binario>...   CPU=<modelo qemu> (default Nehalem), ESPERA=<segundos> (default 45)
set -uo pipefail

cpu="${CPU:-Nehalem}"
espera="${ESPERA:-45}"
recusa='does not support all of the following CPU features'

if [ "$#" -eq 0 ]; then echo "uso: $0 <binario>..." >&2; exit 2; fi
command -v qemu-x86_64 >/dev/null || { echo "::error::qemu-x86_64 ausente" >&2; exit 2; }
qemu-x86_64 --version | head -1

falhas=0
for bin in "$@"; do
  if [ ! -x "$bin" ]; then echo "::error::$bin: binario ausente ou nao executavel"; falhas=$((falhas + 1)); continue; fi
  echo "== $bin sob qemu-x86_64 -cpu $cpu (ate ${espera}s)"
  saida=$(timeout "$espera" qemu-x86_64 -cpu "$cpu" "$bin" 2>&1 </dev/null)
  rc=$?
  printf '%s\n' "$saida" | head -n 30
  echo "-- saida=$rc"
  if grep -qF "$recusa" <<<"$saida"; then
    echo "::error::$bin: recusado em CPU sem AVX (-cpu $cpu); -march amd64 acima de x86-64-v2?"
    falhas=$((falhas + 1))
  elif [ "$rc" -ge 125 ]; then
    echo "::error::$bin: morreu com codigo $rc sob -cpu $cpu (sinal/instrucao ilegal/qemu)"
    falhas=$((falhas + 1))
  elif ! grep -qF '[io.quarkus' <<<"$saida"; then
    echo "::error::$bin: nenhum log do Quarkus sob -cpu $cpu; o codigo Java nao chegou a rodar"
    falhas=$((falhas + 1))
  else
    echo "OK: $bin passou da checagem de CPU e rodou o Quarkus sob -cpu $cpu"
  fi
done

[ "$falhas" -eq 0 ] || { echo "$falhas binario(s) falharam em CPU sem AVX"; exit 1; }
