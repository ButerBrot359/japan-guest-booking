import { render, screen } from '@testing-library/react'
import App from './App'

test('рендерит шапку', () => {
  render(<App />)
  expect(screen.getByText(/Домик в Японии/)).toBeInTheDocument()
})
