package scaling

/*
  Experimenting whether it is possible to scale capabilities
  if there are passed in the method signature using context functions or usings/implicit params

  Here lets experiment lets say with 10 services total,
  accumulated in 1-3 layers

  Questions to answer:
  1. will it be usable to have e.g. 3, 6, 10 service/capabilities in the method signature?
  2. can we wrap the services in some groups to reduce their count, and what kind of services/layers is making sense to group together
  3. if grouped together, what is the impact on using the original services that were wrapped
  4. and how do we provide the implementation (for whole layer/module, pre-builds layers base on defaults)
 */

// ============================================================================
// PART 1: Define 10 individual capabilities across different layers
// ============================================================================

// Domain Models
case class User(id: String, name: String, email: String)
case class Order(id: String, userId: String, amount: Double)
case class Product(id: String, name: String, price: Double)
case class Metric(name: String, value: Double, timestamp: Long)

// Infrastructure Layer (3 services)
trait Database:
  def query(sql: String): List[Map[String, String]]
  def execute(sql: String): Unit

object Database:
  def query(sql: String): Database ?=> List[Map[String, String]] =
    summon[Database].query(sql)
  def execute(sql: String): Database ?=> Unit =
    summon[Database].execute(sql)

trait Cache:
  def get(key: String): Option[String]
  def set(key: String, value: String): Unit
  def invalidate(key: String): Unit

object Cache:
  def get(key: String): Cache ?=> Option[String] =
    summon[Cache].get(key)
  def set(key: String, value: String): Cache ?=> Unit =
    summon[Cache].set(key, value)
  def invalidate(key: String): Cache ?=> Unit =
    summon[Cache].invalidate(key)

trait FileStorage:
  def read(path: String): String
  def write(path: String, content: String): Unit
  def delete(path: String): Unit

object FileStorage:
  def read(path: String): FileStorage ?=> String =
    summon[FileStorage].read(path)
  def write(path: String, content: String): FileStorage ?=> Unit =
    summon[FileStorage].write(path, content)
  def delete(path: String): FileStorage ?=> Unit =
    summon[FileStorage].delete(path)

// Communication Layer (3 services)
trait EmailService:
  def send(to: String, subject: String, body: String): Unit

object EmailService:
  def send(to: String, subject: String, body: String): EmailService ?=> Unit =
    summon[EmailService].send(to, subject, body)

trait SmsService:
  def sendSms(phoneNumber: String, message: String): Unit

object SmsService:
  def sendSms(phoneNumber: String, message: String): SmsService ?=> Unit =
    summon[SmsService].sendSms(phoneNumber, message)

trait PushNotification:
  def sendPush(userId: String, title: String, body: String): Unit

object PushNotification:
  def sendPush(userId: String, title: String, body: String): PushNotification ?=> Unit =
    summon[PushNotification].sendPush(userId, title, body)

// Observability Layer (4 services)
trait Logging:
  def info(message: String): Unit
  def error(message: String, cause: Option[Throwable] = None): Unit
  def debug(message: String): Unit

object Logging:
  def info(message: String): Logging ?=> Unit =
    summon[Logging].info(message)
  def error(message: String, cause: Option[Throwable] = None): Logging ?=> Unit =
    summon[Logging].error(message, cause)
  def debug(message: String): Logging ?=> Unit =
    summon[Logging].debug(message)

trait Metrics:
  def increment(name: String): Unit
  def gauge(name: String, value: Double): Unit
  def timing(name: String, durationMs: Long): Unit

object Metrics:
  def increment(name: String): Metrics ?=> Unit =
    summon[Metrics].increment(name)
  def gauge(name: String, value: Double): Metrics ?=> Unit =
    summon[Metrics].gauge(name, value)
  def timing(name: String, durationMs: Long): Metrics ?=> Unit =
    summon[Metrics].timing(name, durationMs)

trait Analytics:
  def track(event: String, properties: Map[String, String]): Unit
  def identify(userId: String, traits: Map[String, String]): Unit

object Analytics:
  def track(event: String, properties: Map[String, String]): Analytics ?=> Unit =
    summon[Analytics].track(event, properties)
  def identify(userId: String, traits: Map[String, String]): Analytics ?=> Unit =
    summon[Analytics].identify(userId, traits)

trait Tracing:
  def startSpan(name: String): Unit
  def endSpan(): Unit
  def addTag(key: String, value: String): Unit

object Tracing:
  def startSpan(name: String): Tracing ?=> Unit =
    summon[Tracing].startSpan(name)
  def endSpan(): Tracing ?=> Unit =
    summon[Tracing].endSpan()
  def addTag(key: String, value: String): Tracing ?=> Unit =
    summon[Tracing].addTag(key, value)


// ============================================================================
// PART 2: Demonstrate the problem - many capabilities in signatures
// ============================================================================

object ProblematicApproach:

  // ❌ PROBLEM 1: Too many capabilities (10!) - hard to read and maintain
  def complexBusinessOperation(userId: String): (
    Database,
    Cache,
    FileStorage,
    EmailService,
    SmsService,
    PushNotification,
    Logging,
    Metrics,
    Analytics,
    Tracing
  ) ?=> Unit =
    Logging.info(s"Starting complex operation for user: $userId")
    Metrics.increment("operation.started")
    Tracing.startSpan("complexBusinessOperation")

    val userData = Database.query(s"SELECT * FROM users WHERE id = '$userId'")
    Cache.set(s"user:$userId", userData.toString)
    EmailService.send("admin@example.com", "Operation Started", s"User $userId initiated operation")

    Tracing.endSpan()

  // ❌ PROBLEM 2: Repetition - many functions need similar capability sets
  def anotherComplexOperation(orderId: String): (
    Database,
    Cache,
    FileStorage,
    EmailService,
    SmsService,
    PushNotification,
    Logging,
    Metrics,
    Analytics,
    Tracing
  ) ?=> Unit =
    Logging.info(s"Processing order: $orderId")
    Metrics.increment("order.processed")
    // ... more logic

  // ❌ PROBLEM 3: Hard to test - need to provide all 10 capabilities even for simple test
  // ❌ PROBLEM 4: Changes propagate everywhere - adding 11th capability means updating all signatures


// ============================================================================
// PART 3: SOLUTION 1 - Layered Grouping (Recommended from README)
// ============================================================================

// Group capabilities by architectural layer/concern
case class InfraLayer(
  db: Database,
  cache: Cache,
  storage: FileStorage
)

case class CommLayer(
  email: EmailService,
  sms: SmsService,
  push: PushNotification
)

case class ObsLayer(
  logging: Logging,
  metrics: Metrics,
  analytics: Analytics,
  tracing: Tracing
)

object LayeredApproach:

  // ✅ BETTER: Only 3 capabilities instead of 10!
  def complexBusinessOperation(userId: String): (InfraLayer, CommLayer, ObsLayer) ?=> Unit =
    val infra = summon[InfraLayer]
    val comm = summon[CommLayer]
    val obs = summon[ObsLayer]

    obs.logging.info(s"Starting complex operation for user: $userId")
    obs.metrics.increment("operation.started")
    obs.tracing.startSpan("complexBusinessOperation")

    val userData = infra.db.query(s"SELECT * FROM users WHERE id = '$userId'")
    infra.cache.set(s"user:$userId", userData.toString)
    comm.email.send("admin@example.com", "Operation Started", s"User $userId initiated operation")

    obs.tracing.endSpan()

  // ✅ BENEFIT: Operations can declare only needed layers
  def dataOnlyOperation(productId: String): InfraLayer ?=> Unit =
    val infra = summon[InfraLayer]
    infra.db.query(s"SELECT * FROM products WHERE id = '$productId'")

  def notificationOnlyOperation(userId: String, message: String): CommLayer ?=> Unit =
    val comm = summon[CommLayer]
    comm.email.send("user@example.com", "Notification", message)
    comm.push.sendPush(userId, "Alert", message)

  // ✅ BENEFIT: Easy to test - only provide needed layers
  // ✅ BENEFIT: Clear separation of concerns


// ============================================================================
// PART 4: SOLUTION 2 - Single System Wrapper (Alternative from README)
// ============================================================================

case class AppSystem(
  // Infrastructure
  db: Database,
  cache: Cache,
  storage: FileStorage,
  // Communication
  email: EmailService,
  sms: SmsService,
  push: PushNotification,
  // Observability
  logging: Logging,
  metrics: Metrics,
  analytics: Analytics,
  tracing: Tracing
)

object SystemWrapperApproach:

  // ✅ SIMPLEST: Only 1 capability in signature
  def complexBusinessOperation(userId: String): AppSystem ?=> Unit =
    val sys = summon[AppSystem]

    sys.logging.info(s"Starting complex operation for user: $userId")
    sys.metrics.increment("operation.started")
    sys.tracing.startSpan("complexBusinessOperation")

    val userData = sys.db.query(s"SELECT * FROM users WHERE id = '$userId'")
    sys.cache.set(s"user:$userId", userData.toString)
    sys.email.send("admin@example.com", "Operation Started", s"User $userId initiated operation")

    sys.tracing.endSpan()

  // ❌ DRAWBACK: All operations get all capabilities (lose granularity)
  def simpleDataOperation(productId: String): AppSystem ?=> Unit =
    val sys = summon[AppSystem]
    // Only needs DB, but gets entire system
    sys.db.query(s"SELECT * FROM products WHERE id = '$productId'")


// ============================================================================
// PART 5: SOLUTION 3 - Hybrid Approach (Best of Both Worlds)
// ============================================================================

object HybridApproach:
  // Fine-grained for business logic
  def validateOrder(order: Order): InfraLayer ?=> Boolean =
    val infra = summon[InfraLayer]
    val productData = infra.db.query(s"SELECT * FROM products WHERE id = '${order.id}'")
    productData.nonEmpty

  def notifyCustomer(order: Order, user: User): CommLayer ?=> Unit =
    val comm = summon[CommLayer]
    comm.email.send(user.email, "Order Confirmation", s"Order ${order.id} confirmed")
    comm.sms.sendSms("1234567890", s"Order ${order.id} confirmed")

  def recordMetrics(order: Order): ObsLayer ?=> Unit =
    val obs = summon[ObsLayer]
    obs.metrics.increment("order.completed")
    obs.analytics.track("order_completed", Map("orderId" -> order.id, "amount" -> order.amount.toString))

  // Compose into larger operation
  def processOrder(order: Order, user: User): (InfraLayer, CommLayer, ObsLayer) ?=> Unit =
    val infra = summon[InfraLayer]
    val obs = summon[ObsLayer]

    obs.logging.info(s"Processing order ${order.id}")

    if validateOrder(order) then
      infra.db.execute(s"INSERT INTO orders VALUES ('${order.id}', '${order.userId}', ${order.amount})")
      notifyCustomer(order, user)
      recordMetrics(order)
      obs.logging.info(s"Order ${order.id} processed successfully")
    else
      obs.logging.error(s"Invalid order ${order.id}")


// ============================================================================
// PART 6: Implementation Providers - How to construct the layers
// ============================================================================

object MockImplementations:
  // Individual mock implementations
  val mockDatabase: Database = new Database:
    override def query(sql: String): List[Map[String, String]] =
      println(s"[MockDB] Query: $sql")
      List.empty
    override def execute(sql: String): Unit =
      println(s"[MockDB] Execute: $sql")

  val mockCache: Cache = new Cache:
    private var store = Map.empty[String, String]
    override def get(key: String): Option[String] =
      println(s"[MockCache] Get: $key")
      store.get(key)
    override def set(key: String, value: String): Unit =
      println(s"[MockCache] Set: $key = $value")
      store = store + (key -> value)
    override def invalidate(key: String): Unit =
      println(s"[MockCache] Invalidate: $key")
      store = store - key

  val mockFileStorage: FileStorage = new FileStorage:
    override def read(path: String): String =
      println(s"[MockStorage] Read: $path")
      ""
    override def write(path: String, content: String): Unit =
      println(s"[MockStorage] Write: $path")
    override def delete(path: String): Unit =
      println(s"[MockStorage] Delete: $path")

  val mockEmailService: EmailService = new EmailService:
    override def send(to: String, subject: String, body: String): Unit =
      println(s"[MockEmail] To: $to, Subject: $subject")

  val mockSmsService: SmsService = new SmsService:
    override def sendSms(phoneNumber: String, message: String): Unit =
      println(s"[MockSMS] To: $phoneNumber, Message: $message")

  val mockPushNotification: PushNotification = new PushNotification:
    override def sendPush(userId: String, title: String, body: String): Unit =
      println(s"[MockPush] User: $userId, Title: $title")

  val mockLogging: Logging = new Logging:
    override def info(message: String): Unit =
      println(s"[INFO] $message")
    override def error(message: String, cause: Option[Throwable]): Unit =
      println(s"[ERROR] $message ${cause.map(_.getMessage).getOrElse("")}")
    override def debug(message: String): Unit =
      println(s"[DEBUG] $message")

  val mockMetrics: Metrics = new Metrics:
    override def increment(name: String): Unit =
      println(s"[Metrics] Increment: $name")
    override def gauge(name: String, value: Double): Unit =
      println(s"[Metrics] Gauge: $name = $value")
    override def timing(name: String, durationMs: Long): Unit =
      println(s"[Metrics] Timing: $name = ${durationMs}ms")

  val mockAnalytics: Analytics = new Analytics:
    override def track(event: String, properties: Map[String, String]): Unit =
      println(s"[Analytics] Event: $event, Properties: $properties")
    override def identify(userId: String, traits: Map[String, String]): Unit =
      println(s"[Analytics] Identify: $userId, Traits: $traits")

  val mockTracing: Tracing = new Tracing:
    override def startSpan(name: String): Unit =
      println(s"[Trace] Start span: $name")
    override def endSpan(): Unit =
      println(s"[Trace] End span")
    override def addTag(key: String, value: String): Unit =
      println(s"[Trace] Tag: $key = $value")

  // Pre-built layers using defaults
  given InfraLayer = InfraLayer(mockDatabase, mockCache, mockFileStorage)
  given CommLayer = CommLayer(mockEmailService, mockSmsService, mockPushNotification)
  given ObsLayer = ObsLayer(mockLogging, mockMetrics, mockAnalytics, mockTracing)

  // Pre-built complete system
  given AppSystem = AppSystem(
    mockDatabase, mockCache, mockFileStorage,
    mockEmailService, mockSmsService, mockPushNotification,
    mockLogging, mockMetrics, mockAnalytics, mockTracing
  )


// ============================================================================
// PART 7: Comparison Matrix - Usability Analysis
// ============================================================================

object UsabilityComparison:

  def demonstrateUsability(): Unit =
    println("\n=== USABILITY COMPARISON ===\n")

    println("1. INDIVIDUAL CAPABILITIES (10 in signature):")
    println("   ❌ Signature length: Very long and hard to read")
    println("   ❌ Repetition: High - same 10 capabilities everywhere")
    println("   ❌ Testing: Need to mock all 10 even for simple tests")
    println("   ❌ Maintenance: Adding capability = update all signatures")
    println("   Example signature lines: ~7 lines")

    println("\n2. LAYERED GROUPING (3 layers):")
    println("   ✅ Signature length: Short and readable")
    println("   ✅ Repetition: Low - reuse layer types")
    println("   ✅ Testing: Only mock needed layers")
    println("   ✅ Maintenance: Changes isolated to layer definitions")
    println("   ✅ Granularity: Operations declare only needed layers")
    println("   Example signature: 1 line")

    println("\n3. SYSTEM WRAPPER (1 capability):")
    println("   ✅ Signature length: Minimal")
    println("   ✅ Repetition: None")
    println("   ❌ Granularity: Lost - everything gets all capabilities")
    println("   ❌ Testing: Always need complete system")
    println("   Example signature: 1 line")

    println("\n4. HYBRID APPROACH:")
    println("   ✅ Best of both: Fine-grained + composition")
    println("   ✅ Small operations: Only needed layers")
    println("   ✅ Complex operations: Compose from small ones")
    println("   ✅ Testing: Flexible - test at any granularity")


// ============================================================================
// PART 8: Demonstration - Running all approaches
// ============================================================================

@main def scalingMain(): Unit =
  println("=" * 80)
  println("CAPABILITIES SCALING EXPERIMENT")
  println("=" * 80)

  val testUser = User("user-1", "John Doe", "john@example.com")
  val testOrder = Order("order-1", "user-1", 99.99)

  // Demonstrate Problematic Approach
  println("\n--- PROBLEMATIC APPROACH (10 capabilities) ---")
  {
    given db: Database = MockImplementations.mockDatabase
    given cache: Cache = MockImplementations.mockCache
    given storage: FileStorage = MockImplementations.mockFileStorage
    given email: EmailService = MockImplementations.mockEmailService
    given sms: SmsService = MockImplementations.mockSmsService
    given push: PushNotification = MockImplementations.mockPushNotification
    given logging: Logging = MockImplementations.mockLogging
    given metrics: Metrics = MockImplementations.mockMetrics
    given analytics: Analytics = MockImplementations.mockAnalytics
    given tracing: Tracing = MockImplementations.mockTracing

    ProblematicApproach.complexBusinessOperation("user-1")
  }

  // Demonstrate Layered Approach
  println("\n--- LAYERED APPROACH (3 layers) ---")
  {
    given infra: InfraLayer = InfraLayer(
      MockImplementations.mockDatabase,
      MockImplementations.mockCache,
      MockImplementations.mockFileStorage
    )
    given comm: CommLayer = CommLayer(
      MockImplementations.mockEmailService,
      MockImplementations.mockSmsService,
      MockImplementations.mockPushNotification
    )
    given obs: ObsLayer = ObsLayer(
      MockImplementations.mockLogging,
      MockImplementations.mockMetrics,
      MockImplementations.mockAnalytics,
      MockImplementations.mockTracing
    )

    LayeredApproach.complexBusinessOperation("user-1")
    println("\nCalling data-only operation (only needs InfraLayer):")
    LayeredApproach.dataOnlyOperation("product-1")
  }

  // Demonstrate System Wrapper
  println("\n--- SYSTEM WRAPPER APPROACH (1 system) ---")
  {
    given sys: AppSystem = AppSystem(
      MockImplementations.mockDatabase,
      MockImplementations.mockCache,
      MockImplementations.mockFileStorage,
      MockImplementations.mockEmailService,
      MockImplementations.mockSmsService,
      MockImplementations.mockPushNotification,
      MockImplementations.mockLogging,
      MockImplementations.mockMetrics,
      MockImplementations.mockAnalytics,
      MockImplementations.mockTracing
    )

    SystemWrapperApproach.complexBusinessOperation("user-1")
  }

  // Demonstrate Hybrid Approach
  println("\n--- HYBRID APPROACH (composition) ---")
  {
    given infra: InfraLayer = InfraLayer(
      MockImplementations.mockDatabase,
      MockImplementations.mockCache,
      MockImplementations.mockFileStorage
    )
    given comm: CommLayer = CommLayer(
      MockImplementations.mockEmailService,
      MockImplementations.mockSmsService,
      MockImplementations.mockPushNotification
    )
    given obs: ObsLayer = ObsLayer(
      MockImplementations.mockLogging,
      MockImplementations.mockMetrics,
      MockImplementations.mockAnalytics,
      MockImplementations.mockTracing
    )

    HybridApproach.processOrder(testOrder, testUser)
  }

  // Show usability comparison
  UsabilityComparison.demonstrateUsability()

  println("\n" + "=" * 80)
  println("CONCLUSIONS:")
  println("=" * 80)
  println("1. Individual capabilities (10) are UNUSABLE at scale")
  println("2. Layered grouping (3) provides BEST BALANCE:")
  println("   - Readable signatures")
  println("   - Flexible composition")
  println("   - Easy testing")
  println("   - Clear separation of concerns")
  println("3. System wrapper (1) is TOO COARSE:")
  println("   - Simple signatures but loses granularity")
  println("   - Good for small apps, problematic for large ones")
  println("4. HYBRID is RECOMMENDED for production:")
  println("   - Fine-grained business logic")
  println("   - Layered grouping for infrastructure")
  println("   - Compose small operations into complex workflows")
  println("=" * 80)