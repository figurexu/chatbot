// 端到端 API 冒烟测试（Node 22+ 自带 fetch）
const BASE = 'http://127.0.0.1:8113'

async function main() {
  // 1. healthz
  const h = await (await fetch(BASE + '/healthz')).json()
  console.log('[1] healthz:', JSON.stringify(h))

  // 2. characters list
  const chars = await (await fetch(BASE + '/api/characters')).json()
  console.log('[2] characters count:', chars.length)
  for (const c of chars) console.log('    -', c.id, c.name, c.dynasty)

  // 3. character detail (张居正)
  const zjz = await (await fetch(BASE + '/api/characters/zhangjuzheng')).json()
  console.log('[3] zhangjuzheng greeting:', zjz.greeting.slice(0, 40) + '…')

  // 4. chat (mock llm, 张居正, multi-turn)
  const sessionId = 'smoke_' + Date.now()
  const r1 = await (await fetch(BASE + '/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characterId: 'zhangjuzheng', message: '先生，何为一条鞭法？', sessionId })
  })).json()
  console.log('[4] chat#1 reply:', r1.reply.slice(0, 50) + '…')

  const r2 = await (await fetch(BASE + '/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characterId: 'zhangjuzheng', message: '若考成法施行受阻，当如何？', sessionId })
  })).json()
  console.log('[4] chat#2 reply:', r2.reply.slice(0, 50) + '…')

  // 5. history persisted
  const hist = await (await fetch(BASE + '/api/sessions/' + sessionId + '/messages')).json()
  console.log('[5] history count:', hist.length, '| roles:', hist.map(m => m.role).join(','))

  // 6. another character (李白)
  const r3 = await (await fetch(BASE + '/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characterId: 'libai', message: '太白先生，可否为我赋诗一首？' })
  })).json()
  console.log('[6] libai reply:', r3.reply.slice(0, 50) + '…', '| session:', r3.sessionId)

  // 7. validation: empty message -> 400
  const bad = await fetch(BASE + '/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ characterId: 'libai', message: '' })
  })
  console.log('[7] empty message status:', bad.status)

  // 8. clear session
  const del = await fetch(BASE + '/api/sessions/' + sessionId, { method: 'DELETE' })
  const histAfter = await (await fetch(BASE + '/api/sessions/' + sessionId + '/messages')).json()
  console.log('[8] delete status:', del.status, '| history after clear:', histAfter.length)

  console.log('ALL TESTS PASSED')
}
main().catch(e => { console.error('TEST FAILED:', e.message); process.exit(1) })
