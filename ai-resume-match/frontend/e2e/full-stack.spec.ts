import { expect, test } from '@playwright/test'

import {
  DEMO_JOB_TITLE,
  DEMO_RESUME_FILE_NAME,
} from '../src/features/analyses/demoAnalysisFixture'

test.skip(
  process.env.FULL_STACK_E2E !== 'true',
  '仅由真实全栈编排脚本运行',
)

test('用户可通过真实全栈完成分析并在历史中复查', async ({ page }) => {
  await page.goto('/')
  await expect(
    page.getByRole('heading', { level: 1, name: '分析总览' }),
  ).toBeVisible()

  await page.getByRole('link', { name: '新建分析' }).first().click()
  await expect(
    page.getByRole('heading', { level: 1, name: '新建分析' }),
  ).toBeVisible()

  await page.getByRole('button', { name: '一键填入合成示例' }).click()
  await expect(
    page.getByRole('status', { name: '合成演示数据已填入' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '提交分析' }).click()

  await expect(page).toHaveURL(/\/analyses\/\d+$/)
  await expect(
    page.getByRole('status', { name: '任务状态更新' }),
  ).toContainText('已完成', { timeout: 60_000 })
  await expect(page.getByText('100.0 / 100')).toBeVisible()
  await expect(
    page.getByRole('heading', { name: 'Java', exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('heading', { name: '核心结论' }),
  ).toBeVisible()
  await page
    .getByRole('button', { name: '查看核心结论证据 resume:0' })
    .first()
    .click()
  await expect(
    page.getByRole('complementary', { name: '证据详情 resume:0' }),
  ).toContainText('Java, Spring Boot, Redis, RabbitMQ')
  await page.getByRole('button', { name: '关闭' }).click()
  await page.getByText('查看运行溯源').click()
  await expect(page.getByText('e2e-agent-model')).toBeVisible()
  await expect(page.getByText('requirement-verified-agent-v3')).toBeVisible()
  await expect(page.getByText('bounded-tool-agent-v1')).toBeVisible()
  await expect(page.getByText(/Chat \d+ 次 \/ 上下文 \d+ 字符/)).toBeVisible()

  await page.getByRole('link', { name: '分析记录' }).click()
  await expect(
    page.getByRole('heading', { level: 1, name: '分析历史' }),
  ).toBeVisible()
  await expect(page.getByRole('link', { name: DEMO_JOB_TITLE })).toBeVisible()
  await expect(page.getByText(DEMO_RESUME_FILE_NAME)).toBeVisible()
})
