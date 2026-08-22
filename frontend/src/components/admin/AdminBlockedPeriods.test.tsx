import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { mockState } from '../../test/handlers'
import { AdminBlockedPeriods } from './AdminBlockedPeriods'

function renderSection() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><AdminBlockedPeriods /></QueryClientProvider>)
}

// в jsdom отрендерены оба календаря (мобильный и десктопный) — берём первую кнопку
const dayButton = (label: RegExp) => screen.getAllByRole('button', { name: label })[0]

test('список показывает периоды и причину', async () => {
  mockState.blockedPeriods = [
    { id: 1, startDate: '2026-09-01', endDate: '2026-09-03', reason: 'ремонт', createdAt: '2026-08-22T00:00:00Z' },
  ]
  renderSection()
  expect(await screen.findByText(/01\/09\/2026 → 03\/09\/2026/)).toBeInTheDocument()
  expect(screen.getByText('ремонт')).toBeInTheDocument()
})

test('пустое состояние', async () => {
  renderSection()
  expect(await screen.findByText('Закрытых дат нет')).toBeInTheDocument()
})

test('создание диапазона добавляет период в список', async () => {
  renderSection()
  await screen.findByText('Закрытых дат нет')
  await userEvent.click(dayButton(/^10 сентября/))
  await userEvent.click(dayButton(/^13 сентября/))
  await userEvent.type(screen.getByLabelText('Причина'), 'личная поездка')
  await userEvent.click(screen.getByRole('button', { name: 'Закрыть даты' }))
  expect(await screen.findByText(/10\/09\/2026 → 13\/09\/2026/)).toBeInTheDocument()
  expect(screen.getByText('личная поездка')).toBeInTheDocument()
})

test('создание одиночного дня (startDate === endDate)', async () => {
  renderSection()
  await screen.findByText('Закрытых дат нет')
  await userEvent.click(dayButton(/^10 сентября/))
  await userEvent.click(dayButton(/^10 сентября/))
  await userEvent.click(screen.getByRole('button', { name: 'Закрыть даты' }))
  await waitFor(() => expect(mockState.blockedPeriods[0]).toMatchObject({
    startDate: '2026-09-10', endDate: '2026-09-10',
  }))
})

test('удаление после подтверждения убирает период', async () => {
  mockState.blockedPeriods = [
    { id: 1, startDate: '2026-09-01', endDate: '2026-09-03', reason: null, createdAt: '2026-08-22T00:00:00Z' },
  ]
  renderSection()
  await userEvent.click(await screen.findByRole('button', { name: 'Удалить' }))
  await userEvent.click(screen.getByRole('button', { name: 'Да, удалить' }))
  expect(await screen.findByText('Закрытых дат нет')).toBeInTheDocument()
})
