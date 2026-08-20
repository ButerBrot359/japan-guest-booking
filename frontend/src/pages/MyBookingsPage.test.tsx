import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { mockState } from '../test/handlers'
import { renderApp } from '../App.test'

function loginWithBooking() {
  mockState.me = { phone: '+70000000001', name: 'Маша', role: 'FRIEND',
    telegramLinked: true, greeting: null,
    activeBooking: { id: 100, checkIn: '2026-09-10', checkOut: '2026-09-14', status: 'CONFIRMED' } }
  mockState.comment = 'Приеду с женой'
  mockState.history = [{ checkIn: '2026-05-12', checkOut: '2026-05-19', nights: 7 }]
}

it('подвкладка Активная: карточка с комментарием, редактирование сохраняет', async () => {
  loginWithBooking()
  renderApp()
  await userEvent.click(await screen.findByRole('link', { name: 'Мои брони' }))
  expect(await screen.findByText('Приеду с женой')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: /изменить комментарий/i }))
  const input = screen.getByLabelText(/комментарий/i)
  await userEvent.clear(input)
  await userEvent.type(input, 'Буду один')
  await userEvent.click(screen.getByRole('button', { name: /сохранить/i }))
  expect(await screen.findByText('Буду один')).toBeInTheDocument()
})

it('подвкладка История: список поездок с ночами', async () => {
  loginWithBooking()
  renderApp()
  await userEvent.click(await screen.findByRole('link', { name: 'Мои брони' }))
  await userEvent.click(await screen.findByRole('button', { name: 'История' }))
  expect(await screen.findByText(/12\/05\/2026/)).toBeInTheDocument()
  expect(screen.getByText(/7 ночей/)).toBeInTheDocument()
})

it('Перенести уводит на календарь в режиме переноса', async () => {
  loginWithBooking()
  renderApp()
  await userEvent.click(await screen.findByRole('link', { name: 'Мои брони' }))
  await userEvent.click(await screen.findByRole('button', { name: 'Перенести' }))
  expect(await screen.findByText(/выбери новые даты/i)).toBeInTheDocument()
})

it('анониму прямой заход на /my-bookings недоступен — редирект на календарь', async () => {
  window.history.pushState({}, '', '/my-bookings')
  renderApp()
  await waitFor(() => expect(window.location.pathname).toBe('/'))
  expect(await screen.findByText(/выбери даты/i)).toBeInTheDocument()
})
