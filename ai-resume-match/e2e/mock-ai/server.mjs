import http from 'node:http'

import { nextToolCall } from './protocol.mjs'

const host = '0.0.0.0'
const port = Number.parseInt(process.env.PORT ?? '18089', 10)

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
    const chunks = []
    request.on('data', (chunk) => chunks.push(chunk))
    request.on('end', () => {
      try {
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
        const messages = Array.isArray(body.messages) ? body.messages : []
        const toolMessages = messages.filter((message) => message.role === 'tool')
        const { name, args } = nextToolCall(messages)

        sendJson(response, 200, {
          choices: [
            {
              message: {
                content: null,
                tool_calls: [
                  {
                    id: `e2e-call-${toolMessages.length + 1}`,
                    type: 'function',
                    function: { name, arguments: JSON.stringify(args) },
                  },
                ],
              },
            },
          ],
          usage: {
            prompt_tokens: 120,
            completion_tokens: 40,
            total_tokens: 160,
          },
        })
      } catch {
        sendJson(response, 400, { error: 'invalid_request' })
      }
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
