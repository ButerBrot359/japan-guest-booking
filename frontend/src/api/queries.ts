import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from './client'
import type { CalendarResponse, CreateResult, Me, MyBookings } from './types'

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
  // шаг 1: только отправляет код в Telegram, куки ещё нет
  return useMutation({ mutationFn: (phone: string) => api.post<void>('/auth/login', { phone }) })
}

export function useVerifyLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { phone: string; code: string }) => api.post<void>('/auth/verify', body),
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

export function useRescheduleBooking() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ bookingId, checkIn, checkOut }: { bookingId: number; checkIn: string; checkOut: string }) =>
      api.patch<void>(`/bookings/${bookingId}`, { checkIn, checkOut }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
      qc.invalidateQueries({ queryKey: ['my-bookings'] })
    },
  })
}

export function useCancelBooking() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (bookingId: number) => api.del<void>(`/bookings/${bookingId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['calendar'] })
      qc.invalidateQueries({ queryKey: ['my-bookings'] })
    },
  })
}

export function useMyBookings() {
  return useQuery({ queryKey: ['my-bookings'], queryFn: () => api.get<MyBookings>('/me/bookings') })
}

export function useUpdateComment() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (comment: string | null) => api.patch<void>('/bookings/active', { comment }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['my-bookings'] }),
  })
}
