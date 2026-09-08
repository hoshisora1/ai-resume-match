import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

import { installMockApi, trackExternalRequests } from './mockApi'

const captureEnabled = process.env.CAPTURE_PORTFOLIO === '1'
const portfolioDirectory = resolve(
  import.meta.dirname,
  '../../docs/assets/portfolio',
)

test.skip(!captureEnabled, '仅在显式生成作品截图时运行')

test('生成可复现的桌面与移动端作品截图', async ({ page }) => {
  await mkdir(portfolioDirectory, { recursive: true })
  await installMockApi(page)
  const externalRequests = trackExternalRequests(page)

  await page.setViewportSize({ width: 1440, height: 1024 })
  await page.goto('/')
  await expect(
    page.getByRole('heading', { level: 1, name: '分析总览' }),
  ).toBeVisible()
  await expect(page.getByRole('table', { name: '最近五条分析' })).toBeVisible()
  await page.screenshot({
    fullPage: true,
    path: resolve(portfolioDirectory, 'dashboard-desktop.png'),
  })

  await page.getByRole('link', { name: '体验合成演示' }).click()
  await expect(
    page.getByRole('status', { name: '合成演示数据已填入' }),
  ).toBeVisible()

  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({
    fullPage: false,
    path: resolve(portfolioDirectory, 'demo-mobile.png'),
  })

  await page.setViewportSize({ width: 1440, height: 1024 })
  await page.getByRole('button', { name: '提交分析' }).click()
  await expect(
    page.getByRole('status', { name: '任务状态更新' }),
  ).toContainText('已完成', { timeout: 15_000 })
  await expect(page.getByText('88.0 / 100')).toBeVisible()
  await page.getByRole('button', { name: '查看核心结论证据 resume:0' }).click()
  await expect(
    page.getByRole('complementary', { name: '证据详情 resume:0' }),
  ).toBeVisible()
  await page.screenshot({
    fullPage: false,
    path: resolve(portfolioDirectory, 'report-evidence-desktop.png'),
  })

  expect(externalRequests).toEqual([])
})
