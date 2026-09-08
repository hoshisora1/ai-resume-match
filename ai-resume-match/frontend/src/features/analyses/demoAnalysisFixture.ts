export const DEMO_RESUME_FILE_NAME = 'synthetic-ai-fullstack-resume.pdf'
export const DEMO_JOB_TITLE = 'AI 全栈开发工程师（合成演示）'
export const DEMO_JOB_CONTENT = `这是一个完全合成的演示岗位，不包含真实个人或公司信息。

负责 Java 与 Spring Boot 服务开发，使用 Redis 构建缓存，并通过 RabbitMQ 实现可靠异步任务。
参与有界 Tool Calling Agent、证据检索、确定性评分和 React 产品界面的工程化交付。
重视测试、可观测性、数据隐私与人工复核。`

const DEMO_RESUME_LINES = [
  'SYNTHETIC DEMO RESUME - NOT A REAL PERSON',
  'Target role: AI Full Stack Engineer',
  'Skills: Java, Spring Boot, Redis, RabbitMQ, Python, FastAPI, React, TypeScript.',
  'Built asynchronous AI application workflows with transactional outbox and RabbitMQ.',
  'Implemented bounded tool-calling agents with evidence retrieval and deterministic scoring.',
  'Delivered tested Spring Boot APIs, Redis caching, and accessible React user interfaces.',
]

function escapePdfText(value: string) {
  return value.replaceAll('\\', '\\\\').replaceAll('(', '\\(').replaceAll(')', '\\)')
}

function createPdfBytes(lines: readonly string[]) {
  const contentStream = [
    'BT',
    '/F1 11 Tf',
    '72 740 Td',
    '15 TL',
    ...lines.flatMap((line, index) => [
      `(${escapePdfText(line)}) Tj`,
      ...(index === lines.length - 1 ? [] : ['T*']),
    ]),
    'ET',
    '',
  ].join('\n')
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${contentStream.length} >>\nstream\n${contentStream}endstream`,
  ]
  let pdf = '%PDF-1.4\n'
  const offsets: number[] = []

  for (const [index, object] of objects.entries()) {
    offsets.push(pdf.length)
    pdf += `${index + 1} 0 obj\n${object}\nendobj\n`
  }

  const xrefOffset = pdf.length
  pdf += `xref\n0 ${objects.length + 1}\n`
  pdf += '0000000000 65535 f \n'
  pdf += offsets
    .map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`)
    .join('')
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\n`
  pdf += `startxref\n${xrefOffset}\n%%EOF\n`

  return new TextEncoder().encode(pdf)
}

export function createDemoResumeFile() {
  return new File([createPdfBytes(DEMO_RESUME_LINES)], DEMO_RESUME_FILE_NAME, {
    type: 'application/pdf',
    lastModified: Date.UTC(2026, 7, 13),
  })
}
