package com.cloud9ers.play2.sockjs.transports

import com.cloud9ers.play2.sockjs.{ SockJsPlugin, Session, SessionManager }
import play.api.mvc.{ RequestHeader, Request, AnyContent }
import play.api.libs.iteratee.{ Iteratee, Enumerator }
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current
import akka.pattern.ask
import scala.concurrent.duration._
import akka.util.Timeout
import play.api.libs.iteratee.Concurrent
import scala.concurrent.{ Promise, Future }
import akka.actor.{ Actor, ActorRef, Props, PoisonPill }
import com.cloud9ers.play2.sockjs.SockJsFrames
import play.api.mvc.Result
import play.api.libs.EventSource
import play.api.libs.iteratee.Input

class EventSourceActor(channel: Concurrent.Channel[String], session: ActorRef) extends Actor {
  override def preStart() {
    import scala.language.postfixOps
    context.system.scheduler.scheduleOnce(100 milliseconds) {
      channel push "\r\n"
      session ! Session.Dequeue
    }
  }
  def receive: Receive = {
    case Session.Message(m) => channel push s"data: $m\r\n\r\n"; session ! Session.Dequeue
    case Session.HeartBeatFrame(h) => channel push s"data: $h\r\n\r\n"; session ! Session.Dequeue
  }
}

object EventSourceTransport extends Transport {

  def eventSource(sessionId: String)(implicit request: Request[AnyContent]) =
    Async((sessionManager ? SessionManager.GetOrCreateSession(sessionId)).map { session =>
      val (enum, channel) = Concurrent.broadcast[String]
      val eventSourceActor = system.actorOf(Props(new EventSourceActor(channel, session.asInstanceOf[ActorRef])), s"eventsource.$sessionId")
      (Ok stream enum.onDoneEnumerating(eventSourceActor ! PoisonPill))
        .withHeaders(
          CONTENT_TYPE -> "text/event-stream;charset=UTF-8",
          CACHE_CONTROL -> "no-store, no-cache, must-revalidate, max-age=0")
        .withHeaders(cors: _*)
    })
}
