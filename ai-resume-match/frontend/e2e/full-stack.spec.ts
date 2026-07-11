import { expect, test } from '@playwright/test'
import { PDFDocument, StandardFonts } from 'pdf-lib'

const jobTitle = '高级 Java AI 应用工程师'

test.skip(
  process.env.FULL_STACK_E2E !== 'true',
  '仅由真实全栈编排脚本运行',
)

async function createResumePdf() {
  const pdf = await PDFDocument.create()
  const page = pdf.addPage([595, 842])
  const font = await pdf.embedFont(StandardFonts.Helvetica)
  page.drawText('Java Spring Boot Redis RabbitMQ candidate', {
    font,
    size: 12,
    x: 50,
    y: 780,
  })
  return Buffer.from(await pdf.save())
}

test('用户可通过真实全栈完成分析并在历史中复查', async ({ page }) => {
  await page.goto('/')
  await expect(
    page.getByRole('heading', { level: 1, name: '分析总览' }),
  ).toBeVisible()

  await page.getByRole('link', { name: '新建分析' }).first().click()
  await expect(
    page.getByRole('heading', { level: 1, name: '新建分析' }),
  ).toBeVisible()

  await page.getByLabel('选择简历文件').setInputFiles({
    name: 'resume.pdf',
    mimeType: 'application/pdf',
    buffer: await createResumePdf(),
  })
  await page.getByLabel('岗位名称').fill(jobTitle)
  await page
    .getByLabel('岗位 JD')
    .fill('负责 Java、Spring Boot、Redis、RabbitMQ 与 AI 应用工程化交付。')
  await page.getByRole('button', { name: '提交分析' }).click()

  await expect(page).toHaveURL(/\/analyses\/\d+$/)
  await expect(
    page.getByRole('status', { name: '任务状态更新' }),
  ).toContainText('已完成', { timeout: 60_000 })
  await expect(page.getByText('91.0 / 100')).toBeVisible()
  await expect(
    page.getByRole('heading', { name: '核心结论' }),
  ).toBeVisible()

  await page.getByRole('link', { name: '分析记录' }).click()
  await expect(
    page.getByRole('heading', { level: 1, name: '分析历史' }),
  ).toBeVisible()
  await expect(page.getByRole('link', { name: jobTitle })).toBeVisible()
  await expect(page.getByText('resume.pdf')).toBeVisible()
})
