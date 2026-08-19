// Package telegram — тонкий клиент Telegram Bot API: getUpdates + sendMessage.
package telegram

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"
)

type Update struct {
	UpdateID int64    `json:"update_id"`
	Message  *Message `json:"message"`
}

type Message struct {
	Chat    Chat     `json:"chat"`
	From    *User    `json:"from"`
	Text    string   `json:"text"`
	Contact *Contact `json:"contact"`
}

type Chat struct {
	ID int64 `json:"id"`
}

type User struct {
	ID       int64  `json:"id"`
	Username string `json:"username"`
}

type Contact struct {
	PhoneNumber string `json:"phone_number"`
	UserID      int64  `json:"user_id"`
}

// API — то, что нужно поллеру и консьюмеру; Client её реализует.
type API interface {
	GetUpdates(ctx context.Context, offset int64) ([]Update, error)
	SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error
}

type Client struct {
	base string
	http *http.Client
}

// NewClient: baseURL в проде — "https://api.telegram.org", в тестах — httptest.
func NewClient(token, baseURL string) *Client {
	return &Client{
		base: baseURL + "/bot" + token,
		// long poll 30с + запас; таймаут больше poll-таймаута обязателен
		http: &http.Client{Timeout: 65 * time.Second},
	}
}

type apiResponse struct {
	OK          bool            `json:"ok"`
	Description string          `json:"description"`
	Result      json.RawMessage `json:"result"`
}

func (c *Client) GetUpdates(ctx context.Context, offset int64) ([]Update, error) {
	q := url.Values{}
	q.Set("timeout", "30")
	q.Set("offset", fmt.Sprint(offset))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		c.base+"/getUpdates?"+q.Encode(), nil)
	if err != nil {
		return nil, err
	}
	var updates []Update
	if err := c.do(req, &updates); err != nil {
		return nil, err
	}
	return updates, nil
}

func (c *Client) SendMessage(ctx context.Context, chatID int64, text string, requestContact bool) error {
	body := map[string]any{"chat_id": chatID, "text": text}
	if requestContact {
		body["reply_markup"] = map[string]any{
			"keyboard": [][]map[string]any{{
				{"text": "Поделиться контактом", "request_contact": true},
			}},
			"resize_keyboard":   true,
			"one_time_keyboard": true,
		}
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.base+"/sendMessage", bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	return c.do(req, nil)
}

func (c *Client) do(req *http.Request, result any) error {
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var api apiResponse
	if err := json.NewDecoder(resp.Body).Decode(&api); err != nil {
		return fmt.Errorf("telegram: битый ответ: %w", err)
	}
	if !api.OK {
		return fmt.Errorf("telegram: %s", api.Description)
	}
	if result != nil {
		return json.Unmarshal(api.Result, result)
	}
	return nil
}
