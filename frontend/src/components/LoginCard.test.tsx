import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '../test/setup'
import { mockState } from '../test/handlers'
import { LoginCard } from './LoginCard'

function renderCard() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><LoginCard /></QueryClientProvider>)
}

test('успешный вход дёргает POST /api/auth/login', async () => {
  renderCard()
  await userEvent.type(screen.getByPlaceholderText(/\+7/), '+79990001122')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  // mockState.me выставляет MSW-ручка логина; ре-рендер профиля проверяет App-тест задачи 11
  expect(mockState.me?.phone).toBe('+79990001122')
})

test('UNKNOWN_PHONE раскрывает форму заявки', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'UNKNOWN_PHONE', message: '' }, { status: 401 })))
  renderCard()
  await userEvent.type(screen.getByPlaceholderText(/\+7/), '+79990009999')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  expect(await screen.findByText(/нет в списке гостей/)).toBeInTheDocument()

  await userEvent.type(screen.getByPlaceholderText(/зовут/), 'Незнакомец')
  await userEvent.click(screen.getByRole('button', { name: 'Отправить заявку' }))
  expect(await screen.findByText(/Заявка отправлена/)).toBeInTheDocument()
})

test('ALREADY_MEMBER в заявке показывает подсказку', async () => {
  server.use(
    http.post('/api/auth/login', () =>
      HttpResponse.json({ code: 'UNKNOWN_PHONE', message: '' }, { status: 401 })),
    http.post('/api/access-requests', () =>
      HttpResponse.json({ code: 'ALREADY_MEMBER', message: '' }, { status: 409 })),
  )
  renderCard()
  await userEvent.type(screen.getByPlaceholderText(/\+7/), '+79990008888')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  await userEvent.type(await screen.findByPlaceholderText(/зовут/), 'Свой')
  await userEvent.click(screen.getByRole('button', { name: 'Отправить заявку' }))
  expect(await screen.findByText(/уже в списке — просто войди/)).toBeInTheDocument()
})

test('RATE_LIMITED показывает «подожди минуту»', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'RATE_LIMITED', message: '' }, { status: 429 })))
  renderCard()
  await userEvent.type(screen.getByPlaceholderText(/\+7/), '+79990007777')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  expect(await screen.findByText(/подожди минуту/)).toBeInTheDocument()
})
