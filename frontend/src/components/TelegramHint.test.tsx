import { render, screen } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { TelegramHint } from './TelegramHint'

afterEach(() => {
  vi.unstubAllEnvs()
})

test('с заданной VITE_BOT_URL — ссылка «напиши боту» с href', () => {
  vi.stubEnv('VITE_BOT_URL', 'https://t.me/x')
  render(<TelegramHint />)
  const link = screen.getByRole('link', { name: 'напиши боту' })
  expect(link).toHaveAttribute('href', 'https://t.me/x')
  expect(link).toHaveAttribute('target', '_blank')
})

test('без VITE_BOT_URL — просто текст, ссылки нет', () => {
  render(<TelegramHint />)
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
  expect(screen.getByText(/напиши боту/)).toBeInTheDocument()
})
