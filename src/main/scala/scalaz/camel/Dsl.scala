/*
 * Copyright 2010-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package scalaz.camel

import java.util.concurrent.{BlockingQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import org.apache.camel.{Exchange, AsyncCallback, AsyncProcessor}

import scalaz._
import Scalaz._
import Message._

import concurrent.Promise
import concurrent.Strategy

/**
 * Enterprise integration patterns (EIPs) that can be composed via Kleisli composition (>=>).
 *
 * @author Martin Krasser
 */
trait DslEip extends Conv {

  /**
   * Name of the position message header needed by scatter-gather. Needed to
   * preserve the order of messages that are distributed to destinations.
   */
  val Position = "scalaz.camel.multicast.position"

  /**
   * Concurrency strategy for distributing messages to destinations
   * with the multicast and scatter-gather EIPs.
   */
  protected def multicastStrategy: Strategy

  /**
   * Converts messages to one-way messages.
   */
  def oneway = responderKleisli(messageProcessor { m: Message => m.setOneway(true) } )

  /**
   * Converts messages to two-way messages.
   */
  def twoway = responderKleisli(messageProcessor { m: Message => m.setOneway(false) } )

  /**
   * Does routing based on pattern matching of messages. Implements the content-based
   * router EIP.
   */
  def choose(f: PartialFunction[Message, MessageValidationResponderKleisli]): MessageValidationResponderKleisli = responderKleisli {
    (m: Message, k: MessageValidation => Unit) => {
      f.lift(m) match {
        case Some(r) => messageProcessor(r)(m, k)
        case None    => k(m.success)
      }
    }
  }


  /**
   * Distributes messages to given destinations. Applies the concurrency strategy
   * returned by <code>multicastStrategy</code> to distribute messages. Distributed
   * messages are not combined, instead n responses are sent where n is the number
   * of destinations. Implements the static recipient-list EIP.
   */
  def multicast(destinations: MessageValidationResponderKleisli*) = responderKleisli {
    (m: Message, k: MessageValidation => Unit) => {
      0 until destinations.size foreach { i =>
        multicastStrategy.apply {
          destinations(i) apply m.success respond { mv => k(mv ∘ (_.addHeader(Position -> i))) }
        }
      }
    }
  }

  /**
   * Generates a sequence of messages using <code>f</code> and sends n responses taken
   * from the generated message sequence. Implements the splitter EIP.
   */
  def split(f: Message => Seq[Message]) = responderKleisli {
    (m: Message, k: MessageValidation => Unit) => {
      try {
        f(m) foreach { r => k(r.success) }
      } catch {
        case e: Exception => k(m.setException(e).fail)
      }
    }
  }

  /**
   * Filters messages if <code>f</code> returns None and sends a response if
   * <code>f</code> returns Some message. Allows providers of <code>f</code> to
   * aggregate messages and continue processing with a combined message, for example.
   * Implements the aggregator EIP.
   */
  def aggregate(f: Message => Option[Message]) = responderKleisli {
    (m: Message, k: MessageValidation => Unit) => {
      try {
        f(m) match {
          case Some(r) => k(r.success)
          case None    => { /* do not continue */ }
        }
      } catch {
        case e: Exception => k(m.setException(e).fail)
      }
    }
  }

  /**
   * A message filter that evaluates predicate <code>p</code>. If <code>p</code> evaluates
   * to <code>true</code> a response is sent, otherwise the message is dropped. Implements
   * the filter EIP.
   */
  def filter(p: Message => Boolean) = aggregate { m: Message =>
    if (p(m)) Some(m) else None
  }

  /**
   * Creates a builder for a scatter-gather processor.  Implements the scatter-gather EIP.
   *
   * @see ScatterDefinition
   */
  def scatter(destinations: MessageValidationResponderKleisli*) = new ScatterDefinition(destinations: _*)

  /**
   * Builder for a scatter-gather processor.
   *
   * @see scatter
   */
  class ScatterDefinition(destinations: MessageValidationResponderKleisli*) {

    /**
     * Scatters messages to <code>destinations</code> and gathers and combines them using
     * <code>combine</code>. Messages are scattered to <code>destinations</code> using the
     * concurrency strategy returned by <code>multicastStrategy</code>. Implements the
     * scatter-gather EIP.
     *
     * @see scatter
     */
    def gather(combine: (Message, Message) => Message): MessageValidationResponderKleisli = {
      val mcp = multicastProcessor(destinations.toList, combine)
      responderKleisli((m: Message, k: MessageValidation => Unit) => mcp(m, k))
    }
  }

  /** 
   * Marks messages as failed by setting exception <code>e</code> on <code>MessageExchange</code>.
   */
  def markFailed(e: Exception): MessageProcessor = messageProcessor(m => m.setException(e))


  // ------------------------------------------
  //  Internal
  // ------------------------------------------

  /**
   * Creates a CPS processor that distributes messages to destinations (using multicast) and gathers
   * and combines the responses using an aggregator with <code>gatherFunction</code>.
   */
  private def multicastProcessor(destinations: List[MessageValidationResponderKleisli], combine: (Message, Message) => Message): MessageProcessor = {
    (m: Message, k: MessageValidation => Unit) => {
      val sgm = multicast(destinations: _*)
      val sga = aggregate(gatherFunction(combine, destinations.size))
      // ... let's eat our own dog food ...
      sgm >=> sga apply m.success respond k
    }
  }

  /**
   * Creates a function that gathers and combines multicast responses. This method has a side-effect
   * because it collects messages in a data structure that is created for each created gather function.
   */
  private def gatherFunction(combine: (Message, Message) => Message, count: Int): Message => Option[Message] = {
    val ct = new AtomicInteger(count)
    val ma = Array.fill[Message](count)(null)
    (m: Message) => {
      for (pos <- m.header(Position).asInstanceOf[Option[Int]]) {
        ma.synchronized(ma(pos) = m)
      }
      if (ct.decrementAndGet == 0) {
        val ml = ma.synchronized(ma.toList)
        Some(ml.tail.foldLeft(ml.head)((m1, m2) => combine(m1, m2).removeHeader(Position)))
      } else {
        None
      }
    }
  }
}

/**
 * DSL for route initiation and endpoint management.
 *
 * @author Martin Krasser
 */
trait DslRoute extends Conv {

  /** 
   * Creates a CPS processor that acts as a producer to the endpoint represented by <code>uri</code>.
   * This method has a side-effect because it registers the created producer at the Camel context for
   * lifecycle management.
   */
  def to(uri: String)(implicit em: EndpointMgnt, cm: ContextMgnt) = messageProcessor(uri, em, cm)

  /**
   * Creates a context for a connecting a Kleisli route to the consumer of endpoint represented by
   * <code>uri</code>.
   *
   * @see MainRouteDefinition
   */
  def from(uri: String)(implicit em: EndpointMgnt, cm: ContextMgnt) = new MainRouteDefinition(uri, em, cm)

  /**
   * Context for a connecting a Kleisli route to the consumer of endpoint represented by <code>uri</code>.
   *
   * @see from
   */
  class MainRouteDefinition(uri: String, em: EndpointMgnt, cm: ContextMgnt) {
    /**
     * Connects a Kleisli route to the consumer of the endpoint represented by <code>uri</code>. This method has
     * a side-effect because it creates and registers an endpoint consumer at the Camel context for lifecycle
     * management. Returns a context for adding error handlers.
     *
     * @see from
     * @see ErrorRouteDefinition
     */
    def route(r: MessageValidationResponderKleisli): ErrorHandlerDefinition = {
      val processor = new RouteProcessor(r)
      val consumer = em.createConsumer(uri, processor)
      processor
    }
  }

  /**
   * Context for adding error handler.
   */
  trait ErrorHandlerDefinition {
    /**
     * Type alias for error handling partial function.
     */
    type Handler = PartialFunction[Exception, MessageValidationResponderKleisli]

    /**
     * Optional error handler. By default, there's to error handler.
     */
    protected var handler: Option[Handler] = None

    /**
     * Sets the error handler.
     */
    def handle(handler: Handler) {
      this.handler = Some(handler)
    }
  }

  /**
   * Processor that mediates between endpoint consumers and Kleisli routes. <strong>For Kleisli routes
   * that may not produce a response (e.g. because of a contained filter or aggregator), an in-only
   * message exchange should be used</strong>. Sending an in-out exchange will cause the client to wait
   * forever unless an endpoint-specific timeout mechanism is implemented. When sending an in-only exchange
   * the Kleisli route is applied to the exchange's in-message and the exchange is completed immediately
   * (fire-and-forget).
   * <p>
   * Design hint: routes that receive in-out exchanges <strong>and</strong> contain filters or aggregators
   * could follow a <i>receive-acknowledge and background processing</i> strategy as shown in CamelJmsTest,
   * for example (TODO: link to Wiki).
   * <p>
   * Not returning responses, when messages were filtered out or swallowed by aggregators, was a conscious
   * design decision for reasons of consistency between CPS and direct-style usage of routes.
   */
  private class RouteProcessor(val p: MessageValidationResponderKleisli) extends AsyncProcessor with ErrorHandlerDefinition {
    import RouteProcessor._

    /**
     * Synchronous message processing.
     */
    def process(exchange: Exchange) = {
      val latch = new CountDownLatch(1)
      process(exchange, new AsyncCallback() {
        def done(doneSync: Boolean) = {
          latch.countDown
        }
      })
      latch.await
    }

    /**
     * Asynchronous message processing (may be synchronous as well if all message processor are synchronous
     * processors and all concurrency strategies are configured to be <code>Sequential</code>).
     */
    def process(exchange: Exchange, callback: AsyncCallback) =
      if (exchange.getPattern.isOutCapable) processInOut(exchange, callback) else processInOnly(exchange, callback)

    private def processInOut(exchange: Exchange, callback: AsyncCallback) = {
      route(exchange.getIn.toMessage, once(respondTo(exchange, callback)))
      false
    }

    private def processInOnly(exchange: Exchange, callback: AsyncCallback) = {
      route(exchange.getIn.toMessage.setOneway(true), ((mv: MessageValidation) => { /* ignore any result */ }))
      callback.done(true)
      true
    }

    private def route(message: Message, k: MessageValidation => Unit): Unit = {
      p apply message.success respond { mv =>
        mv match {
          case Success(m) => k(mv)
          case Failure(m) => {
            handler match {
              case None    => k(mv)
              case Some(h) => {
                for {
                  e <- m.exception
                  r <- h.lift(e)
                } {
                  r apply m.exceptionHandled.success respond k
                }
              }
            }
          }
        }
      }
    }
  }

  private object RouteProcessor {
    def respondTo(exchange: Exchange, callback: AsyncCallback): MessageValidation => Unit = (mv: MessageValidation ) => mv match {
      case Success(m) => respond(m, exchange, callback)
      case Failure(m) => respond(m, exchange, callback)
    }

    def respond(message: Message, exchange: Exchange, callback: AsyncCallback): Unit = {
      message.exception ∘ (exchange.setException(_))
      exchange.getIn.fromMessage(message)
      exchange.getOut.fromMessage(message)
      callback.done(false)
    }

    def once(k: MessageValidation => Unit): MessageValidation => Unit = {
      val done = new java.util.concurrent.atomic.AtomicBoolean(false)
      (mv: MessageValidation) => if (!done.getAndSet(true)) k(mv)
    }
  }
}

/**
 * DSL for programmatic access to responses generated by Kleisli routes (i.e. when no endpoint
 * consumer is created via <code>from(...)</code>).
 *
 * @author Martin Krasser
 */
trait DslAccess extends Conv {

  /**
   * Provides convenient access to (asynchronous) message validation responses
   * generated by responder r. Hides continuation-passing style (CPS) usage of
   * responder r.
   *
   * @see Camel.responderToResponseAccess
   */
  class ValidationResponseAccess(r: Responder[MessageValidation]) {
    /** Obtain response from responder r (blocking) */
    def response: MessageValidation = responseQueue.take

    /** Obtain response from responder r (blocking with timeout) */
    def response(timeout: Long, unit:  TimeUnit): MessageValidation = responseQueue.poll(timeout, unit)

    /** Obtain response promise from responder r */
    def responsePromise(implicit s: Strategy): Promise[MessageValidation] = promise(responseQueue.take)

    /** Obtain response queue from responder r */
    def responseQueue: BlockingQueue[MessageValidation] = {
      val queue = new java.util.concurrent.LinkedBlockingQueue[MessageValidation](10)
      r respond { mv => queue.put(mv) }
      queue
    }
  }

  /**
   * Provides convenient access to (asynchronous) message validation responses
   * generated by an application of responder Kleisli p (e.g. a Kleisli route)
   * to a message m. Hides continuation-passing style (CPS) usage of responder
   * Kleisli p.
   *
   * @see Camel.responderKleisliToResponseAccessKleisli
   */
  class ValidationResponseAccessKleisli(p: MessageValidationResponderKleisli) {
    /** Obtain response from responder Kleisli p for message m (blocking) */
    def responseFor(m: Message) =
      new ValidationResponseAccess(p apply m.success).response

    /** Obtain response from responder Kleisli p for message m (blocking with timeout) */
    def responseFor(m: Message, timeout: Long, unit:  TimeUnit) =
      new ValidationResponseAccess(p apply m.success).response(timeout: Long, unit:  TimeUnit)

    /** Obtain response promise from responder Kleisli p for message m */
    def responsePromiseFor(m: Message)(implicit s: Strategy) =
      new ValidationResponseAccess(p apply m.success).responsePromise

    /** Obtain response queue from responder Kleisli p for message m */
    def responseQueueFor(m: Message) =
      new ValidationResponseAccess(p apply m.success).responseQueue
  }
}