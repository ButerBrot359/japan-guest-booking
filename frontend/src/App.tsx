import { BrowserRouter, Route, Routes } from 'react-router'
import { CalendarPage } from './pages/CalendarPage'
import { MyBookingsPage } from './pages/MyBookingsPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<CalendarPage />} />
        <Route path="/my-bookings" element={<MyBookingsPage />} />
      </Routes>
    </BrowserRouter>
  )
}
