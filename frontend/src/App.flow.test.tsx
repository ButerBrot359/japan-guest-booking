import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { mockState } from './test/handlers'
import { server } from './test/setup'
import App from './App'

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

async function clickFreeDay(dayNum: string) {
  // тот же номер дня встречается в обоих отрисованных месяцах — прошедшие дни
  // задизейблены, а до загрузки /api/me задизейблено вообще всё. Ждём (с ретраями),
  // пока не появится кликабельная кнопка с нужным номером — это и есть будущий месяц.
  const target = await waitFor(() => {
    const btn = screen.getAllByRole('button', { name: /свободно/ })
      .find((b) => b.textContent === dayNum && !b.hasAttribute('disabled'))
    if (!btn) throw new Error(`день ${dayNum} пока недоступен для выбора`)
    return btn
  })
  await userEvent.click(target)
}

test('полный create-флоу: выбор дат → шторка → OTP → подтверждена', async () => {
  seedFreeSeptember()
  loginAs({})
  renderApp()
  await screen.findByText(/Привет, Маша!/)

  await clickFreeDay('10')
  await clickFreeDay('13')
  await userEvent.click(screen.getByRole('button', { name: 'Забронировать' }))

  await userEvent.type(await screen.findByLabelText('Код из Telegram'), '471523')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))

  expect(await screen.findByText('подтверждена')).toBeInTheDocument()
})

test('отмена CONFIRMED: диалог → OTP без «Отменить бронь»', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  renderApp()
  await userEvent.click(await screen.findByRole('button', { name: 'Отменить' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Да, отменить' }))
  await screen.findByLabelText('Код из Telegram')
  expect(screen.queryByRole('button', { name: 'Отменить бронь' })).not.toBeInTheDocument()
})

test('перенос: режим выбора новых дат с подсказкой', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  renderApp()
  await userEvent.click(await screen.findByRole('button', { name: 'Перенести' }))
  expect(await screen.findByText(/выбери новые даты/)).toBeInTheDocument()
})

test('полный reschedule-флоу: перенос → новые даты → OTP с «перенос» в подзаголовке → модалка закрывается', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  renderApp()

  await userEvent.click(await screen.findByRole('button', { name: 'Перенести' }))
  await clickFreeDay('11')
  await clickFreeDay('14')
  await userEvent.click(screen.getByRole('button', { name: 'Забронировать' }))

  expect(await screen.findByText(/перенос/)).toBeInTheDocument()
  await userEvent.type(await screen.findByLabelText('Код из Telegram'), '471523')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))

  await waitFor(() => expect(screen.queryByLabelText('Код из Telegram')).not.toBeInTheDocument())
})

test('смена режима сбрасывает устаревшую ошибку create и выбор дат', async () => {
  seedFreeSeptember()
  loginAs({ activeBooking: { id: 7, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  server.use(http.post('/api/bookings', () =>
    HttpResponse.json({ code: 'DATES_TAKEN', message: '' }, { status: 409 })))
  renderApp()

  await clickFreeDay('10')
  await clickFreeDay('13')
  await userEvent.click(screen.getByRole('button', { name: 'Забронировать' }))
  expect(await screen.findByText(/только что заняли/)).toBeInTheDocument()

  await userEvent.click(screen.getByRole('button', { name: 'Перенести' }))
  // в режиме переноса выбираем новые даты — селекшен снова непустой, флоу вернётся в idle
  await clickFreeDay('11')
  await clickFreeDay('14')
  await userEvent.click(screen.getByRole('button', { name: 'Передумал' }))

  expect(screen.queryByText(/только что заняли/)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Забронировать' })).not.toBeInTheDocument()
})
