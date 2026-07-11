import { http, passthrough } from 'msw'

const localProxyTestRequest =
  /^http:\/\/127\.0\.0\.1:\d+\/(?:api\/probe|backend-health|actuator\/health\/readiness)$/

export const handlers = [
  http.get(localProxyTestRequest, () => passthrough()),
]
