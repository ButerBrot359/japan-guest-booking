import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, vi } from 'vitest'
import { mockState } from './test/handlers'
import { server } from './test/setup'
import App from './App'

beforeEach(() => {
  // фиксируем дату — дизейбл прошедших дней не должен зависеть от дня запуска CI
  // (иначе пары дней 10/13 и 11/14 «на грани» ломают клики флоу-тестов).
  // 03:00Z = полдень JST, безопасно от границ суток
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-09-01T03:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

function renderApp() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><App /></QueryClientProvider>)
}

function seedFreeSeptember() {
  // календарь мока отдаёт только НЕ-свободные дни; свободные фронт достраивает сам
  mockState.days = []
}

function loginAs(me: Partial<typeof mockState.me>) {
  mockState.me = {
    phone: '+79990001122', name: 'Маша', role: 'FRIEND', telegramLinked: true,
    greeting: null, activeBooking: null, ...me,
  } as NonNullable<typeof mockState.me>
}

// Оба номера дня пары (чек-ин/чек-аут) встречаются в обоих отрисованных месяцах,
// а до загрузки /api/me клик по дню просто игнорируется (см. handlePick в
// CalendarPage — гейт на me.isLoading). Если искать каждый день по
// отдельности «первым кликабельным совпадением», пара может расползтись по
// разным месяцам (например «10» из будущего месяца, а «13» — из текущего,
// потому что текущий уже не задизейблен на этот номер) — получим невалидный
// чек-аут раньше чек-ина. Поэтому ищем ОДИН месяц, где кликабельны ОБА дня
// пары, и кликаем их там по очереди — с ретраями до загрузки /api/me.
async function clickFreeDayPair([first, second]: [string, string]) {
  const [firstBtn, secondBtn] = await waitFor(() => {
    const months = screen.getAllByText(/^[А-ЯЁ][а-яё]+ \d{4}$/)
      .map((title) => title.closest('.mb-4')).filter((el): el is HTMLElement => el != null)
    for (const month of months) {
      const buttons = within(month).getAllByRole('button', { name: /свободно/ })
      const a = buttons.find((b) => b.textContent === first && !b.hasAttribute('disabled'))
      const b = buttons.find((b) => b.textContent === second && !b.hasAttribute('disabled'))
      if (a && b) return [a, b] as const
    }
    throw new Error(`пара дней ${first}/${second} пока недоступна целиком ни в одном месяце`)
  })
  await userEvent.click(firstBtn)
  await userEvent.click(secondBtn)
}

test('полный create-флоу: выбор дат → шторка → OTP → подтверждена', async () => {
  seedFreeSeptember()
  loginAs({})
  renderApp()
  await screen.findByText(/Привет, Маша!/)

  await clickFreeDayPair(['10', '13'])
  await userEvent.click(screen.getByRole('button', { name: 'Забронировать' }))

  await userEvent.type(await screen.findByLabelText('Код из Telegram'), '471523')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))

  expect(await screen.findByText(/мы очень вас ждём/i)).toBeInTheDocument()
  expect(screen.getByText(/свяжемся с вами/i)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Хорошо' }))
  expect(await screen.findByText('подтверждена')).toBeInTheDocument()
})

test('отмена CONFIRMED: диалог → OTP без «Отменить бронь», без тёплого экрана', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  renderApp()
  await userEvent.click(await screen.findByRole('button', { name: 'Отменить' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Да, отменить' }))
  await screen.findByLabelText('Код из Telegram')
  expect(screen.queryByRole('button', { name: 'Отменить бронь' })).not.toBeInTheDocument()

  await userEvent.type(screen.getByLabelText('Код из Telegram'), '471523')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
  await waitFor(() => expect(screen.queryByLabelText('Код из Telegram')).not.toBeInTheDocument())
  expect(screen.queryByText(/мы очень вас ждём/i)).not.toBeInTheDocument()
})

test('перенос: режим выбора новых дат с подсказкой', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  renderApp()
  await userEvent.click(await screen.findByRole('button', { name: 'Перенести' }))
  expect(await screen.findByText(/выбери новые даты/)).toBeInTheDocument()
})

test('полный reschedule-флоу: перенос → новые даты → OTP с «перенос» в подзаголовке → тёплый экран → подтверждена', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  renderApp()

  await userEvent.click(await screen.findByRole('button', { name: 'Перенести' }))
  await clickFreeDayPair(['11', '14'])
  await userEvent.click(screen.getByRole('button', { name: 'Забронировать' }))

  expect(await screen.findByText(/перенос/)).toBeInTheDocument()
  await userEvent.type(await screen.findByLabelText('Код из Telegram'), '471523')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))

  expect(await screen.findByText(/мы очень вас ждём/i)).toBeInTheDocument()
  await waitFor(() => expect(screen.queryByLabelText('Код из Telegram')).not.toBeInTheDocument())
  await userEvent.click(screen.getByRole('button', { name: 'Хорошо' }))
  expect(await screen.findByText('подтверждена')).toBeInTheDocument()
})

test('аноним кликает дату → модалка логина → после входа дата уже выбрана', async () => {
  seedFreeSeptember()
  renderApp()
  const day = (await screen.findAllByRole('button', { name: /10 сентября.*свободно/i }))[0]
  await userEvent.click(day)
  expect(await screen.findByText(/войдите, чтобы выбрать даты/i)).toBeInTheDocument()
  await userEvent.type(screen.getByTestId('phone-input'), '7787886432')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  await waitFor(() =>
    expect(screen.queryByText(/войдите, чтобы выбрать даты/i)).not.toBeInTheDocument())
  expect((await screen.findAllByRole('button', { name: /10 сентября/i }))[0])
    .toHaveAttribute('data-selected')
})

test('смена режима сбрасывает устаревшую ошибку create и выбор дат', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  server.use(http.post('/api/bookings', () =>
    HttpResponse.json({ code: 'DATES_TAKEN', message: '' }, { status: 409 })))
  renderApp()

  await clickFreeDayPair(['10', '13'])
  await userEvent.click(screen.getByRole('button', { name: 'Забронировать' }))
  expect(await screen.findByText(/только что заняли/)).toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Перенести' }))
  // в режиме переноса выбираем новые даты — селекшен снова непустой, флоу вернётся в idle
  await clickFreeDayPair(['11', '14'])
  await userEvent.click(screen.getByRole('button', { name: 'Передумал' }))

  expect(screen.queryByText(/только что заняли/)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Забронировать' })).not.toBeInTheDocument()
})
