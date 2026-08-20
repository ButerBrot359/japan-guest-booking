import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import { BookingSheet } from './BookingSheet'

const base = {
  selection: { checkIn: '2026-09-10', checkOut: '2026-09-13' },
  willReplace: null, onSubmit: vi.fn(), onDismiss: vi.fn(),
  pending: false, errorCode: null as string | null,
}

test('показывает даты дд/мм/гггг и ночи', () => {
  render(<BookingSheet {...base} />)
  expect(screen.getByText(/10\/09\/2026/)).toBeInTheDocument()
  expect(screen.getByText(/3 ночи/)).toBeInTheDocument()
})

test('отправляет комментарий', async () => {
  const onSubmit = vi.fn()
  render(<BookingSheet {...base} onSubmit={onSubmit} />)
  await userEvent.type(screen.getByPlaceholderText(/Комментарий/), 'приеду с женой')
  await userEvent.click(screen.getByRole('button', { name: 'Забронировать' }))
  expect(onSubmit).toHaveBeenCalledWith('приеду с женой')
})

test('предупреждает о замене активной брони', () => {
  render(<BookingSheet {...base}
    willReplace={{ id: 4, checkIn: '2026-08-01', checkOut: '2026-08-05', status: 'CONFIRMED' }} />)
  expect(screen.getByText(/заменит твою бронь 01\/08\/2026/)).toBeInTheDocument()
})

test('DATES_TAKEN показывает «даты только что заняли»', () => {
  render(<BookingSheet {...base} errorCode="DATES_TAKEN" />)
  expect(screen.getByText(/только что заняли/)).toBeInTheDocument()
})

test('TELEGRAM_NOT_LINKED ведёт к боту', () => {
  render(<BookingSheet {...base} errorCode="TELEGRAM_NOT_LINKED" />)
  expect(screen.getByText(/привяжи Telegram/)).toBeInTheDocument()
})
