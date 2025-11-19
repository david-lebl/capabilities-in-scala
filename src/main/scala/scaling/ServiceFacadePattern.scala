package scaling.servicefacade

/*
  Service Facade Pattern: Combining Service and Use Case patterns

  This approach:
  1. Services are facades that encapsulate all context capabilities
  2. Service methods delegate to use case functions
  3. Use cases remain pure and testable with minimal dependencies
  4. Services provide all capabilities implicitly to use cases
  5. External consumers only interact with services (clean API)

  Benefits:
  - Clean API for external consumers (just call service methods)
  - Use cases remain testable with fine-grained dependencies
  - Services handle capability wiring internally
  - Clear separation: Services = public API, Use Cases = business logic
 */

// ============================================================================
// PART 1: Shared Infrastructure (same as before)
// ============================================================================

trait EventBus:
  def publish(topic: String, event: String): Unit

object EventBus:
  def publish(topic: String, event: String): EventBus ?=> Unit =
    summon[EventBus].publish(topic, event)

trait Logger:
  def log(context: String, message: String): Unit
  def error(context: String, message: String, error: Throwable): Unit

object Logger:
  def log(context: String, message: String): Logger ?=> Unit =
    summon[Logger].log(context, message)
  def error(context: String, message: String, error: Throwable): Logger ?=> Unit =
    summon[Logger].error(context, message, error)


// ============================================================================
// PART 2: Order Context - Service Facade Pattern
// ============================================================================

object OrderContextV2:
  // Domain Models
  case class Order(id: String, customerId: String, items: List[String], totalAmount: Double, status: String)

  // Fine-grained capabilities (for use cases)
  trait OrderRepository:
    def save(order: Order): Unit
    def findById(id: String): Option[Order]
    def updateStatus(id: String, status: String): Unit

  object OrderRepository:
    def save(order: Order): OrderRepository ?=> Unit =
      summon[OrderRepository].save(order)
    def findById(id: String): OrderRepository ?=> Option[Order] =
      summon[OrderRepository].findById(id)
    def updateStatus(id: String, status: String): OrderRepository ?=> Unit =
      summon[OrderRepository].updateStatus(id, status)

  trait OrderValidator:
    def validateOrder(order: Order): Either[String, Order]
    def checkInventory(items: List[String]): Boolean

  object OrderValidator:
    def validateOrder(order: Order): OrderValidator ?=> Either[String, Order] =
      summon[OrderValidator].validateOrder(order)
    def checkInventory(items: List[String]): OrderValidator ?=> Boolean =
      summon[OrderValidator].checkInventory(items)

  // Pure Use Cases - declare only needed capabilities
  object OrderUseCases:
    // Minimal dependencies: only what's needed
    def createOrder(customerId: String, items: List[String], amount: Double): (
      OrderRepository,
      OrderValidator,
      EventBus,
      Logger
    ) ?=> Either[String, Order] =
      Logger.log("OrderContext", s"Creating order for customer $customerId")

      val order = Order(
        id = java.util.UUID.randomUUID().toString,
        customerId = customerId,
        items = items,
        totalAmount = amount,
        status = "PENDING"
      )

      for
        validOrder <- OrderValidator.validateOrder(order)
        _ <- if OrderValidator.checkInventory(items) then Right(())
             else Left("Insufficient inventory")
        _ = OrderRepository.save(validOrder)
        _ = EventBus.publish("orders", s"OrderCreated:${validOrder.id}")
        _ = Logger.log("OrderContext", s"Order ${validOrder.id} created successfully")
      yield validOrder

    def confirmOrder(orderId: String): (OrderRepository, EventBus, Logger) ?=> Either[String, Unit] =
      Logger.log("OrderContext", s"Confirming order $orderId")

      OrderRepository.findById(orderId) match
        case Some(order) =>
          OrderRepository.updateStatus(orderId, "CONFIRMED")
          EventBus.publish("orders", s"OrderConfirmed:$orderId")
          Logger.log("OrderContext", s"Order $orderId confirmed")
          Right(())
        case None =>
          Logger.log("OrderContext", s"Order $orderId not found")
          Left(s"Order $orderId not found")

    def cancelOrder(orderId: String, reason: String): (OrderRepository, EventBus, Logger) ?=> Either[String, Unit] =
      Logger.log("OrderContext", s"Cancelling order $orderId")

      OrderRepository.findById(orderId) match
        case Some(order) =>
          OrderRepository.updateStatus(orderId, "CANCELLED")
          EventBus.publish("orders", s"OrderCancelled:$orderId:$reason")
          Logger.log("OrderContext", s"Order $orderId cancelled: $reason")
          Right(())
        case None =>
          Left(s"Order $orderId not found")

  // Service Facade - encapsulates all capabilities and provides public API
  class OrderServiceFacade(
    repository: OrderRepository,
    validator: OrderValidator,
    eventBus: EventBus,
    logger: Logger
  ):
    // Provide all capabilities implicitly within service scope
    given OrderRepository = repository
    given OrderValidator = validator
    given EventBus = eventBus
    given Logger = logger

    // Public API - delegates to use cases with capabilities automatically provided
    def createOrder(customerId: String, items: List[String], amount: Double): Either[String, Order] =
      OrderUseCases.createOrder(customerId, items, amount)

    def confirmOrder(orderId: String): Either[String, Unit] =
      OrderUseCases.confirmOrder(orderId)

    def cancelOrder(orderId: String, reason: String): Either[String, Unit] =
      OrderUseCases.cancelOrder(orderId, reason)

    def findOrder(orderId: String): Option[Order] =
      repository.findById(orderId)

  // Companion object for service construction
  object OrderServiceFacade:
    def apply(
      repository: OrderRepository,
      validator: OrderValidator,
      eventBus: EventBus,
      logger: Logger
    ): OrderServiceFacade =
      new OrderServiceFacade(repository, validator, eventBus, logger)


// ============================================================================
// PART 3: Payment Context - Service Facade Pattern
// ============================================================================

object PaymentContextV2:
  // Domain Models
  case class Payment(id: String, orderId: String, amount: Double, status: String, method: String)

  // Fine-grained capabilities
  trait PaymentGateway:
    def processPayment(amount: Double, method: String): Either[String, String]
    def refund(transactionId: String, amount: Double): Either[String, Unit]

  object PaymentGateway:
    def processPayment(amount: Double, method: String): PaymentGateway ?=> Either[String, String] =
      summon[PaymentGateway].processPayment(amount, method)
    def refund(transactionId: String, amount: Double): PaymentGateway ?=> Either[String, Unit] =
      summon[PaymentGateway].refund(transactionId, amount)

  trait PaymentRepository:
    def save(payment: Payment): Unit
    def findByOrderId(orderId: String): Option[Payment]

  object PaymentRepository:
    def save(payment: Payment): PaymentRepository ?=> Unit =
      summon[PaymentRepository].save(payment)
    def findByOrderId(orderId: String): PaymentRepository ?=> Option[Payment] =
      summon[PaymentRepository].findByOrderId(orderId)

  // Pure Use Cases - minimal dependencies
  object PaymentUseCases:
    def processPayment(orderId: String, amount: Double, method: String): (
      PaymentGateway,
      PaymentRepository,
      EventBus,
      Logger
    ) ?=> Either[String, Payment] =
      Logger.log("PaymentContext", s"Processing payment for order $orderId")

      PaymentGateway.processPayment(amount, method) match
        case Right(transactionId) =>
          val payment = Payment(
            id = java.util.UUID.randomUUID().toString,
            orderId = orderId,
            amount = amount,
            status = "COMPLETED",
            method = method
          )
          PaymentRepository.save(payment)
          EventBus.publish("payments", s"PaymentProcessed:${payment.id}:$orderId")
          Logger.log("PaymentContext", s"Payment ${payment.id} processed successfully")
          Right(payment)
        case Left(error) =>
          val payment = Payment(
            id = java.util.UUID.randomUUID().toString,
            orderId = orderId,
            amount = amount,
            status = "FAILED",
            method = method
          )
          PaymentRepository.save(payment)
          EventBus.publish("payments", s"PaymentFailed:${payment.id}:$orderId:$error")
          Logger.log("PaymentContext", s"Payment failed: $error")
          Left(error)

  // Service Facade
  class PaymentServiceFacade(
    gateway: PaymentGateway,
    repository: PaymentRepository,
    eventBus: EventBus,
    logger: Logger
  ):
    given PaymentGateway = gateway
    given PaymentRepository = repository
    given EventBus = eventBus
    given Logger = logger

    def processPayment(orderId: String, amount: Double, method: String): Either[String, Payment] =
      PaymentUseCases.processPayment(orderId, amount, method)

    def findPaymentByOrder(orderId: String): Option[Payment] =
      repository.findByOrderId(orderId)

  object PaymentServiceFacade:
    def apply(
      gateway: PaymentGateway,
      repository: PaymentRepository,
      eventBus: EventBus,
      logger: Logger
    ): PaymentServiceFacade =
      new PaymentServiceFacade(gateway, repository, eventBus, logger)


// ============================================================================
// PART 4: Shipping Context - Service Facade Pattern
// ============================================================================

object ShippingContextV2:
  // Domain Models
  case class Shipment(id: String, orderId: String, address: String, status: String)

  // Fine-grained capabilities
  trait ShippingService:
    def createShipment(orderId: String, address: String): Either[String, String]

  object ShippingService:
    def createShipment(orderId: String, address: String): ShippingService ?=> Either[String, String] =
      summon[ShippingService].createShipment(orderId, address)

  trait ShipmentRepository:
    def save(shipment: Shipment): Unit
    def findByOrderId(orderId: String): Option[Shipment]

  object ShipmentRepository:
    def save(shipment: Shipment): ShipmentRepository ?=> Unit =
      summon[ShipmentRepository].save(shipment)

  // Pure Use Cases
  object ShippingUseCases:
    def createShipment(orderId: String, address: String): (
      ShippingService,
      ShipmentRepository,
      EventBus,
      Logger
    ) ?=> Either[String, Shipment] =
      Logger.log("ShippingContext", s"Creating shipment for order $orderId")

      ShippingService.createShipment(orderId, address) match
        case Right(trackingNumber) =>
          val shipment = Shipment(
            id = java.util.UUID.randomUUID().toString,
            orderId = orderId,
            address = address,
            status = "CREATED"
          )
          ShipmentRepository.save(shipment)
          EventBus.publish("shipping", s"ShipmentCreated:${shipment.id}:$orderId")
          Logger.log("ShippingContext", s"Shipment ${shipment.id} created")
          Right(shipment)
        case Left(error) =>
          Logger.log("ShippingContext", s"Failed to create shipment: $error")
          Left(error)

  // Service Facade
  class ShippingServiceFacade(
    shippingService: ShippingService,
    repository: ShipmentRepository,
    eventBus: EventBus,
    logger: Logger
  ):
    given ShippingService = shippingService
    given ShipmentRepository = repository
    given EventBus = eventBus
    given Logger = logger

    def createShipment(orderId: String, address: String): Either[String, Shipment] =
      ShippingUseCases.createShipment(orderId, address)

    def findShipmentByOrder(orderId: String): Option[Shipment] =
      repository.findByOrderId(orderId)

  object ShippingServiceFacade:
    def apply(
      shippingService: ShippingService,
      repository: ShipmentRepository,
      eventBus: EventBus,
      logger: Logger
    ): ShippingServiceFacade =
      new ShippingServiceFacade(shippingService, repository, eventBus, logger)


// ============================================================================
// PART 5: Cross-Context Orchestration with Service Facades
// ============================================================================

object OrderFulfillmentSagaV2:
  case class FulfillmentResult(
    order: OrderContextV2.Order,
    payment: Option[PaymentContextV2.Payment],
    shipment: Option[ShippingContextV2.Shipment],
    success: Boolean,
    errors: List[String]
  )

  // Saga using service facades via context functions - ultra clean!
  def fulfillOrder(
    customerId: String,
    items: List[String],
    amount: Double,
    paymentMethod: String,
    shippingAddress: String
  ): (
    OrderContextV2.OrderServiceFacade,
    PaymentContextV2.PaymentServiceFacade,
    ShippingContextV2.ShippingServiceFacade
  ) ?=> FulfillmentResult =

    val orderService = summon[OrderContextV2.OrderServiceFacade]
    val paymentService = summon[PaymentContextV2.PaymentServiceFacade]
    val shippingService = summon[ShippingContextV2.ShippingServiceFacade]

    var errors = List.empty[String]

    // Clean service-to-service calls
    val result = for
      // Step 1: Create Order
      order <- orderService.createOrder(customerId, items, amount)

      // Step 2: Process Payment
      payment <- paymentService.processPayment(order.id, amount, paymentMethod)
        .left.map { error =>
          orderService.cancelOrder(order.id, "Payment failed")
          error
        }

      // Step 3: Confirm Order
      _ <- orderService.confirmOrder(order.id)

      // Step 4: Create Shipment
      shipment <- shippingService.createShipment(order.id, shippingAddress)
        .left.map { error =>
          orderService.cancelOrder(order.id, "Shipment creation failed")
          error
        }
    yield (order, Some(payment), Some(shipment))

    result match
      case Right((order, payment, shipment)) =>
        FulfillmentResult(order, payment, shipment, success = true, errors)

      case Left(error) =>
        errors = error :: errors
        FulfillmentResult(
          OrderContextV2.Order("", customerId, items, amount, "FAILED"),
          None,
          None,
          success = false,
          errors
        )


// ============================================================================
// PART 6: Mock Implementations
// ============================================================================

object ServiceFacadeMocks:
  // Shared infrastructure mocks
  val mockEventBus: EventBus = new EventBus:
    override def publish(topic: String, event: String): Unit =
      println(s"[EventBus] Published to '$topic': $event")

  val mockLogger: Logger = new Logger:
    override def log(context: String, message: String): Unit =
      println(s"[LOG][$context] $message")
    override def error(context: String, message: String, error: Throwable): Unit =
      println(s"[ERROR][$context] $message: ${error.getMessage}")

  // Order Context mocks
  val mockOrderRepository: OrderContextV2.OrderRepository = new OrderContextV2.OrderRepository:
    private var orders = Map.empty[String, OrderContextV2.Order]
    override def save(order: OrderContextV2.Order): Unit =
      println(s"[OrderRepository] Saving order ${order.id}")
      orders = orders + (order.id -> order)
    override def findById(id: String): Option[OrderContextV2.Order] =
      orders.get(id)
    override def updateStatus(id: String, status: String): Unit =
      println(s"[OrderRepository] Updating order $id status to $status")
      orders.get(id).foreach { order =>
        orders = orders + (id -> order.copy(status = status))
      }

  val mockOrderValidator: OrderContextV2.OrderValidator = new OrderContextV2.OrderValidator:
    override def validateOrder(order: OrderContextV2.Order): Either[String, OrderContextV2.Order] =
      if order.items.isEmpty then Left("Order must have items")
      else if order.totalAmount <= 0 then Left("Order amount must be positive")
      else Right(order)
    override def checkInventory(items: List[String]): Boolean =
      println(s"[OrderValidator] Checking inventory for ${items.size} items")
      true

  // Payment Context mocks
  val mockPaymentGateway: PaymentContextV2.PaymentGateway = new PaymentContextV2.PaymentGateway:
    override def processPayment(amount: Double, method: String): Either[String, String] =
      println(s"[PaymentGateway] Processing payment of $$${amount} via $method")
      if amount > 0 then Right(s"TXN-${java.util.UUID.randomUUID().toString.take(8)}")
      else Left("Invalid amount")
    override def refund(transactionId: String, amount: Double): Either[String, Unit] =
      println(s"[PaymentGateway] Refunding $$${amount} for transaction $transactionId")
      Right(())

  val mockPaymentRepository: PaymentContextV2.PaymentRepository = new PaymentContextV2.PaymentRepository:
    private var payments = Map.empty[String, PaymentContextV2.Payment]
    override def save(payment: PaymentContextV2.Payment): Unit =
      println(s"[PaymentRepository] Saving payment ${payment.id}")
      payments = payments + (payment.id -> payment)
    override def findByOrderId(orderId: String): Option[PaymentContextV2.Payment] =
      payments.values.find(_.orderId == orderId)

  // Shipping Context mocks
  val mockShippingService: ShippingContextV2.ShippingService = new ShippingContextV2.ShippingService:
    override def createShipment(orderId: String, address: String): Either[String, String] =
      println(s"[ShippingService] Creating shipment for order $orderId to $address")
      Right(s"TRACK-${java.util.UUID.randomUUID().toString.take(8)}")

  val mockShipmentRepository: ShippingContextV2.ShipmentRepository = new ShippingContextV2.ShipmentRepository:
    private var shipments = Map.empty[String, ShippingContextV2.Shipment]
    override def save(shipment: ShippingContextV2.Shipment): Unit =
      println(s"[ShipmentRepository] Saving shipment ${shipment.id}")
      shipments = shipments + (shipment.id -> shipment)
    override def findByOrderId(orderId: String): Option[ShippingContextV2.Shipment] =
      shipments.values.find(_.orderId == orderId)


// ============================================================================
// PART 7: Demonstration
// ============================================================================

@main def serviceFacadeMain(): Unit =
  println("=" * 80)
  println("SERVICE FACADE PATTERN")
  println("=" * 80)

  import ServiceFacadeMocks.*

  // Build services with all dependencies
  given orderService: OrderContextV2.OrderServiceFacade = OrderContextV2.OrderServiceFacade(
    mockOrderRepository,
    mockOrderValidator,
    mockEventBus,
    mockLogger
  )

  given paymentService: PaymentContextV2.PaymentServiceFacade = PaymentContextV2.PaymentServiceFacade(
    mockPaymentGateway,
    mockPaymentRepository,
    mockEventBus,
    mockLogger
  )

  given shippingService: ShippingContextV2.ShippingServiceFacade = ShippingContextV2.ShippingServiceFacade(
    mockShippingService,
    mockShipmentRepository,
    mockEventBus,
    mockLogger
  )

  println("\n--- SCENARIO 1: Direct Service Usage ---")
  println("\nCreating an order through OrderService:")
  {
    val result = orderService.createOrder(
      "customer-123",
      List("item-1", "item-2", "item-3"),
      299.99
    )
    result match
      case Right(order) => println(s"✅ Order created: ${order.id}")
      case Left(error) => println(s"❌ Failed: $error")
  }

  println("\n--- SCENARIO 2: Service-to-Service Communication ---")
  println("\nProcessing payment through PaymentService:")
  {
    val result = paymentService.processPayment("order-456", 299.99, "credit-card")
    result match
      case Right(payment) => println(s"✅ Payment processed: ${payment.id}")
      case Left(error) => println(s"❌ Payment failed: $error")
  }

  println("\n--- SCENARIO 3: Full Order Fulfillment Saga with Services via Context ---")
  println("\nOrchestrating services for complete order fulfillment:")
  {
    val result = OrderFulfillmentSagaV2.fulfillOrder(
      customerId = "customer-789",
      items = List("laptop", "mouse", "keyboard"),
      amount = 1299.99,
      paymentMethod = "credit-card",
      shippingAddress = "123 Main St, City, Country"
    )

    println(s"\nFulfillment Result:")
    println(s"  Success: ${result.success}")
    println(s"  Order: ${result.order.id} (${result.order.status})")
    println(s"  Payment: ${result.payment.map(_.id).getOrElse("N/A")}")
    println(s"  Shipment: ${result.shipment.map(_.id).getOrElse("N/A")}")
    if result.errors.nonEmpty then
      println(s"  Errors: ${result.errors.mkString(", ")}")
  }

  println("\n" + "=" * 80)
  println("KEY BENEFITS OF SERVICE FACADE PATTERN:")
  println("=" * 80)
  println("1. CLEAN PUBLIC API:")
  println("   - External consumers only see service methods")
  println("   - No need to know about capabilities or use cases")
  println("   - Service handles all dependency wiring")
  println()
  println("2. TESTABLE USE CASES:")
  println("   - Use cases declare minimal dependencies")
  println("   - Can test use cases independently with mocks")
  println("   - No need to mock entire service")
  println()
  println("3. SEPARATION OF CONCERNS:")
  println("   - Services = Public API + Dependency Management")
  println("   - Use Cases = Pure Business Logic")
  println("   - Capabilities = Infrastructure Contracts")
  println()
  println("4. EASY SERVICE-TO-SERVICE CALLS:")
  println("   - Sagas just call service methods")
  println("   - No capability wiring in saga code")
  println("   - Clean orchestration layer")
  println()
  println("5. FLEXIBILITY:")
  println("   - Can swap implementations at service level")
  println("   - Use cases remain unchanged")
  println("   - Easy to add new service methods")
  println("=" * 80)