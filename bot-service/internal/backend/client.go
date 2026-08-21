// Package backend — синхронный HTTP-клиент к бэкенду для read-only данных гостя.
package backend

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

type Booking struct {
	CheckIn  string `json:"checkIn"`
	CheckOut string `json:"checkOut"`
	Status   string `json:"status"`
}

type Visit struct {
	CheckIn  string `json:"checkIn"`
	CheckOut string `json:"checkOut"`
	Nights   int    `json:"nights"`
}

type GuestBookings struct {
	Linked  bool     `json:"linked"`
	Active  *Booking `json:"active"`
	History []Visit  `json:"history"`
}

type Client struct {
	base  string
	token string
	http  *http.Client
}

func NewClient(baseURL, token string) *Client {
	return &Client{base: baseURL, token: token, http: &http.Client{Timeout: 10 * time.Second}}
}

func (c *Client) GetGuestBookings(ctx context.Context, chatID int64) (GuestBookings, error) {
	q := url.Values{}
	q.Set("chat_id", strconv.FormatInt(chatID, 10))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		c.base+"/api/bot/bookings?"+q.Encode(), nil)
	if err != nil {
		return GuestBookings{}, err
	}
	req.Header.Set("X-Bot-Token", c.token)
	resp, err := c.http.Do(req)
	if err != nil {
		return GuestBookings{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return GuestBookings{}, fmt.Errorf("backend: статус %d", resp.StatusCode)
	}
	var gb GuestBookings
	if err := json.NewDecoder(resp.Body).Decode(&gb); err != nil {
		return GuestBookings{}, err
	}
	return gb, nil
}

// ResolveAccessRequest одобряет/отклоняет заявку; возвращает HTTP-статус бэкенда
// (204/403/404/409) для маршрутизации ответа боту, либо ошибку сети.
func (c *Client) ResolveAccessRequest(ctx context.Context, id int64, action string, adminChatID int64) (int, error) {
	payload, err := json.Marshal(map[string]any{"adminChatId": adminChatID})
	if err != nil {
		return 0, err
	}
	url := c.base + "/api/bot/access-requests/" + strconv.FormatInt(id, 10) + "/" + action
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return 0, err
	}
	req.Header.Set("X-Bot-Token", c.token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	return resp.StatusCode, nil
}
