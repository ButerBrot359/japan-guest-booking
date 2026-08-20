import { http, HttpResponse } from 'msw'
import { server } from './../test/setup'
import { api, ApiError } from './client'

test('успешный GET парсит JSON', async () => {
  server.use(http.get('/api/ping', () => HttpResponse.json({ ok: true })))
  await expect(api.get('/ping')).resolves.toEqual({ ok: true })
})

test('204 отдаёт undefined', async () => {
  server.use(http.post('/api/ping', () => new HttpResponse(null, { status: 204 })))
  await expect(api.post('/ping', {})).resolves.toBeUndefined()
})

test('ошибка {code,message} становится ApiError', async () => {
  server.use(http.get('/api/ping', () =>
    HttpResponse.json({ code: 'UNKNOWN_PHONE', message: 'Номер не найден' }, { status: 401 })))
  const err = await api.get('/ping').catch((e) => e)
  expect(err).toBeInstanceOf(ApiError)
  // сужаем тип рантайм-проверкой: api.get<T>() без явного T выводится как
  // unknown, а unknown в объединении с любым TResult из .catch() остаётся unknown
  if (!(err instanceof ApiError)) throw err
  expect(err.code).toBe('UNKNOWN_PHONE')
  expect(err.status).toBe(401)
})

test('не-JSON ответ с ошибкой становится ApiError INTERNAL', async () => {
  server.use(http.get('/api/ping', () => new HttpResponse('boom', { status: 502 })))
  const err = await api.get('/ping').catch((e) => e)
  if (!(err instanceof ApiError)) throw err
  expect(err.code).toBe('INTERNAL_ERROR')
})
