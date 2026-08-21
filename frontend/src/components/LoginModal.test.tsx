import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import { LoginModal } from './LoginModal'

function renderModal() {
  const onClose = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <LoginModal open title="Вход" onClose={onClose} />
    </QueryClientProvider>,
  )
  return onClose
}

test('крестик закрывает модалку', async () => {
  const onClose = renderModal()
  await userEvent.click(screen.getByRole('button', { name: 'Закрыть' }))
  expect(onClose).toHaveBeenCalled()
})

// на мобиле click при сдвиге лейаута (клавиатура) диспатчится в подложку,
// даже когда пользователь целился в карточку — подложка закрывать не должна
test('клик по подложке не закрывает модалку', async () => {
  const onClose = renderModal()
  await userEvent.click(screen.getByTestId('login-backdrop'))
  expect(onClose).not.toHaveBeenCalled()
})
