function parseToolOutput(message) {
  try {
    return JSON.parse(message.content)
  } catch {
    return null
  }
}

export function nextToolCall(messages) {
  const toolOutputs = messages
    .filter((message) => message.role === 'tool')
    .map(parseToolOutput)
    .filter((output) => output !== null)
  const requirementOutput = toolOutputs.find(
    (output) => Array.isArray(output?.untrustedData?.requirements),
  )

  if (requirementOutput === undefined) {
    return { name: 'get_job_requirements', args: {} }
  }

  const requirements = requirementOutput.untrustedData.requirements
  const searchOutputs = toolOutputs.filter(
    (output) => typeof output?.untrustedData?.requirementId === 'string',
  )
  const searchedRequirementIds = new Set(
    searchOutputs.map((output) => output.untrustedData.requirementId),
  )
  const nextRequirement = requirements.find(
    (requirement) => !searchedRequirementIds.has(requirement.requirementId),
  )

  if (nextRequirement !== undefined) {
    return {
      name: 'search_resume_evidence',
      args: {
        requirementId: nextRequirement.requirementId,
        query: nextRequirement.text,
        topK: 3,
      },
    }
  }

  const evidenceByRequirement = new Map(
    searchOutputs.map((output) => [
      output.untrustedData.requirementId,
      [
        ...new Set(
          (output.untrustedData.evidence ?? [])
            .map((item) => item.evidenceId)
            .filter((evidenceId) => typeof evidenceId === 'string'),
        ),
      ].slice(0, 3),
    ]),
  )

  return {
    name: 'submit_match_report',
    args: {
      requirementAssessments: requirements.map((requirement) => {
        const evidenceIds = evidenceByRequirement.get(requirement.requirementId) ?? []
        return {
          requirementId: requirement.requirementId,
          status: evidenceIds.length > 0 ? 'supported' : 'not_found',
          explanation:
            evidenceIds.length > 0
              ? 'The retrieved resume excerpt directly covers this requirement.'
              : 'No relevant resume evidence was retrieved for this requirement.',
          evidenceIds,
        }
      }),
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
    },
  }
}
