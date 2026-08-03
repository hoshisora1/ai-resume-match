import http from 'node:http'

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
        let name
        let args

        if (toolMessages.length === 0) {
          name = 'get_job_requirements'
          args = {}
        } else if (toolMessages.length === 1) {
          name = 'search_resume_evidence'
          args = { query: 'Java Spring Boot Redis RabbitMQ', topK: 3 }
        } else {
          const evidenceIds = toolMessages
            .flatMap((message) => {
              try {
                const content = JSON.parse(message.content)
                return content?.untrustedData?.evidence ?? []
              } catch {
                return []
              }
            })
            .map((item) => item.evidenceId)
            .filter(Boolean)
          const citedEvidenceIds = [...new Set(evidenceIds)].slice(0, 3)
          const hasEvidence = citedEvidenceIds.length > 0

          name = 'submit_match_report'
          args = {
            matchScore: hasEvidence ? 91 : 25,
            coreClaims: hasEvidence
              ? [
                  {
                    claim: '候选人的后端工程经历与岗位要求有直接匹配证据。',
                    evidenceIds: citedEvidenceIds,
                  },
                ]
              : [],
            matchedSkills: hasEvidence
              ? ['Java', 'Spring Boot', 'Redis', 'RabbitMQ'].map((claim) => ({
                  claim,
                  evidenceIds: citedEvidenceIds,
                }))
              : [],
            skillGaps: ['需要继续补充真实生产流量与 Agent 评测证据'],
            recommendations: [
              '补充工具调用正确率评测',
              '记录端到端延迟和成本',
              '增加提示注入回归测试',
            ],
            interviewQuestions: [
              '为什么限制 Agent 最大步数？',
              '如何校验工具调用参数？',
              '如何保证异步重试幂等？',
              '如何设计 Agent 评测集？',
              '如何处理提示注入？',
            ],
          }
        }

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
