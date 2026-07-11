import { expect, test, type Page, type TestInfo } from '@playwright/test'

import { installMockApi } from './mockApi'

const viewports = [
  { height: 900, label: 'desktop', width: 1440 },
  { height: 844, label: 'mobile', width: 390 },
] as const

async function expectNoPageOverflow(page: Page) {
  const overflow = await page.evaluate(
    () =>
      document.documentElement.scrollWidth >
      document.documentElement.clientWidth,
  )
  expect(overflow).toBe(false)
}

async function capture(
  page: Page,
  testInfo: TestInfo,
  name: string,
) {
  await expectNoPageOverflow(page)
  await page.screenshot({
    animations: 'disabled',
    fullPage: true,
    path: testInfo.outputPath(`${name}.png`),
  })
}

for (const viewport of viewports) {
  test(`${viewport.label} 关键页面无横向溢出`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport)
    const controls = await installMockApi(page)

    await page.goto('/')
    await expect(
      page.getByRole('heading', { level: 1, name: '分析总览' }),
    ).toBeVisible()
    await capture(page, testInfo, `${viewport.label}-dashboard`)

    await page.getByRole('link', { name: '新建分析' }).first().click()
    await expect(
      page.getByRole('heading', { level: 1, name: '新建分析' }),
    ).toBeVisible()
    await capture(page, testInfo, `${viewport.label}-new-analysis`)

    controls.setTask42Sequence(['RUNNING'])
    await page.goto('/analyses/42')
    await expect(
      page.getByRole('heading', { name: '正在生成匹配报告' }),
    ).toBeVisible()
    await capture(page, testInfo, `${viewport.label}-active-task`)

    controls.setTask42Sequence(['SUCCESS'])
    await page.reload()
    await expect(page.getByText('88.0 / 100')).toBeVisible()
    await capture(page, testInfo, `${viewport.label}-report`)
  })
}
