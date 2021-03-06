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
package scalaz.camel.core

import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, WordSpec}

import scalaz.concurrent.Strategy._

/**
 * @author Martin Krasser
 */
class CamelJmsTest extends Camel with CamelTestProcessors with WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import org.apache.camel.component.mock.MockEndpoint
  import org.apache.camel.spring.SpringCamelContext._

  dispatchConcurrencyStrategy = Sequential
  multicastConcurrencyStrategy = Sequential
  processorConcurrencyStrategy = Naive

  val context = springCamelContext("/context.xml")
  val template = context.createProducerTemplate
  implicit val router = new Router(context)

  override def beforeAll = router.start
  override def afterAll = router.stop
  override def afterEach = mock.reset

  def mock = context.getEndpoint("mock:mock", classOf[MockEndpoint])

  def support = afterWord("support")

  "scalaz.camel.core.Camel" should support {

    "communication with jms endpoints" in {
      from("jms:queue:test") {
        appendToBody("-1") >=> appendToBody("-2") >=> printMessage >=> to("mock:mock")
      }

      from("direct:test") {
        to("jms:queue:test") >=> printMessage
      }

      mock.expectedBodiesReceivedInAnyOrder("a-1-2", "b-1-2", "c-1-2")
      template.sendBody("direct:test", "a")
      template.sendBody("direct:test", "b")
      template.sendBody("direct:test", "c")
      mock.assertIsSatisfied
    }

    "receive-acknowledge and background processing scenarios" in {
      from("direct:ack") {
        oneway >=> to("jms:queue:background") >=> { m: Message => m.appendToBody("-ack") }
      }

      from("jms:queue:background") {
        appendToBody("-1") >=> to("mock:mock")
      }

      mock.expectedBodiesReceived("hello-1")
      template.requestBody("direct:ack", "hello") must equal ("hello-ack")
      mock.assertIsSatisfied
    }

    "fast failure of routes" in {
      from("jms:queue:test-failure") {
        appendToBody("-1") >=> choose {
          case Message("a-1", _) => failWithMessage("failure")
          case Message("b-1", _) => printMessage
        } >=> to("mock:mock")
      }

      from("direct:test-failure") {
        to("jms:queue:test-failure") >=> printMessage
      }

      mock.expectedBodiesReceived("b-1")
      template.sendBody("direct:test-failure", "a")
      template.sendBody("direct:test-failure", "b")
      mock.assertIsSatisfied
    }
  }
}