import assert from 'node:assert/strict'
import test from 'node:test'

import { nextToolCall } from './protocol.mjs'

function toolMessage(output) {
  return { role: 'tool', content: JSON.stringify(output) }
}

const requirements = [
  { requirementId: 'requirement:0', text: 'Java' },
  { requirementId: 'requirement:1', text: 'Redis' },
]

const requirementMessage = toolMessage({
  ok: true,
  untrustedData: { requirements },
})

function searchMessage(requirementId, evidence) {
  return toolMessage({
    ok: true,
    untrustedData: { requirementId, evidence },
  })
}

test('reads requirements before searching', () => {
  assert.deepEqual(nextToolCall([]), {
    name: 'get_job_requirements',
    args: {},
  })
})

test('searches every dynamic requirement in order with its stable ID', () => {
  assert.deepEqual(nextToolCall([requirementMessage]), {
    name: 'search_resume_evidence',
    args: { requirementId: 'requirement:0', query: 'Java', topK: 3 },
  })

  assert.deepEqual(
    nextToolCall([
      requirementMessage,
      searchMessage('requirement:0', [{ evidenceId: 'resume:0' }]),
    ]),
    {
      name: 'search_resume_evidence',
      args: { requirementId: 'requirement:1', query: 'Redis', topK: 3 },
    },
  )
})

test('submits same-requirement evidence and fails closed on an empty search', () => {
  const call = nextToolCall([
    requirementMessage,
    searchMessage('requirement:0', [
      { evidenceId: 'resume:0' },
      { evidenceId: 'resume:0' },
      { evidenceId: 42 },
    ]),
    searchMessage('requirement:1', []),
  ])

  assert.equal(call.name, 'submit_match_report')
  assert.deepEqual(call.args.requirementAssessments, [
    {
      requirementId: 'requirement:0',
      status: 'supported',
      explanation: 'The retrieved resume excerpt directly covers this requirement.',
      evidenceIds: ['resume:0'],
    },
    {
      requirementId: 'requirement:1',
      status: 'not_found',
      explanation: 'No relevant resume evidence was retrieved for this requirement.',
      evidenceIds: [],
    },
  ])
})
