import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { mockState } from '../../test/handlers'
import { AdminAccessRequests } from './AdminAccessRequests'

function renderSection() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><AdminAccessRequests /></QueryClientProvider>)
}

test('показывает ожидающие заявки', async () => {
  mockState.accessRequests = [
    { id: 1, phone: '+79990000001', name: 'Незнакомец', message: 'друг Миши', status: 'PENDING', createdAt: 'x', resolvedAt: null },
  ]
  renderSection()
  expect(await screen.findByText(/Незнакомец/)).toBeInTheDocument()
  expect(screen.getByText(/друг Миши/)).toBeInTheDocument()
})

test('кнопка Добавить убирает заявку из ожидающих', async () => {
  mockState.accessRequests = [
    { id: 1, phone: '+7', name: 'Незнакомец', message: null, status: 'PENDING', createdAt: 'x', resolvedAt: null },
  ]
  renderSection()
  await userEvent.click(await screen.findByRole('button', { name: 'Добавить' }))
  await waitFor(() => expect(screen.queryByText(/Незнакомец/)).not.toBeInTheDocument())
})

test('переключение на Историю показывает одобренные', async () => {
  mockState.accessRequests = [
    { id: 2, phone: '+7', name: 'Одобренный', message: null, status: 'APPROVED', createdAt: 'x', resolvedAt: 'y' },
  ]
  renderSection()
  await userEvent.click(screen.getByRole('button', { name: 'История' }))
  expect(await screen.findByText(/Одобренный/)).toBeInTheDocument()
})
