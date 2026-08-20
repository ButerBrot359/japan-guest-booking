import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import type { CalendarDay } from '../api/types'
import { todayIso } from '../lib/dates'
import { Calendar } from './Calendar'

function yesterdayOf(iso: string): string {
  const d = new Date(iso + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() - 1)
  return d.toISOString().slice(0, 10)
}

const noop = () => {}
const base = {
  months: ['2026-09-01', '2026-10-01'],
  selection: { checkIn: null, checkOut: null },
  selectable: true,
  onShiftMonth: vi.fn(),
  onPick: noop,
}

/** Дефолтный CalendarDay с точечными переопределениями — для краткости в тестах. */
const day = (date: string, extra: Partial<CalendarDay> = {}): [string, CalendarDay] =>
  [date, { date, status: 'FREE', guestName: null, mine: false, ...extra }]

test('показывает заголовки двух месяцев', () => {
  render(<Calendar {...base} days={new Map([])} />)
  expect(screen.getByText('Сентябрь 2026')).toBeInTheDocument()
  expect(screen.getByText('Октябрь 2026')).toBeInTheDocument()
})

test('занятый день показывает имя и некликабелен', async () => {
  const onPick = vi.fn()
  render(<Calendar {...base} onPick={onPick}
    days={new Map([day('2026-09-10', { status: 'BOOKED', guestName: 'Миша' })])} />)
  const el = screen.getByRole('button', { name: /10 сентября.*занято.*Миша/i })
  expect(el).toBeDisabled()
  await userEvent.click(el)
  expect(onPick).not.toHaveBeenCalled()
})

test('заблокированный день некликабелен', () => {
  render(<Calendar {...base} days={new Map([day('2026-09-23', { status: 'BLOCKED' })])} />)
  expect(screen.getByRole('button', { name: /23 сентября.*закрыто/i })).toBeDisabled()
})

test('свободный день кликабелен и зовёт onPick с ISO', async () => {
  const onPick = vi.fn()
  render(<Calendar {...base} onPick={onPick} days={new Map([day('2026-09-15')])} />)
  await userEvent.click(screen.getByRole('button', { name: /15 сентября.*свободно/i }))
  expect(onPick).toHaveBeenCalledWith('2026-09-15')
})

test('выбранный диапазон подсвечен', () => {
  render(<Calendar {...base} days={new Map([])}
    selection={{ checkIn: '2026-09-10', checkOut: '2026-09-13' }} />)
  expect(screen.getByRole('button', { name: /10 сентября/i })).toHaveAttribute('data-selected', 'true')
  expect(screen.getByRole('button', { name: /12 сентября/i })).toHaveAttribute('data-selected', 'true')
})

test('прошедший день недоступен для выбора, даже если он FREE', () => {
  const yesterday = yesterdayOf(todayIso())
  const monthStart = yesterday.slice(0, 7) + '-01'
  const dayNum = String(Number(yesterday.slice(8)))
  render(<Calendar {...base} months={[monthStart]} days={new Map([])} />)
  const target = screen.getAllByRole('button', { name: /свободно/ })
    .find((b) => b.textContent === dayNum)
  expect(target).toBeDisabled()
})

test('день из checkoutCandidates кликабелен несмотря на статус', async () => {
  const onPick = vi.fn()
  render(<Calendar {...base} onPick={onPick} checkoutCandidates={new Set(['2026-09-13'])}
    days={new Map([day('2026-09-13', { status: 'BOOKED', guestName: 'Петя' })])} />)
  await userEvent.click(screen.getByRole('button', { name: /13 сентября/i }))
  expect(onPick).toHaveBeenCalledWith('2026-09-13')
})

test('свой день зелёный и есть в легенде', () => {
  render(<Calendar {...base} days={new Map([day('2026-09-10', { status: 'BOOKED', mine: true })])} />)
  expect(screen.getByRole('button', { name: /10 сентября/i }).className).toContain('bg-leaf')
  expect(screen.getByText('твоя')).toBeInTheDocument()
})

test('чужой занятый день с именем кликабелен и зовёт onPickBusy', async () => {
  const onPickBusy = vi.fn()
  render(<Calendar {...base} onPickBusy={onPickBusy}
    days={new Map([day('2026-09-10', { status: 'BOOKED', guestName: 'Миша' })])} />)
  await userEvent.click(screen.getByRole('button', { name: /10 сентября/i }))
  expect(onPickBusy).toHaveBeenCalledWith('2026-09-10')
})

test('чужой занятый день без имени (аноним) остаётся заблокированным', () => {
  render(<Calendar {...base} days={new Map([day('2026-09-10', { status: 'BOOKED' })])} />)
  expect(screen.getByRole('button', { name: /10 сентября/i })).toBeDisabled()
})

test('дни дальше maxCheckout заблокированы', () => {
  render(<Calendar {...base} maxCheckout="2026-09-15"
    selection={{ checkIn: '2026-09-01', checkOut: null }} days={new Map()} />)
  expect(screen.getByRole('button', { name: /16 сентября/i })).toBeDisabled()
  expect(screen.getByRole('button', { name: /15 сентября/i })).toBeEnabled()
})
