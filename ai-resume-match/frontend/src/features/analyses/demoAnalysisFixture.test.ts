import { expect, test } from 'vitest'

import {
  createDemoResumeFile,
  DEMO_JOB_CONTENT,
  DEMO_JOB_TITLE,
  DEMO_RESUME_FILE_NAME,
} from './demoAnalysisFixture'

test('creates a deterministic synthetic PDF with valid object offsets', async () => {
  const file = createDemoResumeFile()
  const source = await file.text()

  expect(file.name).toBe(DEMO_RESUME_FILE_NAME)
  expect(file.type).toBe('application/pdf')
  expect(file.size).toBeGreaterThan(500)
  expect(source).toMatch(/^%PDF-1\.4\n/)
  expect(source).toContain('SYNTHETIC DEMO RESUME - NOT A REAL PERSON')
  expect(source).toContain('Java, Spring Boot, Redis, RabbitMQ')
  expect(source).toMatch(/trailer\n<< \/Size 6 \/Root 1 0 R >>/)
  expect(source).toMatch(/startxref\n\d+\n%%EOF\n$/)

  const xrefEntries = source
    .match(/xref\n0 6\n([\s\S]+?)trailer/)?.[1]
    ?.trimEnd()
    .split('\n')
  expect(xrefEntries).toHaveLength(6)
  for (let objectNumber = 1; objectNumber <= 5; objectNumber += 1) {
    const offset = Number(xrefEntries?.[objectNumber]?.slice(0, 10))
    expect(source.slice(offset)).toMatch(
      new RegExp(`^${objectNumber} 0 obj\\n`),
    )
  }
})

test('labels all demo form data as synthetic', () => {
  expect(DEMO_RESUME_FILE_NAME).toContain('synthetic')
  expect(DEMO_JOB_TITLE).toContain('合成演示')
  expect(DEMO_JOB_CONTENT).toContain('完全合成')
  expect(DEMO_JOB_CONTENT).toContain('不包含真实个人或公司信息')
})
