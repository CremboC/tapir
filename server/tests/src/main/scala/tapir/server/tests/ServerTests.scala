package tapir.server.tests

import java.io.{ByteArrayInputStream, File, InputStream}
import java.nio.ByteBuffer

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.implicits._
import com.softwaremill.sttp._
import com.softwaremill.sttp.asynchttpclient.cats.AsyncHttpClientCatsBackend
import org.scalatest.{Assertion, BeforeAndAfterAll, FunSuite, Matchers}
import tapir.model.{MultiQueryParams, Part, UsernamePassword}
import tapir.server.{ServerDefaults, StatusMapper}
import tapir.tests.TestUtil._
import tapir.tests._
import tapir.typelevel.ParamsAsArgs
import tapir._

import scala.util.Random

trait ServerTests[R[_], S, ROUTE] extends FunSuite with Matchers with BeforeAndAfterAll {

  // method matching

  testServer(endpoint, () => pureResult(().asRight[Unit]), "GET empty endpoint") { baseUri =>
    sttp.get(baseUri).send().map(_.body shouldBe Right(""))
  }

  testServer(endpoint, () => pureResult(().asRight[Unit]), "POST empty endpoint") { baseUri =>
    sttp.post(baseUri).send().map(_.body shouldBe Right(""))
  }

  testServer(endpoint.get, () => pureResult(().asRight[Unit]), "GET a GET endpoint") { baseUri =>
    sttp.get(baseUri).send().map(_.body shouldBe Right(""))
  }

  testServer(endpoint.get, () => pureResult(().asRight[Unit]), "POST a GET endpoint") { baseUri =>
    sttp.post(baseUri).send().map(_.body shouldBe 'left)
  }

  //

  testServer(in_query_out_string, (fruit: String) => pureResult(s"fruit: $fruit".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit=orange").send().map(_.body shouldBe Right("fruit: orange"))
  }

  testServer(in_query_query_out_string, (fruit: String, amount: Option[Int]) => pureResult(s"$fruit $amount".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit=orange").send().map(_.body shouldBe Right("orange None")) *>
      sttp.get(uri"$baseUri?fruit=orange&amount=10").send().map(_.body shouldBe Right("orange Some(10)"))
  }

  testServer(in_header_out_string, (p1: String) => pureResult(s"$p1".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri").header("X-Role", "Admin").send().map(_.body shouldBe Right("Admin"))
  }

  testServer(in_path_path_out_string, (fruit: String, amount: Int) => pureResult(s"$fruit $amount".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/fruit/orange/amount/20").send().map(_.body shouldBe Right("orange 20"))
  }

  testServer(in_string_out_string, (b: String) => pureResult(b.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("Sweet").send().map(_.body shouldBe Right("Sweet"))
  }

  testServer(in_string_out_string, (b: String) => pureResult(b.asRight[Unit]), "with get method") { baseUri =>
    sttp.get(uri"$baseUri/api/echo").body("Sweet").send().map(_.body shouldBe 'left)
  }

  testServer(in_mapped_query_out_string, (fruit: List[Char]) => pureResult(s"fruit length: ${fruit.length}".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit=orange").send().map(_.body shouldBe Right("fruit length: 6"))
  }

  testServer(in_mapped_path_out_string, (fruit: Fruit) => pureResult(s"$fruit".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/fruit/kiwi").send().map(_.body shouldBe Right("Fruit(kiwi)"))
  }

  testServer(in_mapped_path_path_out_string, (p1: FruitAmount) => pureResult(s"FA: $p1".asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/fruit/orange/amount/10").send().map(_.body shouldBe Right("FA: FruitAmount(orange,10)"))
  }

  testServer(in_query_mapped_path_path_out_string, (fa: FruitAmount, color: String) => pureResult(s"FA: $fa color: $color".asRight[Unit])) {
    baseUri =>
      sttp
        .get(uri"$baseUri/fruit/orange/amount/10?color=yellow")
        .send()
        .map(_.body shouldBe Right("FA: FruitAmount(orange,10) color: yellow"))
  }

  testServer(in_query_out_mapped_string, (p1: String) => pureResult(p1.toList.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit=orange").send().map(_.body shouldBe Right("orange"))
  }

  testServer(in_query_out_mapped_string_header, (p1: String) => pureResult(FruitAmount(p1, p1.length).asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri?fruit=orange").send().map { r =>
      r.body shouldBe Right("orange")
      r.header("X-Role") shouldBe Some("6")
    }
  }

  testServer(in_json_out_json, (fa: FruitAmount) => pureResult(FruitAmount(fa.fruit + " banana", fa.amount * 2).asRight[Unit])) { baseUri =>
    sttp
      .post(uri"$baseUri/api/echo")
      .body("""{"fruit":"orange","amount":11}""")
      .send()
      .map(_.body shouldBe Right("""{"fruit":"orange banana","amount":22}"""))
  }

  testServer(in_json_out_json, (fa: FruitAmount) => pureResult(fa.asRight[Unit]), "with accept header") { baseUri =>
    sttp
      .post(uri"$baseUri/api/echo")
      .body("""{"fruit":"banana","amount":12}""")
      .header(HeaderNames.Accept, MediaTypes.Json)
      .send()
      .map(_.body shouldBe Right("""{"fruit":"banana","amount":12}"""))
  }

  testServer(in_byte_array_out_byte_array, (b: Array[Byte]) => pureResult(b.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("banana kiwi".getBytes).send().map(_.body shouldBe Right("banana kiwi"))
  }

  testServer(in_byte_buffer_out_byte_buffer, (b: ByteBuffer) => pureResult(b.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("mango").send().map(_.body shouldBe Right("mango"))
  }

  testServer(in_input_stream_out_input_stream,
             (is: InputStream) => pureResult((new ByteArrayInputStream(inputStreamToByteArray(is)): InputStream).asRight[Unit])) {
    baseUri =>
      sttp.post(uri"$baseUri/api/echo").body("mango").send().map(_.body shouldBe Right("mango"))
  }

  testServer(in_unit_out_string, () => pureResult("".asRight[Unit]), "default status mapper") { baseUri =>
    sttp.get(uri"$baseUri/not-existing-path").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  testServer(in_unit_out_string, () => pureResult("".asRight[Unit]), "custom status mapper", (_: String) => StatusCodes.AlreadyReported) {
    baseUri =>
      sttp.get(uri"$baseUri/api").send().map(_.code shouldBe StatusCodes.AlreadyReported)
  }

  testServer(in_unit_error_out_string, () => pureResult("".asLeft[Unit]), "default error status mapper") { baseUri =>
    sttp.get(uri"$baseUri/api").send().map(_.code shouldBe StatusCodes.BadRequest)
  }

  testServer(in_unit_error_out_string,
             () => pureResult("".asLeft[Unit]),
             "custom error status mapper",
             errorStatusMapper = (_: String) => StatusCodes.TooManyRequests) { baseUri =>
    sttp.get(uri"$baseUri/api").send().map(_.code shouldBe StatusCodes.TooManyRequests)
  }

  testServer(in_file_out_file, (file: File) => pureResult(file.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("pen pineapple apple pen").send().map(_.body shouldBe Right("pen pineapple apple pen"))
  }

  testServer(in_form_out_form, (fa: FruitAmount) => pureResult(fa.copy(fruit = fa.fruit.reverse, amount = fa.amount + 1).asRight[Unit])) {
    baseUri =>
      sttp
        .post(uri"$baseUri/api/echo")
        .body(Map("fruit" -> "plum", "amount" -> "10"))
        .send()
        .map(_.body shouldBe Right("fruit=mulp&amount=11"))
  }

  testServer(in_query_params_out_string,
             (mqp: MultiQueryParams) => pureResult(mqp.toSeq.sortBy(_._1).map(p => s"${p._1}=${p._2}").mkString("&").asRight[Unit])) {
    baseUri =>
      val params = Map("name" -> "apple", "weight" -> "42", "kind" -> "very good")
      sttp
        .get(uri"$baseUri/api/echo/params?$params")
        .send()
        .map(_.body shouldBe Right("kind=very good&name=apple&weight=42"))
  }

  testServer(in_headers_out_headers, (hs: Seq[(String, String)]) => pureResult(hs.map(h => h.copy(_2 = h._2.reverse)).asRight[Unit])) {
    baseUri =>
      sttp
        .get(uri"$baseUri/api/echo/headers")
        .headers(("X-Fruit", "apple"), ("Y-Fruit", "Orange"))
        .send()
        .map(_.headers should contain allOf (("X-Fruit", "elppa"), ("Y-Fruit", "egnarO")))
  }

  testServer(in_paths_out_string, (ps: Seq[String]) => pureResult(ps.mkString(" ").asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/hello/it/is/me/hal").send().map(_.body shouldBe Right("hello it is me hal"))
  }

  testServer(in_paths_out_string, (ps: Seq[String]) => pureResult(ps.mkString(" ").asRight[Unit]), "paths should match empty path") {
    baseUri =>
      sttp.get(uri"$baseUri").send().map(_.body shouldBe Right(""))
  }

  testServer(in_stream_out_stream[S], (s: S) => pureResult(s.asRight[Unit])) { baseUri =>
    sttp.post(uri"$baseUri/api/echo").body("pen pineapple apple pen").send().map(_.body shouldBe Right("pen pineapple apple pen"))
  }

  testServer(in_query_list_out_header_list, (l: List[String]) => pureResult(("v0" :: l).reverse.asRight[Unit])) { baseUri =>
    sttp
      .get(uri"$baseUri/api/echo/param-to-header?qq=${List("v1", "v2", "v3")}")
      .send()
      .map { r =>
        r.headers.filter(_._1 == "hh").map(_._2).toList shouldBe List("v3", "v2", "v1", "v0")
      }
  }

  testServer(in_simple_multipart_out_multipart,
             (fa: FruitAmount) => pureResult(FruitAmount(fa.fruit + " apple", fa.amount * 2).asRight[Unit])) { baseUri =>
    sttp
      .post(uri"$baseUri/api/echo/multipart")
      .multipartBody(multipart("fruit", "pineapple"), multipart("amount", "120"))
      .send()
      .map { r =>
        r.unsafeBody should include regex "name=\"fruit\"\\s*pineapple apple"
        r.unsafeBody should include regex "name=\"amount\"\\s*240"
      }
  }

  testServer(
    in_file_multipart_out_multipart,
    (fd: FruitData) =>
      pureResult(
        FruitData(Part(writeToFile(readFromFile(fd.data.body).reverse)).header("X-Auth", fd.data.header("X-Auth").toString)).asRight[Unit])
  ) { baseUri =>
    val file = writeToFile("peach mario")
    sttp
      .post(uri"$baseUri/api/echo/multipart")
      .multipartBody(multipartFile("data", file).fileName("fruit-data.txt").header("X-Auth", "12"))
      .send()
      .map { r =>
        r.unsafeBody should include regex "name=\"data\"\\s*X-Auth: Some\\(12\\)\\s*oiram hcaep"
      }
  }

  testServer(in_query_out_string, (fruit: String) => pureResult(s"fruit: $fruit".asRight[Unit]), "invalid query parameter") { baseUri =>
    sttp.get(uri"$baseUri?fruit2=orange").send().map(_.code shouldBe StatusCodes.BadRequest)
  }

  testServer(
    in_cookies_out_cookies,
    (cs: List[tapir.model.CookiePair]) => pureResult(cs.map(c => tapir.model.Cookie(c.name, c.value.reverse)).asRight[Unit])
  ) { baseUri =>
    sttp.get(uri"$baseUri/api/echo/headers").cookies(("c1", "v1"), ("c2", "v2")).send().map { r =>
      r.cookies.map(c => (c.name, c.value)).toList shouldBe List(("c1", "1v"), ("c2", "2v"))
    }
  }

  // path matching

  testServer(endpoint, () => pureResult(Either.right[Unit, Unit](())), "no path should match anything") { baseUri =>
    sttp.get(uri"$baseUri").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/nonemptypath").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/nonemptypath/nonemptypath2").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_root_path, () => pureResult(Either.right[Unit, Unit](())), "root path should not match non-root path") { baseUri =>
    sttp.get(uri"$baseUri/nonemptypath").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  testServer(in_root_path, () => pureResult(Either.right[Unit, Unit](())), "root path should match empty path") { baseUri =>
    sttp.get(uri"$baseUri").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_root_path, () => pureResult(Either.right[Unit, Unit](())), "root path should match root path") { baseUri =>
    sttp.get(uri"$baseUri/").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_single_path, () => pureResult(Either.right[Unit, Unit](())), "single path should match single path") { baseUri =>
    sttp.get(uri"$baseUri/api").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_single_path, () => pureResult(Either.right[Unit, Unit](())), "single path should match single/ path") { baseUri =>
    sttp.get(uri"$baseUri/api/").send().map(_.code shouldBe StatusCodes.Ok)
  }

  testServer(in_single_path, () => pureResult(Either.right[Unit, Unit](())), "single path should not match root path") { baseUri =>
    sttp.get(uri"$baseUri").send().map(_.code shouldBe StatusCodes.NotFound) >>
      sttp.get(uri"$baseUri/").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  testServer(in_single_path, () => pureResult(Either.right[Unit, Unit](())), "single path should not match larger path") { baseUri =>
    sttp.get(uri"$baseUri/api/echo").send().map(_.code shouldBe StatusCodes.NotFound) >>
      sttp.get(uri"$baseUri/api/echo/").send().map(_.code shouldBe StatusCodes.NotFound)
  }

  // auth

  testServer(in_auth_apikey_header_out_string, (s: String) => pureResult(s.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/auth").header("X-Api-Key", "1234").send().map(_.unsafeBody shouldBe "1234")
  }

  testServer(in_auth_apikey_query_out_string, (s: String) => pureResult(s.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/auth?api-key=1234").send().map(_.unsafeBody shouldBe "1234")
  }

  testServer(in_auth_basic_out_string, (up: UsernamePassword) => pureResult(up.toString.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/auth").auth.basic("teddy", "bear").send().map(_.unsafeBody shouldBe "UsernamePassword(teddy,Some(bear))")
  }

  testServer(in_auth_bearer_out_string, (s: String) => pureResult(s.asRight[Unit])) { baseUri =>
    sttp.get(uri"$baseUri/auth").auth.bearer("1234").send().map(_.unsafeBody shouldBe "1234")
  }

  //

  testServer(
    "two endpoints with a body defined as the first input: should only consume body when then path matches",
    NonEmptyList.of(
      route(endpoint.post.in(binaryBody[Array[Byte]]).in("p1").out(stringBody),
            (s: Array[Byte]) => pureResult(s"p1 ${s.length}".asRight[Unit])),
      route(endpoint.post.in(binaryBody[Array[Byte]]).in("p2").out(stringBody),
            (s: Array[Byte]) => pureResult(s"p2 ${s.length}".asRight[Unit]))
    )
  ) { baseUri =>
    sttp
      .post(uri"$baseUri/p2")
      .body("a" * 1000000)
      .send()
      .map { r =>
        r.unsafeBody shouldBe "p2 1000000"
      }
  }

  testServer(
    "two endpoints with query defined as the first input, path segments as second input: should try the second endpoint if the path doesn't match",
    NonEmptyList.of(
      route(endpoint.get.in(query[String]("q1")).in("p1"), (_: String) => pureResult(().asRight[Unit])),
      route(endpoint.get.in(query[String]("q2")).in("p2"), (_: String) => pureResult(().asRight[Unit]))
    )
  ) { baseUri =>
    sttp.get(uri"$baseUri/p1?q1=10").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/p1?q2=10").send().map(_.code shouldBe StatusCodes.BadRequest) >>
      sttp.get(uri"$baseUri/p2?q2=10").send().map(_.code shouldBe StatusCodes.Ok) >>
      sttp.get(uri"$baseUri/p2?q1=10").send().map(_.code shouldBe StatusCodes.BadRequest)
  }

  //

  implicit val backend: SttpBackend[IO, Nothing] = AsyncHttpClientCatsBackend[IO]()

  override protected def afterAll(): Unit = {
    backend.close()
    super.afterAll()
  }

  //

  type Port = Int

  def pureResult[T](t: T): R[T]

  def route[I, E, O, FN[_]](
      e: Endpoint[I, E, O, S],
      fn: FN[R[Either[E, O]]],
      statusMapper: StatusMapper[O] = ServerDefaults.statusMapper,
      errorStatusMapper: StatusMapper[E] = ServerDefaults.errorStatusMapper)(implicit paramsAsArgs: ParamsAsArgs.Aux[I, FN]): ROUTE

  def server(routes: NonEmptyList[ROUTE], port: Port): Resource[IO, Unit]

  def testServer[I, E, O, FN[_]](e: Endpoint[I, E, O, S],
                                 fn: FN[R[Either[E, O]]],
                                 testNameSuffix: String = "",
                                 statusMapper: StatusMapper[O] = ServerDefaults.statusMapper,
                                 errorStatusMapper: StatusMapper[E] = ServerDefaults.errorStatusMapper)(runTest: Uri => IO[Assertion])(
      implicit paramsAsArgs: ParamsAsArgs.Aux[I, FN]): Unit = {

    testServer(e.show + (if (testNameSuffix == "") "" else " " + testNameSuffix),
               NonEmptyList.of(route(e, fn, statusMapper, errorStatusMapper)))(runTest)
  }

  def testServer(name: String, rs: => NonEmptyList[ROUTE])(runTest: Uri => IO[Assertion]): Unit = {
    val resources = for {
      port <- Resource.liftF(IO(randomPort()))
      _ <- server(rs, port)
    } yield uri"http://localhost:$port"

    test(name)(resources.use(runTest).unsafeRunSync())
  }

  //

  private val random = new Random()
  private def randomPort(): Port = random.nextInt(29232) + 32768
}
