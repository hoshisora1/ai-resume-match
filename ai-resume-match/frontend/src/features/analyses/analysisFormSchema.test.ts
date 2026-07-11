import { expect, test } from 'vitest'

import * as analysisFormSchemaModule from './analysisFormSchema'

const validFile = new File(['resume'], 'candidate.pdf')

function parseTextValues(jobTitle: string, jobContent: string) {
  return analysisFormSchemaModule.analysisFormSchema.safeParse({
    file: validFile,
    jobTitle,
    jobContent,
  })
}

test('exports a Java-union Unicode whitespace predicate', () => {
  const predicate = Reflect.get(
    analysisFormSchemaModule,
    'isJavaUnicodeWhitespaceCodePoint',
  )

  expect(predicate).toBeTypeOf('function')
  if (typeof predicate !== 'function') {
    return
  }

  for (const codePoint of [
    0x0009, 0x000d, 0x001c, 0x001f, 0x00a0, 0x2007, 0x2028, 0x2029,
    0x202f, 0x3000,
  ]) {
    expect(predicate(codePoint), `U+${codePoint.toString(16)}`).toBe(true)
  }

  for (const codePoint of [0x0085, 0xfeff, 0x0041]) {
    expect(predicate(codePoint), `U+${codePoint.toString(16)}`).toBe(false)
  }
})

test.each([
  ['U+001C', '\u001c'],
  ['NBSP', '\u00a0'],
  ['U+3000', '\u3000'],
])('rejects Java-union blank %s for both title and JD', (_, blankValue) => {
  const result = parseTextValues(blankValue, blankValue)

  expect(result.success).toBe(false)
  if (result.success) {
    return
  }

  const fieldErrors = result.error.flatten().fieldErrors
  expect(fieldErrors.jobTitle).toContain('请输入岗位名称')
  expect(fieldErrors.jobContent).toContain('请输入岗位描述')
})

test.each([
  ['U+0085', '\u0085'],
  ['U+FEFF', '\ufeff'],
])('accepts backend-visible %s for both title and JD', (_, visibleValue) => {
  const result = parseTextValues(visibleValue, visibleValue)

  expect(result.success).toBe(true)
  if (!result.success) {
    return
  }

  expect(result.data.jobTitle).toBe(visibleValue)
  expect(result.data.jobContent).toBe(visibleValue)
})

test('accepts mixed visible content without applying JavaScript trim semantics', () => {
  const jobTitle = '\u00a0岗位名称\u3000'
  const jobContent = '\u001c岗位描述\u2029'
  const result = parseTextValues(jobTitle, jobContent)

  expect(result.success).toBe(true)
  if (!result.success) {
    return
  }

  expect(result.data.jobTitle).toBe(jobTitle)
  expect(result.data.jobContent).toBe(jobContent)
})

test('keeps emoji limits based on Unicode code points', () => {
  expect(parseTextValues('😀'.repeat(120), '🚀'.repeat(20_000)).success).toBe(
    true,
  )
  expect(parseTextValues('😀'.repeat(121), '岗位描述').success).toBe(false)
  expect(parseTextValues('岗位名称', '🚀'.repeat(20_001)).success).toBe(false)
})
