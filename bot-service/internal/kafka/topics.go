package kafka

import (
	"errors"
	"net"
	"strconv"

	kafkago "github.com/segmentio/kafka-go"
)

// EnsureTopic создаёт топик детерминированно при старте, если его ещё нет.
// Раньше топики создавались лениво backend'ом при первой публикации — консьюмер,
// стартовавший раньше, молча висел без партиций и не замечал топик, появившийся
// позже (баг, найденный живым смоуком). Идемпотентно: «топик уже существует» — успех.
func EnsureTopic(brokers []string, topic string) error {
	conn, err := kafkago.Dial("tcp", brokers[0])
	if err != nil {
		return err
	}
	defer conn.Close()

	controller, err := conn.Controller()
	if err != nil {
		return err
	}

	controllerConn, err := kafkago.Dial("tcp", net.JoinHostPort(controller.Host, strconv.Itoa(controller.Port)))
	if err != nil {
		return err
	}
	defer controllerConn.Close()

	err = controllerConn.CreateTopics(kafkago.TopicConfig{
		Topic:             topic,
		NumPartitions:     1,
		ReplicationFactor: 1,
	})
	if err != nil && !errors.Is(err, kafkago.TopicAlreadyExists) {
		return err
	}
	return nil
}
