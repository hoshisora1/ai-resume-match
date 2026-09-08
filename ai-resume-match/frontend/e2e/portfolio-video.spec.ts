import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

import { installMockApi, trackExternalRequests } from './mockApi'

const captureEnabled = process.env.CAPTURE_PORTFOLIO_VIDEO === '1'
const portfolioDirectory = resolve(
  import.meta.dirname,
  '../../docs/assets/portfolio',
)

test.skip(!captureEnabled, '仅在显式生成作品录屏时运行')

test('生成约 60 秒成功与重试路径作品录屏', async ({ browser }, testInfo) => {
  test.setTimeout(120_000)
  await mkdir(portfolioDirectory, { recursive: true })

  const context = await browser.newContext({
    baseURL: String(testInfo.project.use.baseURL ?? 'http://127.0.0.1:4173'),
    recordVideo: {
      dir: testInfo.outputPath('recording'),
      size: { width: 1280, height: 720 },
    },
    viewport: { width: 1280, height: 720 },
  })
  const page = await context.newPage()
  const video = page.video()

  try {
    await installMockApi(page)
    const externalRequests = trackExternalRequests(page)

    await page.goto('/')
    await expect(
      page.getByRole('heading', { level: 1, name: '分析总览' }),
    ).toBeVisible()
    await expect(page.getByRole('table', { name: '最近五条分析' })).toBeVisible()
    await page.waitForTimeout(5_000)

    await page.getByRole('link', { name: '体验合成演示' }).click()
    await expect(
      page.getByRole('status', { name: '合成演示数据已填入' }),
    ).toBeVisible()
    await page.waitForTimeout(6_000)

    await page.getByRole('textbox', { name: '岗位 JD' }).scrollIntoViewIfNeeded()
    await page.waitForTimeout(5_000)
    await page.getByRole('button', { name: '提交分析' }).scrollIntoViewIfNeeded()
    await page.getByRole('button', { name: '提交分析' }).click()

    await expect(
      page.getByRole('status', { name: '任务状态更新' }),
    ).toContainText('已完成', { timeout: 15_000 })
    await expect(page.getByText('88.0 / 100')).toBeVisible()
    await page.getByRole('heading', { name: '报告内容' }).scrollIntoViewIfNeeded()
    await page.waitForTimeout(6_000)

    await page
      .getByRole('heading', { name: '岗位要求逐项判定' })
      .scrollIntoViewIfNeeded()
    await page.waitForTimeout(8_000)
    await page.getByRole('button', { name: '查看核心结论证据 resume:0' }).click()
    await expect(
      page.getByRole('complementary', { name: '证据详情 resume:0' }),
    ).toBeVisible()
    await page.waitForTimeout(8_000)
    await page.getByRole('button', { name: '关闭' }).click()

    await page.getByText('查看运行溯源').scrollIntoViewIfNeeded()
    await page.getByText('查看运行溯源').click()
    await expect(page.getByText('deterministic-e2e-model')).toBeVisible()
    await page.waitForTimeout(7_000)

    await page.goto('/analyses/43')
    await expect(
      page.getByRole('heading', { name: '本次分析可以重试' }),
    ).toBeVisible()
    await page.waitForTimeout(7_000)
    await page.getByRole('button', { name: '重新分析' }).click()
    await expect(
      page.getByRole('heading', { name: '任务正在排队' }),
    ).toBeVisible()
    await page.waitForTimeout(6_000)

    await page.getByRole('link', { name: '总览' }).click()
    await expect(
      page.getByRole('heading', { level: 1, name: '分析总览' }),
    ).toBeVisible()
    await page.waitForTimeout(5_000)

    expect(externalRequests).toEqual([])
  } finally {
    await context.close()
    await video?.saveAs(
      resolve(portfolioDirectory, 'product-walkthrough.webm'),
    )
  }
})
