import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import { mockState } from '../../test/handlers'
import { AdminGuests } from './AdminGuests'

function renderSection() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={qc}><AdminGuests /></QueryClientProvider>)
}

test('показывает список гостей', async () => {
  mockState.adminGuests = [
    { id: 1, phone: '+79990000001', name: 'Айгуль', role: 'FRIEND', telegramLinked: true, deletedAt: null },
  ]
  renderSection()
  expect(await screen.findByText('Айгуль')).toBeInTheDocument()
})

test('добавление гостя обновляет список', async () => {
  renderSection()
  await userEvent.type(screen.getByTestId('add-phone'), '7001234567')
  await userEvent.type(screen.getByLabelText('Имя'), 'Батыр')
  await userEvent.click(screen.getByRole('button', { name: 'Добавить гостя' }))
  expect(await screen.findByText('Батыр')).toBeInTheDocument()
})

test('добавление существующего показывает ошибку', async () => {
  mockState.adminGuests = [
    { id: 1, phone: '+77001234567', name: 'Есть', role: 'FRIEND', telegramLinked: false, deletedAt: null },
  ]
  renderSection()
  await userEvent.type(screen.getByTestId('add-phone'), '7001234567')
  await userEvent.type(screen.getByLabelText('Имя'), 'Дубль')
  await userEvent.click(screen.getByRole('button', { name: 'Добавить гостя' }))
  expect(await screen.findByText(/уже в списке/i)).toBeInTheDocument()
})

test('удаление гостя после подтверждения', async () => {
  mockState.adminGuests = [
    { id: 1, phone: '+79990000001', name: 'Удаляемый', role: 'FRIEND', telegramLinked: false, deletedAt: null },
  ]
  renderSection()
  await userEvent.click(await screen.findByRole('button', { name: 'Удалить' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Да, удалить' }))
  await waitFor(() => expect(screen.getByText(/удалён/i)).toBeInTheDocument())
})

test('редактор приветствий грузит текущие и сохраняет', async () => {
  mockState.adminGuests = [
    { id: 1, phone: '+79990000001', name: 'Гость', role: 'FRIEND', telegramLinked: false, deletedAt: null },
  ]
  mockState.guestGreetings = { 1: ['Привет!'] }
  renderSection()
  await userEvent.click(await screen.findByRole('button', { name: 'Приветствия' }))
  expect(await screen.findByDisplayValue('Привет!')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Добавить строку' }))
  const inputs = screen.getAllByLabelText(/приветствие/i)
  await userEvent.type(inputs[inputs.length - 1], 'С приездом!')
  await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
  await waitFor(() => expect(mockState.guestGreetings[1]).toContain('С приездом!'))
})
