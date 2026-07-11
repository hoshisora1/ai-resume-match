import http from 'node:http'

const host = '0.0.0.0'
const port = Number.parseInt(process.env.PORT ?? '18089', 10)

const analysisContent = [
  '匹配分数: 91',
  '',
  '## 核心结论',
  '候选人的 Java、Spring Boot、Redis 与 RabbitMQ 经验匹配岗位要求。',
].join('\n')

function sendJson(response, statusCode, body) {
  response.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  })
  response.end(JSON.stringify(body))
}

const server = http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    sendJson(response, 200, { status: 'ok' })
    return
  }

  if (request.method === 'POST' && request.url === '/v1/chat/completions') {
    // Consume and discard the request without retaining or logging prompt data.
    request.resume()
    request.on('end', () => {
      sendJson(response, 200, {
        choices: [{ message: { content: analysisContent } }],
      })
    })
    return
  }

  request.resume()
  sendJson(response, 404, { error: 'not_found' })
})

server.listen(port, host, () => {
  console.log(`mock-ai listening on ${host}:${port}`)
})

function shutdown() {
  server.close((error) => {
    process.exitCode = error ? 1 : 0
  })
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
