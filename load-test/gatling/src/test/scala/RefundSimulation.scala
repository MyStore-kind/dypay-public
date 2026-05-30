// 退款链路压测
// 链路：查询订单 -> 创建退款 -> Stripe 退款 -> Webhook 通知
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class RefundSimulation extends Simulation {

  val baseUrl: String = sys.env.getOrElse("API_BASE_URL", "http://localhost:8080")
  val stripeKey: String = sys.env.getOrElse("STRIPE_TEST_KEY", "${STRIPE_TEST_KEY}")

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .header("X-Stripe-Test-Key", stripeKey)

  val merchantFeeder = csv("test-data/merchants.csv").circular

  // 退款场景：先创建一笔订单并支付，然后退款
  val refundScenario = scenario("退款链路")
    .feed(merchantFeeder)
    .exec(
      http("查询可退款订单")
        .get("/api/v1/orders/refundable?merchantId=${merchantId}")
        .check(status.is(200))
        .check(jsonPath("$.orders[0].orderId").saveAs("orderId"))
        .check(jsonPath("$.orders[0].amount").saveAs("amount"))
    )
    .pause(50.millis, 200.millis)
    .exec(
      http("发起退款")
        .post("/api/v1/refunds")
        .body(StringBody(
          """{
            |  "orderId": "${orderId}",
            |  "amount": ${amount},
            |  "reason": "requested_by_customer"
            |}""".stripMargin)).asJson
        .check(status.is(200))
        .check(jsonPath("$.refundId").saveAs("refundId"))
    )
    .exec(
      http("查询退款状态")
        .get("/api/v1/refunds/${refundId}")
        .check(status.is(200))
        .check(jsonPath("$.status").in("succeeded", "pending"))
    )

  // 退款压力较小：100 TPS 持续 5 分钟
  setUp(
    refundScenario.inject(
      rampUsersPerSec(0).to(50).during(1.minutes),
      constantUsersPerSec(100).during(5.minutes)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(1500),
      global.responseTime.percentile4.lt(3000),
      global.failedRequests.percent.lt(1.0)
    )
}
