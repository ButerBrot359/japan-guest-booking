// Зеркало DTO бэкенда, дословно
export type DayStatus = 'FREE' | 'BOOKED' | 'BLOCKED'
export interface CalendarDay { date: string; status: DayStatus; guestName: string | null; mine: boolean }
export interface CalendarResponse { days: CalendarDay[] }
export type BookingStatus = 'CONFIRMED' | 'CANCELLED' | 'COMPLETED'
export interface ActiveBooking { id: number; checkIn: string; checkOut: string; status: BookingStatus }
export interface Me {
  phone: string; name: string; role: 'FRIEND' | 'ADMIN'
  telegramLinked: boolean; greeting: string | null
  activeBooking: ActiveBooking | null
}
export interface CreateResult { bookingId: number }
export interface PastVisit { checkIn: string; checkOut: string; nights: number }
export interface ActiveBookingDetails extends ActiveBooking { comment: string | null }
export interface MyBookings { active: ActiveBookingDetails | null; history: PastVisit[] }

export type AccessRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export interface AdminUserRow {
  id: number; phone: string; name: string
  role: 'FRIEND' | 'ADMIN'; telegramLinked: boolean; deletedAt: string | null
  greetings: string[]
}
export interface AccessRequestRow {
  id: number; phone: string; name: string; message: string | null
  status: AccessRequestStatus; createdAt: string; resolvedAt: string | null
}
export interface AdminBookingRow {
  id: number; guestName: string; guestPhone: string
  checkIn: string   // ISO yyyy-MM-dd
  checkOut: string  // ISO, занят включительно
  status: BookingStatus; comment: string | null
}
export interface BlockedPeriodRow {
  id: number
  startDate: string  // ISO
  endDate: string    // ISO, включительно
  reason: string | null
  createdAt: string  // ISO instant
}
