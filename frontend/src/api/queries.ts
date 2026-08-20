import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from './client'
import type { CalendarResponse, CreateResult, Me } from './types'

export function useCalendar(fromIso: string, toIso: string) {
  return useQuery({
    queryKey: ['calendar', fromIso, toIso],
    queryFn: () => api.get<CalendarResponse>(`/calendar?from=${fromIso}&to=${toIso}`),
  })
}

export function useMe() {
  return useQuery({
    queryKey: ['me'],
    // 401 = «не залогинен» — это данные (null), а не ошибка
    queryFn: () =>
      api.get<Me>('/me').catch((e) => {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }),
  })
}

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (phone: string) => api.post<void>('/auth/login', { phone }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me'] }),
  })
}

export function useLogout() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/auth/logout'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me'] }),
  })
}

export function useAccessRequest() {
  return useMutation({
    mutationFn: (body: { phone: string; name: string; message?: string }) =>
      api.post<void>('/access-requests', body),
  })
}

export function useCreateBooking() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { checkIn: string; checkOut: string; comment?: string }) =>
      api.post<CreateResult>('/bookings', body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
    },
  })
}

export function useConfirmBooking() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ bookingId, code }: { bookingId: number; code: string }) =>
      api.post<void>(`/bookings/${bookingId}/confirm`, { code }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
    },
  })
}

export function useResendCode() {
  return useMutation({
    mutationFn: (bookingId: number) => api.post<void>(`/bookings/${bookingId}/resend-code`),
  })
}

export function useRescheduleBooking() {
  return useMutation({
    mutationFn: ({ bookingId, checkIn, checkOut }: { bookingId: number; checkIn: string; checkOut: string }) =>
      api.patch<void>(`/bookings/${bookingId}`, { checkIn, checkOut }),
  })
}

export function useCancelBooking() {
  return useMutation({
    mutationFn: (bookingId: number) => api.del<void>(`/bookings/${bookingId}`),
  })
}

export function useCancelPending() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.del<void>('/bookings/pending'),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
    },
  })
}
