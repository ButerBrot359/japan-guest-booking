import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import type { CalendarDay } from '../api/types'
import { Calendar } from './Calendar'

function daysMap(entries: CalendarDay[]): Map<string, CalendarDay> {
  return new Map(entries.map((d) => [d.date, d]))
}

const base = {
  monthStart: '2026-09-01',
  selection: { checkIn: null, checkOut: null },
  selectable: true,
  onShiftMonth: vi.fn(),
}

test('показывает заголовки двух месяцев', () => {
  render(<Calendar {...base} days={daysMap([])} onPick={vi.fn()} />)
  expect(screen.getByText('Сентябрь 2026')).toBeInTheDocument()
  expect(screen.getByText('Октябрь 2026')).toBeInTheDocument()
})

test('занятый день показывает имя и некликабелен', async () => {
  const onPick = vi.fn()
  render(<Calendar {...base} onPick={onPick} days={daysMap([
    { date: '2026-09-10', status: 'BOOKED', guestName: 'Миша' },
  ])} />)
  const day = screen.getByRole('button', { name: /10 сентября.*занято.*Миша/i })
  expect(day).toBeDisabled()
  await userEvent.click(day)
  expect(onPick).not.toHaveBeenCalled()
})

test('заблокированный день некликабелен', () => {
  render(<Calendar {...base} onPick={vi.fn()} days={daysMap([
    { date: '2026-09-23', status: 'BLOCKED', guestName: null },
  ])} />)
  expect(screen.getByRole('button', { name: /23 сентября.*закрыто/i })).toBeDisabled()
})

test('свободный день кликабелен и зовёт onPick с ISO', async () => {
  const onPick = vi.fn()
  render(<Calendar {...base} onPick={onPick} days={daysMap([
    { date: '2026-09-15', status: 'FREE', guestName: null },
  ])} />)
  await userEvent.click(screen.getByRole('button', { name: /15 сентября.*свободно/i }))
  expect(onPick).toHaveBeenCalledWith('2026-09-15')
})

test('выбранный диапазон подсвечен', () => {
  render(<Calendar {...base} onPick={vi.fn()} days={daysMap([])}
    selection={{ checkIn: '2026-09-10', checkOut: '2026-09-13' }} />)
  expect(screen.getByRole('button', { name: /10 сентября/i })).toHaveAttribute('data-selected', 'true')
  expect(screen.getByRole('button', { name: /12 сентября/i })).toHaveAttribute('data-selected', 'true')
})

test('день из checkoutCandidates кликабелен несмотря на статус', async () => {
  const onPick = vi.fn()
  render(<Calendar {...base} onPick={onPick} checkoutCandidates={new Set(['2026-09-13'])}
    days={daysMap([{ date: '2026-09-13', status: 'BOOKED', guestName: 'Петя' }])} />)
  await userEvent.click(screen.getByRole('button', { name: /13 сентября/i }))
  expect(onPick).toHaveBeenCalledWith('2026-09-13')
})
