import { z } from 'zod'

export const MAX_RESUME_FILE_SIZE = 5 * 1024 * 1024
export const MAX_JOB_TITLE_CODE_POINTS = 120
export const MAX_JOB_CONTENT_CODE_POINTS = 20_000

const UNICODE_SPACE_CATEGORY = /^(?:\p{Zs}|\p{Zl}|\p{Zp})$/u

export function isJavaUnicodeWhitespaceCodePoint(codePoint: number) {
  if (!Number.isInteger(codePoint) || codePoint < 0 || codePoint > 0x10ffff) {
    return false
  }

  return (
    (codePoint >= 0x0009 && codePoint <= 0x000d) ||
    (codePoint >= 0x001c && codePoint <= 0x001f) ||
    UNICODE_SPACE_CATEGORY.test(String.fromCodePoint(codePoint))
  )
}

export function hasNonJavaUnicodeWhitespaceCodePoint(value: string) {
  return Array.from(value).some(
    (character) =>
      !isJavaUnicodeWhitespaceCodePoint(character.codePointAt(0)!),
  )
}

const codePointCount = (value: string) => Array.from(value).length

const jobTitleSchema = z
  .string()
  .refine(hasNonJavaUnicodeWhitespaceCodePoint, '请输入岗位名称')
  .refine(
    (value) => codePointCount(value) <= MAX_JOB_TITLE_CODE_POINTS,
    '岗位名称不能超过 120 个字符',
  )

const jobContentSchema = z
  .string()
  .refine(hasNonJavaUnicodeWhitespaceCodePoint, '请输入岗位描述')
  .refine(
    (value) => codePointCount(value) <= MAX_JOB_CONTENT_CODE_POINTS,
    '岗位描述不能超过 20,000 个字符',
  )

export const analysisFormSchema = z.object({
  file: z
    .instanceof(File, { error: '请选择简历文件' })
    .refine((file) => file.size > 0, '请选择非空简历文件')
    .refine(
      (file) => file.size <= MAX_RESUME_FILE_SIZE,
      '简历文件不能超过 5 MB',
    )
    .refine((file) => /\.(?:pdf|docx)$/i.test(file.name), '仅支持 PDF 或 DOCX'),
  jobTitle: jobTitleSchema,
  jobContent: jobContentSchema,
})

export type AnalysisFormValues = z.infer<typeof analysisFormSchema>
