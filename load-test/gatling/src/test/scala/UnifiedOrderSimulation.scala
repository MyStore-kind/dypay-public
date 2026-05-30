// 统一下单全链路压测
// 链路：商户下单 -> 风控钩子 -> 路由 -> Stripe 沙箱 -> Webhook 回调
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class UnifiedOrderSimulation extends Simulation {

  // 基础配置（从环境变量读取，避免硬编码）
  val baseUrl: String = sys.env.getOrElse("API_BASE_URL", "http://localhost:8080")
  val stripeKey: String = sys.env.getOrElse("STRIPE_TEST_KEY", "${STRIPE_TEST_KEY}")

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .header("X-Stripe-Test-Key", stripeKey)
    .userAgentHeader("Gatling/LoadTest")

  // 测试数据 Feeder：循环读取商户与测试卡
  val merchantFeeder = csv("test-data/merchants.csv").circular
  val cardFeeder = csv("test-data/cards.csv").random

  // 场景定义：完整支付链路
  val unifiedOrderScenario = scenario("统一下单全链路")
    .feed(merchantFeeder)
    .feed(cardFeeder)
    // 1. 创建订单
    .exec(
      http("创建订单")
        .post("/api/v1/orders")
        .body(StringBody(
          """{
            |  "merchantId": "${merchantId}",
            |  "amount": 9900,
            |  "currency": "USD",
            |  "riskLevel": "${riskLevel}"
            |}""".stripMargin)).asJson
        .check(status.is(200))
        .check(jsonPath("$.orderId").saveAs("orderId"))
    )
    .pause(50.millis, 150.millis)
    // 2. 风控钩子（要求 < 50ms）
    .exec(
      http("风控评估")
        .post("/api/v1/risk/evaluate")
        .body(StringBody("""{"orderId":"${orderId}","cardBin":"${cardBin}"}""")).asJson
        .check(status.is(200))
        .check(responseTimeInMillis.lte(50))
        .check(jsonPath("$.decision").saveAs("riskDecision"))
    )
    // 3. 路由决策
    .exec(
      http("通道路由")
        .post("/api/v1/route")
        .body(StringBody("""{"orderId":"${orderId}","currency":"USD"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.channel").saveAs("channel"))
    )
    // 4. Stripe 沙箱支付
    .exec(
      http("Stripe 支付")
        .post("/api/v1/payments/stripe")
        .body(StringBody(
          """{
            |  "orderId": "${orderId}",
            |  "cardNumber": "${cardNumber}",
            |  "exp": "${exp}",
            |  "cvc": "${cvc}"
            |}""".stripMargin)).asJson
        .check(status.in(200, 201))
        .check(jsonPath("$.paymentIntentId").saveAs("piId"))
    )
    .pause(100.millis, 300.millis)
    // 5. Webhook 回调确认
    .exec(
      http("Webhook 确认")
        .get("/api/v1/payments/${piId}/status")
        .check(status.is(200))
        .check(jsonPath("$.status").in("succeeded", "processing"))
    )

  // 阶梯式压力：0 -> 100 -> 300 -> 500 TPS，每台阶 2 分钟，目标层持续 10 分钟
  setUp(
    unifiedOrderScenario.inject(
      rampUsersPerSec(0).to(100).during(2.minutes),
      constantUsersPerSec(100).during(2.minutes),
      rampUsersPerSec(100).to(300).during(2.minutes),
      rampUsersPerSec(300).to(500).during(2.minutes),
      constantUsersPerSec(500).during(10.minutes)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(1000),  // TP95 < 1s
      global.responseTime.percentile4.lt(2000),  // TP99 < 2s
      global.failedRequests.percent.lt(1.0),     // 错误率 < 1%
      details("风控评估").responseTime.max.lt(50) // 风控钩子 < 50ms
    )
}
