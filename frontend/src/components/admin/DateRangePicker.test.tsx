import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import type { CalendarDay } from '../../api/types'
import type { PickOptions } from '../../lib/selection'
import { type Selection } from '../Calendar'
import { DateRangePicker } from './DateRangePicker'

beforeEach(() => {
  // фиксируем дату — как в App.flow.test.tsx: 03:00Z = полдень JST
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-09-01T03:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

// в jsdom отрендерены и «мобильный», и «десктопный» календари — берём первую кнопку
const dayButton = (label: RegExp) => screen.getAllByRole('button', { name: label })[0]

const busy = (dates: string[]): Map<string, CalendarDay> =>
  new Map(dates.map((d) => [d, { date: d, status: 'BOOKED' as const, guestName: null, mine: false }]))

function Harness({ pickOptions, days }: { pickOptions?: PickOptions; days?: Map<string, CalendarDay> }) {
  const [value, setValue] = useState<Selection>({ checkIn: null, checkOut: null })
  return (
    <DateRangePicker days={days ?? new Map()} value={value} onChange={setValue}
      pickOptions={pickOptions} minMonth="2026-09-01" />
  )
}

test('два клика собирают диапазон и показывают его текстом', async () => {
  render(<Harness />)
  await userEvent.click(dayButton(/^10 сентября/))
  await userEvent.click(dayButton(/^13 сентября/))
  expect(screen.getByTestId('picker-range')).toHaveTextContent('10/09/2026 → 13/09/2026 · 3 ночи')
})

test('allowSingleDay: повторный клик по дню выбирает один день', async () => {
  render(<Harness pickOptions={{ maxNights: Infinity, allowSingleDay: true }} />)
  await userEvent.click(dayButton(/^10 сентября/))
  await userEvent.click(dayButton(/^10 сентября/))
  expect(screen.getByTestId('picker-range')).toHaveTextContent('10/09/2026')
  expect(screen.getByTestId('picker-range')).not.toHaveTextContent('→')
})

test('maxNights: Infinity — диапазон длиннее 14 ночей собирается', async () => {
  render(<Harness pickOptions={{ maxNights: Infinity }} />)
  await userEvent.click(dayButton(/^10 сентября/))
  await userEvent.click(dayButton(/^10 октября/))
  expect(screen.getByTestId('picker-range')).toHaveTextContent('10/09/2026 → 10/10/2026')
})

test('занятый день disabled и не выбирается', async () => {
  render(<Harness days={busy(['2026-09-12'])} />)
  expect(dayButton(/^12 сентября/)).toBeDisabled()
})
