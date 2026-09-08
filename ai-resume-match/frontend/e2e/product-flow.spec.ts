import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'

import {
  DEMO_JOB_TITLE,
  DEMO_RESUME_FILE_NAME,
} from '../src/features/analyses/demoAnalysisFixture'
import { installMockApi, trackExternalRequests } from './mockApi'

test('用户可从总览提交分析、查看报告并返回历史', async ({ page }) => {
  const controls = await installMockApi(page)
  const externalRequests = trackExternalRequests(page)

  await page.goto('/')
  await expect(
    page.getByRole('heading', { level: 1, name: '分析总览' }),
  ).toBeVisible()
  await expect(page.getByRole('table', { name: '最近五条分析' })).toBeVisible()

  await page.getByRole('link', { name: '体验合成演示' }).click()
  await expect(page).toHaveURL(/\/analyses\/new\?demo=1$/)
  await expect(
    page.getByRole('status', { name: '合成演示数据已填入' }),
  ).toBeVisible()
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

  const jsonDownloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 JSON' }).click()
  const jsonDownload = await jsonDownloadPromise
  expect(jsonDownload.suggestedFilename()).toBe(
    'analysis-42-match-report.json',
  )
  const jsonPath = await jsonDownload.path()
  expect(jsonPath).not.toBeNull()
  const exportedReport = JSON.parse(
    await readFile(jsonPath as string, 'utf8'),
  ) as Record<string, unknown>
  expect(exportedReport).toMatchObject({
    schemaVersion: 'match-report-export-v1',
    sourceDocumentsIncluded: false,
    taskId: 42,
    matchScore: 88,
    reportSchemaVersion: 'match-report-v2',
  })
  expect(exportedReport).not.toHaveProperty('resumeText')
  expect(exportedReport).not.toHaveProperty('jobDescription')

  const markdownDownloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 Markdown' }).click()
  const markdownDownload = await markdownDownloadPromise
  expect(markdownDownload.suggestedFilename()).toBe(
    'analysis-42-match-report.md',
  )
  const markdownPath = await markdownDownload.path()
  expect(markdownPath).not.toBeNull()
  expect(await readFile(markdownPath as string, 'utf8')).toContain(
    '# 匹配报告',
  )

  await page.getByRole('button', { name: '查看核心结论证据 resume:0' }).click()
  await expect(
    page.getByRole('complementary', { name: '证据详情 resume:0' }),
  ).toContainText('Java、Spring Boot 与 RabbitMQ')
  await page.getByRole('button', { name: '关闭' }).click()

  expect(controls.state.submissionContentType).toContain('multipart/form-data')
  expect(controls.state.submissionBody).toContain('name="file"')
  expect(controls.state.submissionBody).toContain(DEMO_RESUME_FILE_NAME)
  expect(controls.state.submissionBody).toContain('name="jobTitle"')
  expect(controls.state.submissionBody).toContain(DEMO_JOB_TITLE)
  expect(controls.state.submissionBody).toContain('name="jobContent"')
  expect(controls.state.submissionBody).toContain('完全合成')

  await page.getByRole('link', { name: '再次分析' }).click()
  await expect(page).toHaveURL(/\/analyses\/new$/)
  await expect(
    page.getByRole('status', { name: '合成演示数据已填入' }),
  ).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: '岗位名称' })).toHaveValue('')

  await page.getByRole('link', { name: '分析记录' }).click()
  await expect(page.getByRole('table', { name: '分析历史' })).toBeVisible()
  await expect(
    page.getByRole('link', { name: DEMO_JOB_TITLE }),
  ).toBeVisible()
  await expect(page.getByText(DEMO_RESUME_FILE_NAME)).toBeVisible()
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
