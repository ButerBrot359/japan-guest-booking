// bot-service: мост Telegram ↔ Kafka. Бизнес-логики нет — только доставка
// уведомлений и трансляция действий пользователя в события.
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/kafka"
	"github.com/buterbrot359/japan-guest-booking/bot-service/internal/telegram"
)

func main() {
	token := os.Getenv("BOT_TOKEN")
	if token == "" {
		log.Fatal("BOT_TOKEN не задан")
	}
	brokers := strings.Split(envOr("KAFKA_BROKERS", "localhost:9092"), ",")

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	client := telegram.NewClient(token, "https://api.telegram.org")
	producer := kafka.NewProducer(brokers)
	defer producer.Close()
	consumer := kafka.NewConsumer(brokers, client)
	defer consumer.Close()
	poller := telegram.NewPoller(client, producer)

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); poller.Run(ctx) }()
	go func() {
		defer wg.Done()
		for ctx.Err() == nil {
			if err := kafka.EnsureTopic(brokers, "notifications.outbound"); err != nil {
				log.Printf("ensure topic: %v — повтор через 3с", err)
				select {
				case <-time.After(3 * time.Second):
					continue
				case <-ctx.Done():
					return
				}
			}
			break
		}
		if ctx.Err() == nil {
			consumer.Run(ctx)
		}
	}()
	log.Println("bot-service запущен; Ctrl+C для остановки")
	wg.Wait()
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
