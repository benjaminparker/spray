/*
 * Copyright (C) 2011-2013 spray.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.can.server

import org.specs2.mutable.Specification
import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor.{ ActorRef, ActorSystem }
import akka.io.IO
import akka.testkit.TestProbe
import spray.can.Http
import spray.util.Utils.temporaryServerHostnameAndPort
import spray.httpx.RequestBuilding._
import spray.http._
import java.net.Socket
import java.io.{ InputStreamReader, BufferedReader, OutputStreamWriter, BufferedWriter }
import org.parboiled.common.FileUtils
import scala.annotation.tailrec

class SprayCanServerSpec extends Specification {
  val testConf: Config = ConfigFactory.parseString("""
    akka {
      event-handlers = ["akka.testkit.TestEventListener"]
      loglevel = WARNING
      io.tcp.trace-logging = off
    }""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)

  "The server-side spray-can HTTP infrastructure" should {

    "properly bind and unbind an HttpListener" in new TestSetup {
      val commander = TestProbe()
      commander.send(listener, Http.Unbind)
      commander expectMsg Http.Unbound
    }

    "properly complete a simple request/response cycle" in new TestSetup {
      val connection = openNewClientConnection()
      val serverHandler = acceptConnection()

      val probe = sendRequest(connection, Get("/abc"))
      serverHandler.expectMsgType[HttpRequest].uri === Uri(s"http://$hostname:$port/abc")

      serverHandler.reply(HttpResponse(entity = "yeah"))
      probe.expectMsgType[HttpResponse].entity === HttpEntity("yeah")
    }

    "maintain response order for pipelined requests" in new TestSetup {
      val connection = openNewClientConnection()
      val serverHandler = acceptConnection()

      val probeA = sendRequest(connection, Get("/a"))
      val probeB = sendRequest(connection, Get("/b"))
      serverHandler.expectMsgType[HttpRequest].uri.path.toString === "/a"
      val responderA = serverHandler.sender
      serverHandler.expectMsgType[HttpRequest].uri.path.toString === "/b"
      serverHandler.reply(HttpResponse(entity = "B"))
      serverHandler.send(responderA, HttpResponse(entity = "A"))

      probeA.expectMsgType[HttpResponse].entity === HttpEntity("A")
      probeB.expectMsgType[HttpResponse].entity === HttpEntity("B")
    }

    "automatically produce an error response" in {
      "when the request has no absolute URI and no Host header" in new TestSetup {
        val socket = openClientSocket()
        val serverHandler = acceptConnection()
        val writer = write(socket, "GET / HTTP/1.1\r\n\r\n")
        serverHandler.expectMsg(Http.Closed)
        val (text, reader) = readAll(socket)()
        text must startWith("HTTP/1.1 400 Bad Request")
        text must endWith("Cannot establish effective request URI, request has a relative URI and is missing a `Host` header")
        socket.close()
      }
      "when the request has an ill-formed URI" in new TestSetup {
        val socket = openClientSocket()
        val serverHandler = acceptConnection()
        val writer = write(socket, "GET http://host:naaa HTTP/1.1\r\n\r\n")
        serverHandler.expectMsg(Http.Closed)
        val (text, reader) = readAll(socket)()
        text must startWith("HTTP/1.1 400 Bad Request")
        text must contain("Illegal request-target")
        socket.close()
      }
      "when the request has a URI with a fragment" in new TestSetup {
        val socket = openClientSocket()
        val serverHandler = acceptConnection()
        val writer = write(socket, "GET /path?query#fragment HTTP/1.1\r\n\r\n")
        serverHandler.expectMsg(Http.Closed)
        val (text, reader) = readAll(socket)()
        text must startWith("HTTP/1.1 400 Bad Request")
        text must contain("Illegal request-target, unexpected character")
        socket.close()
      }
    }
  }

  step {
    val probe = TestProbe()
    probe.send(IO(Http), Http.CloseAll)
    probe.expectMsg(Http.ClosedAll)
    system.shutdown()
  }

  class TestSetup extends org.specs2.specification.Scope {
    val (hostname, port) = temporaryServerHostnameAndPort()
    val bindHandler = TestProbe()

    // automatically bind a server
    val listener = {
      val commander = TestProbe()
      commander.send(IO(Http), Http.Bind(bindHandler.ref, hostname, port))
      commander expectMsg Http.Bound
      commander.sender
    }

    def openNewClientConnection(): ActorRef = {
      val probe = TestProbe()
      probe.send(IO(Http), Http.Connect(hostname, port))
      probe.expectMsgType[Http.Connected]
      probe.sender
    }

    def acceptConnection(): TestProbe = {
      bindHandler.expectMsgType[Http.Connected]
      val probe = TestProbe()
      bindHandler reply Http.Register(probe.ref)
      probe
    }

    def sendRequest(connection: ActorRef, part: HttpRequestPart): TestProbe = {
      val probe = TestProbe()
      probe.send(connection, part)
      probe
    }

    def openClientSocket() = new Socket(hostname, port)

    def write(socket: Socket, data: String) = {
      val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream))
      writer.write(data)
      writer.flush()
      writer
    }

    def readAll(socket: Socket)(reader: BufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream))): (String, BufferedReader) = {
      val sb = new java.lang.StringBuilder
      val cbuf = new Array[Char](256)
      @tailrec def drain(): (String, BufferedReader) = reader.read(cbuf) match {
        case -1 ⇒ sb.toString -> reader
        case n  ⇒ sb.append(cbuf, 0, n); drain()
      }
      drain()
    }
  }
}