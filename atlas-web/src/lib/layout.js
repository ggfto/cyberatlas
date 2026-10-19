// Layout manual (design §3.2): a posicao que o operador arrastou vence a automatica, no por no. Gravar e um PATCH por
// no com If-Match da rev; no 409 a posicao vence o ultimo, entao reenvia uma vez com a rev atual.

/** Aplica as posicoes manuais ({ key: {x, y, rev} }) por cima do layout automatico; caixas de maquina nao mudam. */
export function applyLayout(nodes, manual) {
  return nodes.map((n) => {
    if (n.type === 'machineGroup') return n
    const m = manual[n.id]
    return { ...n, position: m ? { x: m.x, y: m.y } : n.position, data: { ...n.data, rev: m ? m.rev : 0 } }
  })
}

async function patch(key, pos, rev, fetchImpl) {
  return fetchImpl(`/api/layout/nodes/${key}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'If-Match': `"${rev}"` },
    body: JSON.stringify({ x: pos.x, y: pos.y }),
  })
}

export async function saveMove(key, pos, rev, fetchImpl = fetch) {
  let r = await patch(key, pos, rev, fetchImpl)
  if (r.status === 409) {
    const { current } = await r.json()
    r = await patch(key, pos, current ? current.rev : 0, fetchImpl)
    if (r.status === 409) throw new Error('conflito ao salvar a posicao')
  }
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}
