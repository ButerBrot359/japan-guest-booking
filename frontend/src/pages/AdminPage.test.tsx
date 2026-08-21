import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { expect, test } from 'vitest'
import { mockState } from '../test/handlers'
import { AdminPage } from './AdminPage'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><AdminPage /></MemoryRouter>
    </QueryClientProvider>,
  )
}

test('аноним видит форму входа админа', async () => {
  renderPage()
  expect(await screen.findByLabelText('Пароль')).toBeInTheDocument()
})

test('гость (не админ) видит форму входа, не панель', async () => {
  mockState.me = { phone: '+7', name: 'Маша', role: 'FRIEND', telegramLinked: true, greeting: null, activeBooking: null }
  renderPage()
  expect(await screen.findByLabelText('Пароль')).toBeInTheDocument()
})

test('вход по паролю открывает панель с вкладками', async () => {
  renderPage()
  await userEvent.type(await screen.findByTestId('admin-phone'), '8000000000')
  await userEvent.type(await screen.findByLabelText('Пароль'), 'admin')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  expect(await screen.findByRole('button', { name: 'Заявки' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Гости' })).toBeInTheDocument()
})

test('неверный пароль показывает ошибку', async () => {
  renderPage()
  await userEvent.type(await screen.findByTestId('admin-phone'), '8000000000')
  await userEvent.type(await screen.findByLabelText('Пароль'), 'wrong')
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }))
  expect(await screen.findByText(/неверный телефон или пароль/i)).toBeInTheDocument()
})

test('уже вошедший админ сразу видит панель', async () => {
  mockState.me = { phone: '+7', name: 'Админ', role: 'ADMIN', telegramLinked: true, greeting: null, activeBooking: null }
  renderPage()
  expect(await screen.findByRole('button', { name: 'Заявки' })).toBeInTheDocument()
})
