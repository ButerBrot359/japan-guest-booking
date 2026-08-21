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

function gotoMyBookings() {
  window.history.pushState({}, '', '/my-bookings')
  return renderApp()
}

it('нет вкладок «Активная»/«История» — активная бронь и история видны одновременно', async () => {
  loginWithBooking()
  gotoMyBookings()
  expect(await screen.findByText('Приеду с женой')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Активная' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'История' })).not.toBeInTheDocument()
  expect(document.querySelector('[aria-pressed]')).not.toBeInTheDocument()
  expect(screen.getByText('Прошлые поездки')).toBeInTheDocument()
  expect(screen.getByText(/12\/05\/2026/)).toBeInTheDocument()
  expect(screen.getByText(/7 ночей/)).toBeInTheDocument()
})

it('карточка с комментарием, редактирование сохраняет', async () => {
  loginWithBooking()
  gotoMyBookings()
  expect(await screen.findByText('Приеду с женой')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: /изменить комментарий/i }))
  const input = screen.getByLabelText(/комментарий/i)
  await userEvent.clear(input)
  await userEvent.type(input, 'Буду один')
  await userEvent.click(screen.getByRole('button', { name: /сохранить/i }))
  expect(await screen.findByText('Буду один')).toBeInTheDocument()
})

it('Перенести уводит на календарь в режиме переноса', async () => {
  loginWithBooking()
  gotoMyBookings()
  await userEvent.click(await screen.findByRole('button', { name: 'Перенести' }))
  expect(await screen.findByText(/выбери новые даты/i)).toBeInTheDocument()
})

it('отмена работает без кода — сразу подтверждение и запрос на удаление', async () => {
  loginWithBooking()
  gotoMyBookings()
  await userEvent.click(await screen.findByRole('button', { name: 'Отменить' }))
  expect(screen.queryByLabelText(/код/i)).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Да, отменить' }))
  await waitFor(() => expect(screen.queryByText(/Отменить бронь/)).not.toBeInTheDocument())
})

it('анониму прямой заход на /my-bookings недоступен — редирект на календарь', async () => {
  gotoMyBookings()
  await waitFor(() => expect(window.location.pathname).toBe('/'))
  expect(await screen.findByText(/выбери даты/i)).toBeInTheDocument()
})
