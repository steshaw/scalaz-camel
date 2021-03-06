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
package akka.actor

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import akka.dispatch.{CompletableFuture, MessageDispatcher, MessageInvocation}

import scalaz.camel.core.Conv._
import scalaz.camel.core.Message

/**
 * Sender used for communication with actors via <code>!</code>. Replies to this
 * sender are passed to continuation <code>k</code>.
 *
 * @author Martin Krasser
 */
sealed class Sender(k: MessageValidation => Unit) extends ActorRef with ScalaActorRef {
  def start = { _status = akka.actor.ActorRefInternals.RUNNING; this }
  def stop = { _status = akka.actor.ActorRefInternals.SHUTDOWN }

  /**
   * Processes replies by passing it to continuation <code>k</code>. If the reply is of
   * <ul>
   * <li>type <code>MessageValidation</code> then it is passed to <code>k</code> as is</li>
   * <li>type <code>Message</code> then it is converted to either <code>Success</code> or
   * <code>Failure</code> (depending on the the <code>message.exception</code> value)
   * before passing to <code>k</code></li>.
   * <li>any other type it is used as body for <code>Success(Message(body))</code> that
   * is then passed to <code>k</code></li>.
   * </ul>
   */
  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel) = {
    import scalaz._
    import Scalaz._

    message match {
      case mv: MessageValidation => k(mv)
      case m: Message => m.exception match {
        case None    => k(m.success)
        case Some(_) => k(m.fail)
      }
      case b => k(Message(b).success)
    }
  }

  def actorClass: Class[_ <: Actor] = unsupported
  def actorClassName = unsupported
  def dispatcher_=(md: MessageDispatcher): Unit = unsupported
  def dispatcher: MessageDispatcher = unsupported
  def makeRemote(hostname: String, port: Int): Unit = unsupported
  def makeRemote(address: InetSocketAddress): Unit = unsupported
  def homeAddress_=(address: InetSocketAddress): Unit = unsupported
  def remoteAddress: Option[InetSocketAddress] = unsupported
  def link(actorRef: ActorRef): Unit = unsupported
  def linkedActors: java.util.Map[Uuid, ActorRef] = unsupported
  def unlink(actorRef: ActorRef): Unit = unsupported
  def startLink(actorRef: ActorRef): Unit = unsupported
  def startLinkRemote(actorRef: ActorRef, hostname: String, port: Int): Unit = unsupported
  def spawn(clazz: Class[_ <: Actor]): ActorRef = unsupported
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef = unsupported
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef = unsupported
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef = unsupported
  def shutdownLinkedActors: Unit = unsupported
  def supervisor: Option[ActorRef] = unsupported
  def homeAddress: Option[InetSocketAddress] = None
  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(message: Any, timeout: Long, channel: UntypedChannel) = unsupported
  protected[akka] def mailbox: AnyRef = unsupported
  protected[akka] def mailbox_=(msg: AnyRef):AnyRef = unsupported
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = unsupported
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = unsupported
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = unsupported
  protected[akka] def linkedActorsAsList: List[ActorRef] = unsupported
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = unsupported
  protected[akka] def remoteAddress_=(addr: Option[InetSocketAddress]): Unit = unsupported
  protected[akka] def registerSupervisorAsRemoteActor = unsupported
  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = unsupported
  protected[akka] def actorInstance: AtomicReference[Actor] = unsupported

  private def unsupported = throw new UnsupportedOperationException("Not supported for %s" format classOf[Sender].getName)
}