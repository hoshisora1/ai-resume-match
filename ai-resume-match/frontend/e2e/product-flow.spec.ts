import { expect, test } from '@playwright/test'

import { installMockApi, trackExternalRequests } from './mockApi'

test('用户可从总览提交分析、查看报告并返回历史', async ({ page }) => {
  const controls = await installMockApi(page)
  const externalRequests = trackExternalRequests(page)

  await page.goto('/')
  await expect(
    page.getByRole('heading', { level: 1, name: '分析总览' }),
  ).toBeVisible()
  await expect(page.getByRole('table', { name: '最近五条分析' })).toBeVisible()

  await page.getByRole('link', { name: '新建分析' }).first().click()
  await page.getByLabel('选择简历文件').setInputFiles({
    name: 'resume.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('%PDF-1.4\nsynthetic browser fixture\n%%EOF'),
  })
  await page.getByLabel('岗位名称').fill('高级 Java AI 应用工程师')
  await page
    .getByLabel('岗位 JD')
    .fill('负责 Java、Spring Boot、Redis、RabbitMQ 与 AI 应用工程化交付。')
  await page.getByRole('button', { name: '提交分析' }).click()

  await expect(page).toHaveURL(/\/analyses\/42$/)
  await expect(
    page.getByRole('status', { name: '任务状态更新' }),
  ).toContainText('已完成', { timeout: 15_000 })
  expect(controls.state.task42FetchCount).toBeGreaterThanOrEqual(3)
  await expect(page.getByText('88.0 / 100')).toBeVisible()
  await expect(
    page.getByRole('heading', { name: '核心结论' }),
  ).toBeVisible()

  expect(controls.state.submissionContentType).toContain('multipart/form-data')
  expect(controls.state.submissionBody).toContain('name="file"')
  expect(controls.state.submissionBody).toContain('name="jobTitle"')
  expect(controls.state.submissionBody).toContain('高级 Java AI 应用工程师')
  expect(controls.state.submissionBody).toContain('name="jobContent"')

  await page.getByRole('link', { name: '分析记录' }).click()
  await expect(page.getByRole('table', { name: '分析历史' })).toBeVisible()
  await expect(
    page.getByRole('link', { name: '高级 Java AI 应用工程师' }),
  ).toBeVisible()
  await expect(page.getByText('resume.pdf')).toBeVisible()
  expect(externalRequests).toEqual([])
})

test('可重试失败可重新入队', async ({ page }) => {
  await installMockApi(page)

  await page.goto('/analyses/43')
  await expect(
    page.getByRole('heading', { name: '本次分析可以重试' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '重新分析' }).click()

  await expect(
    page.getByRole('status', { name: '任务状态更新' }),
  ).toContainText('待处理')
  await expect(
    page.getByRole('heading', { name: '任务正在排队' }),
  ).toBeVisible()
})

test('最终失败保留终态且刷新错误展示请求 ID', async ({ page }) => {
  const controls = await installMockApi(page)

  await page.goto('/analyses/44')
  await expect(
    page.getByRole('status', { name: '任务状态更新' }),
  ).toContainText('最终失败')
  await expect(
    page.getByRole('button', { name: '重新分析' }),
  ).toHaveCount(0)

  controls.failFinalTaskRefresh()
  await page.getByRole('link', { name: '总览' }).click()
  await expect(
    page.getByRole('heading', { level: 1, name: '分析总览' }),
  ).toBeVisible()
  await page.goBack()

  await expect(
    page.getByText('连接中断，当前显示上次获取的任务状态。'),
  ).toBeVisible()
  await expect(page.getByText('req-final-44')).toBeVisible()
  await expect(
    page.getByRole('status', { name: '任务状态更新' }),
  ).toContainText('最终失败')
  await expect(
    page.getByRole('button', { name: '重新分析' }),
  ).toHaveCount(0)
})
