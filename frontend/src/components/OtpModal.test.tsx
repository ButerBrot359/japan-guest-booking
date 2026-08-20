import type { ComponentProps } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { vi } from 'vitest'
import { server } from '../test/setup'
import { OtpModal } from './OtpModal'

function renderModal(props: Partial<ComponentProps<typeof OtpModal>> = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onDone = vi.fn(), onClose = vi.fn()
  render(
    <QueryClientProvider client={qc}>
      <OtpModal bookingId={100} subtitle="заезд 10/09/2026 → выезд 13/09/2026"
        showCancelPending onDone={onDone} onClose={onClose} {...props} />
    </QueryClientProvider>,
  )
  return { onDone, onClose }
}

test('успешное подтверждение зовёт onDone', async () => {
  const { onDone } = renderModal()
  await userEvent.type(screen.getByLabelText('Код из Telegram'), '471523')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
  expect(onDone).toHaveBeenCalled()
})

test('INVALID_CODE показывает «Неверный код»', async () => {
  server.use(http.post('/api/bookings/100/confirm', () =>
    HttpResponse.json({ code: 'INVALID_CODE', message: '' }, { status: 400 })))
  renderModal()
  await userEvent.type(screen.getByLabelText('Код из Telegram'), '000000')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
  expect(await screen.findByText('Неверный код')).toBeInTheDocument()
})

test('CODE_EXPIRED предлагает новый код', async () => {
  server.use(http.post('/api/bookings/100/confirm', () =>
    HttpResponse.json({ code: 'CODE_EXPIRED', message: '' }, { status: 400 })))
  renderModal()
  await userEvent.type(screen.getByLabelText('Код из Telegram'), '000000')
  await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
  expect(await screen.findByText(/Код сгорел/)).toBeInTheDocument()
})

test('resend заблокирован таймером сразу после открытия', () => {
  renderModal()
  expect(screen.getByRole('button', { name: /Отправить новый/ })).toBeDisabled()
})

test('«Отменить бронь» дёргает DELETE /pending и закрывает', async () => {
  let deleted = false
  server.use(http.delete('/api/bookings/pending', () => {
    deleted = true
    return new HttpResponse(null, { status: 204 })
  }))
  const { onClose } = renderModal()
  await userEvent.click(screen.getByRole('button', { name: 'Отменить бронь' }))
  expect(deleted).toBe(true)
  expect(onClose).toHaveBeenCalled()
})

test('без showCancelPending кнопки отмены нет', () => {
  renderModal({ showCancelPending: false })
  expect(screen.queryByRole('button', { name: 'Отменить бронь' })).not.toBeInTheDocument()
})
