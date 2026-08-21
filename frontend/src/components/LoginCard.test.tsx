import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { vi } from 'vitest'
import { server } from '../test/setup'
import { mockState } from '../test/handlers'
import { LoginCard } from './LoginCard'

function renderCard() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><LoginCard /></QueryClientProvider>)
}

async function typePhone(digits: string) {
  await userEvent.type(screen.getByTestId('phone-input'), digits)
}

test('после отправки номера показывает шаг кода и входит по верному коду', async () => {
  renderCard()
  await typePhone('7787886432')
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }))

  const codeInput = await screen.findByLabelText('Код из Telegram')
  await userEvent.type(codeInput, '123456')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  await waitFor(() => expect(mockState.me).not.toBeNull())
})

test('неверный код показывает ошибку и не пускает', async () => {
  renderCard()
  await typePhone('7787886432')
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }))

  const codeInput = await screen.findByLabelText('Код из Telegram')
  await userEvent.type(codeInput, '000000')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  expect(await screen.findByText('Неверный код')).toBeInTheDocument()
  expect(mockState.me).toBeNull()
})

test('TELEGRAM_NOT_LINKED показывает инструкцию про бота', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'TELEGRAM_NOT_LINKED', message: '' }, { status: 409 })))
  renderCard()
  await typePhone('7787886432')
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }))
  expect(await screen.findByText(/Мы шлём код входа в Telegram/)).toBeInTheDocument()
})

test('«Изменить номер» возвращает на шаг телефона', async () => {
  renderCard()
  await typePhone('7787886432')
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }))
  await screen.findByLabelText('Код из Telegram')

  await userEvent.click(screen.getByRole('button', { name: 'Изменить номер' }))
  expect(screen.getByTestId('phone-input')).toBeInTheDocument()
  expect(screen.queryByLabelText('Код из Telegram')).not.toBeInTheDocument()
})

test('resend RATE_LIMITED на шаге кода тоже показывает «подожди минуту» (не проглатывается)', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  try {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderCard()
    await user.type(screen.getByTestId('phone-input'), '7787886432')
    await user.click(screen.getByRole('button', { name: 'Получить код' }))
    await screen.findByLabelText('Код из Telegram')

    // следующий POST /auth/login (вызванный кнопкой «Отправить новый») падает с 429
    server.use(http.post('/api/auth/login', () =>
      HttpResponse.json({ code: 'RATE_LIMITED', message: '' }, { status: 429 })))
    // кулдаун после первой отправки — 60с, ждём его окончания
    await vi.advanceTimersByTimeAsync(60_000)
    await user.click(screen.getByRole('button', { name: /Отправить новый/ }))
    expect(await screen.findByText('Слишком часто — подожди минуту.')).toBeInTheDocument()
  } finally {
    vi.useRealTimers()
  }
})

test('«Изменить номер» сбрасывает залипшую ошибку resend — на шаге телефона её не видно', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  try {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderCard()
    await user.type(screen.getByTestId('phone-input'), '7787886432')
    await user.click(screen.getByRole('button', { name: 'Получить код' }))
    await screen.findByLabelText('Код из Telegram')

    server.use(http.post('/api/auth/login', () =>
      HttpResponse.json({ code: 'RATE_LIMITED', message: '' }, { status: 429 })))
    await vi.advanceTimersByTimeAsync(60_000)
    await user.click(screen.getByRole('button', { name: /Отправить новый/ }))
    await screen.findByText('Слишком часто — подожди минуту.')

    await user.click(screen.getByRole('button', { name: 'Изменить номер' }))
    expect(screen.getByTestId('phone-input')).toBeInTheDocument()
    expect(screen.queryByText(/подожди минуту/)).not.toBeInTheDocument()
  } finally {
    vi.useRealTimers()
  }
})

test('UNKNOWN_PHONE раскрывает форму заявки', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'UNKNOWN_PHONE', message: '' }, { status: 401 })))
  renderCard()
  await typePhone('9990009999')
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }))
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
  await typePhone('9990008888')
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }))
  await userEvent.type(await screen.findByPlaceholderText(/зовут/), 'Свой')
  await userEvent.click(screen.getByRole('button', { name: 'Отправить заявку' }))
  expect(await screen.findByText(/уже в списке — просто войди/)).toBeInTheDocument()
})

test('RATE_LIMITED показывает «подожди минуту»', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'RATE_LIMITED', message: '' }, { status: 429 })))
  renderCard()
  await typePhone('9990007777')
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }))
  expect(await screen.findByText(/подожди минуту/)).toBeInTheDocument()
})

test('повторная отправка кода сбрасывает состояние прошлой заявки', async () => {
  server.use(http.post('/api/auth/login', () =>
    HttpResponse.json({ code: 'UNKNOWN_PHONE', message: '' }, { status: 401 })))
  renderCard()

  await typePhone('9990001111')
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }))
  await userEvent.type(await screen.findByPlaceholderText(/зовут/), 'Незнакомец')
  await userEvent.click(screen.getByRole('button', { name: 'Отправить заявку' }))
  expect(await screen.findByText(/Заявка отправлена/)).toBeInTheDocument()

  const phoneInput = screen.getByTestId('phone-input')
  await userEvent.clear(phoneInput)
  await userEvent.type(phoneInput, '9990002222')
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }))

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
