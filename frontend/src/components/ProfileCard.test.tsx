import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { vi } from 'vitest'
import type { Me } from '../api/types'
import { ProfileCard } from './ProfileCard'

const base: Me = {
  phone: '+79990001122', name: 'Аня', role: 'FRIEND',
  telegramLinked: true, greeting: null, activeBooking: null,
}
const noop = { onReschedule: vi.fn(), onCancel: vi.fn(), onEnterCode: vi.fn(), onCancelPending: vi.fn() }

function renderCard(me: Me) {
  const qc = new QueryClient()
  return render(<QueryClientProvider client={qc}><ProfileCard me={me} {...noop} /></QueryClientProvider>)
}

test('фолбэк «Привет, Имя!» без кастомного приветствия', () => {
  renderCard(base)
  expect(screen.getByText('Привет, Аня!')).toBeInTheDocument()
  expect(screen.getByText(/выбери даты в календаре/)).toBeInTheDocument()
})

test('кастомное приветствие вместо фолбэка', () => {
  renderCard({ ...base, greeting: 'Мишаня! Футон проветрен' })
  expect(screen.getByText('Мишаня! Футон проветрен')).toBeInTheDocument()
  expect(screen.queryByText(/Привет,/)).not.toBeInTheDocument()
})

test('CONFIRMED-бронь: даты дд/мм/гггг, бейдж, кнопки', () => {
  renderCard({ ...base, activeBooking: { id: 5, checkIn: '2026-09-10', checkOut: '2026-09-13', status: 'CONFIRMED' } })
  expect(screen.getByText(/10\/09\/2026/)).toBeInTheDocument()
  expect(screen.getByText('подтверждена')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Перенести' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Отменить' })).toBeInTheDocument()
})

test('PENDING_OTP-бронь: бейдж «ждёт код» и свои кнопки', () => {
  renderCard({ ...base, activeBooking: { id: 6, checkIn: '2026-10-01', checkOut: '2026-10-03', status: 'PENDING_OTP' } })
  expect(screen.getByText('ждёт код')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Ввести код' })).toBeInTheDocument()
})
