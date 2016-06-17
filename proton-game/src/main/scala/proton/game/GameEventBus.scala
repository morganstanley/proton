package proton.game

import java.util.UUID

import akka.actor.ActorRef
import akka.event.{EventBus, LookupClassification}

class GameEventBus extends EventBus with LookupClassification {
  type Event = Envelope
  type Classifier = UUID
  type Subscriber = ActorRef

  override protected def classify(event: Event): Classifier = event.id

  override protected def publish(event: Event, subscriber: Subscriber): Unit = {
    subscriber ! event.message
  }

  override protected def compareSubscribers(a: Subscriber, b: Subscriber): Int =
    a.compareTo(b)

  override protected def mapSize: Int = 1024
}