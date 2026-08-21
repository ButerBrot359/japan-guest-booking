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
  await userEvent.type(screen.getByTestId('phone-input'), '9990001122')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  // mockState.me выставляет MSW-ручка логина; ре-рендер профиля проверяет App-тест задачи 11
  expect(mockState.me?.phone).toBe('+79990001122')
})

test('UNKNOWN_PHONE раскрывает форму заявки', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'UNKNOWN_PHONE', message: '' }, { status: 401 })))
  renderCard()
  await userEvent.type(screen.getByTestId('phone-input'), '9990009999')
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
  await userEvent.type(screen.getByTestId('phone-input'), '9990008888')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  await userEvent.type(await screen.findByPlaceholderText(/зовут/), 'Свой')
  await userEvent.click(screen.getByRole('button', { name: 'Отправить заявку' }))
  expect(await screen.findByText(/уже в списке — просто войди/)).toBeInTheDocument()
})

test('RATE_LIMITED показывает «подожди минуту»', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'RATE_LIMITED', message: '' }, { status: 429 })))
  renderCard()
  await userEvent.type(screen.getByTestId('phone-input'), '9990007777')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  expect(await screen.findByText(/подожди минуту/)).toBeInTheDocument()
})

test('повторный вход сбрасывает состояние прошлой заявки', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'UNKNOWN_PHONE', message: '' }, { status: 401 })))
  renderCard()

  await userEvent.type(screen.getByTestId('phone-input'), '9990001111')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  await userEvent.type(await screen.findByPlaceholderText(/зовут/), 'Незнакомец')
  await userEvent.click(screen.getByRole('button', { name: 'Отправить заявку' }))
  expect(await screen.findByText(/Заявка отправлена/)).toBeInTheDocument()

  const phoneInput = screen.getByTestId('phone-input')
  await userEvent.clear(phoneInput)
  await userEvent.type(phoneInput, '9990002222')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))

  const nameInput = await screen.findByPlaceholderText(/зовут/)
  expect(screen.queryByText(/Заявка отправлена/)).not.toBeInTheDocument()
  expect(nameInput).toHaveValue('')
})

test('правка цифры в середине номера не разъезжается (курсор управляется)', async () => {
  renderCard()
  const input = screen.getByTestId('phone-input') as HTMLInputElement
  await userEvent.type(input, '7787886462')
  expect(input).toHaveValue('+7 (778) 788-64-62')
  // ошиблись в девятой цифре: выделяем «6» (индекс 16) и печатаем «3»
  await userEvent.type(input, '3', { initialSelectionStart: 16, initialSelectionEnd: 17 })
  expect(input).toHaveValue('+7 (778) 788-64-32')
  // курсор остался сразу после исправленной цифры, а не упрыгал в конец
  expect(input.selectionStart).toBe(17)
})

test('вставка цифры в середину встаёт на своё место', async () => {
  renderCard()
  const input = screen.getByTestId('phone-input') as HTMLInputElement
  await userEvent.type(input, '778788643')
  expect(input).toHaveValue('+7 (778) 788-64-3')
  // забыли цифру «6» после «788-»: ставим курсор перед «6» (индекс 13) и печатаем
  await userEvent.type(input, '6', { initialSelectionStart: 13, initialSelectionEnd: 13 })
  expect(input).toHaveValue('+7 (778) 788-66-43')
  expect(input.selectionStart).toBe(14)
})
