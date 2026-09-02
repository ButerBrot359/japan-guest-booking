import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { expect, test } from 'vitest'
import { mockState } from '../test/handlers'
import { useAccessRequests, useAdminGuests, useAdminBookings, useAdminBlockedPeriods } from './queries'

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

test('useAdminGuests отдаёт список из API', async () => {
  mockState.adminGuests = [
    { id: 1, phone: '+79990000001', name: 'Айгуль', role: 'FRIEND', telegramLinked: true, deletedAt: null, greetings: [] },
  ]
  const { result } = renderHook(() => useAdminGuests(), { wrapper })
  await waitFor(() => expect(result.current.data?.[0].name).toBe('Айгуль'))
})

test('useAccessRequests фильтрует по статусу', async () => {
  mockState.accessRequests = [
    { id: 1, phone: '+7', name: 'A', message: null, status: 'PENDING', createdAt: 'x', resolvedAt: null },
    { id: 2, phone: '+7', name: 'B', message: null, status: 'APPROVED', createdAt: 'x', resolvedAt: 'y' },
  ]
  const { result } = renderHook(() => useAccessRequests('PENDING'), { wrapper })
  await waitFor(() => expect(result.current.data?.length).toBe(1))
  expect(result.current.data?.[0].name).toBe('A')
})

test('useAdminBookings отдаёт брони из API', async () => {
  mockState.adminBookings = [
    { id: 1, guestName: 'Маша', guestPhone: '+79990000001', checkIn: '2026-09-10',
      checkOut: '2026-09-12', status: 'CONFIRMED', comment: null },
  ]
  const { result } = renderHook(() => useAdminBookings(), { wrapper })
  await waitFor(() => expect(result.current.data?.[0].guestName).toBe('Маша'))
})

test('useAdminBlockedPeriods отдаёт периоды из API', async () => {
  mockState.blockedPeriods = [
    { id: 1, startDate: '2026-09-01', endDate: '2026-09-03', reason: 'ремонт', createdAt: '2026-08-22T00:00:00Z' },
  ]
  const { result } = renderHook(() => useAdminBlockedPeriods(), { wrapper })
  await waitFor(() => expect(result.current.data?.[0].reason).toBe('ремонт'))
})
