package scaling

/*
  Demonstrating microservices architecture using bounded contexts
  with capability-based design.

  This example shows:
  1. How to model different bounded contexts as separate capability groups
  2. How services within a context share infrastructure but maintain separation
  3. How to compose cross-context operations (saga patterns, event-driven)
  4. How to provide implementations for entire bounded contexts
 */

// ============================================================================
// PART 1: Shared Infrastructure Capabilities
// ============================================================================

// These are shared across all bounded contexts

trait EventBus:
  def publish(topic: String, event: String): Unit
  def subscribe(topic: String, handler: String => Unit): Unit

object EventBus:
  def publish(topic: String, event: String): EventBus ?=> Unit =
    summon[EventBus].publish(topic, event)
  def subscribe(topic: String, handler: String => Unit): EventBus ?=> Unit =
    summon[EventBus].subscribe(topic, handler)

trait MessageQueue:
  def send(queue: String, message: String): Unit
  def receive(queue: String): Option[String]

object MessageQueue:
  def send(queue: String, message: String): MessageQueue ?=> Unit =
    summon[MessageQueue].send(queue, message)
  def receive(queue: String): MessageQueue ?=> Option[String] =
    summon[MessageQueue].receive(queue)

trait Logger:
  def log(context: String, message: String): Unit
  def error(context: String, message: String, error: Throwable): Unit

object Logger:
  def log(context: String, message: String): Logger ?=> Unit =
    summon[Logger].log(context, message)
  def error(context: String, message: String, error: Throwable): Logger ?=> Unit =
    summon[Logger].error(context, message, error)


// ============================================================================
// PART 2: Bounded Context 1 - Order Management
// ============================================================================

object OrderContext:
  // Domain Models
  case class Order(id: String, customerId: String, items: List[String], totalAmount: Double, status: String)
  case class OrderCreated(orderId: String, customerId: String, totalAmount: Double)
  case class OrderConfirmed(orderId: String)
  case class OrderCancelled(orderId: String, reason: String)

  // Capabilities within Order Context
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

  // Aggregate all Order Context capabilities
  case class OrderContextCapabilities(
    repository: OrderRepository,
    validator: OrderValidator,
    eventBus: EventBus,
    logger: Logger
  )

  // Use Cases within Order Context
  object OrderUseCases:
    def createOrder(customerId: String, items: List[String], amount: Double): OrderContextCapabilities ?=> Either[String, Order] =
      val ctx = summon[OrderContextCapabilities]
      given OrderRepository = ctx.repository
      given OrderValidator = ctx.validator
      given EventBus = ctx.eventBus
      given Logger = ctx.logger

      Logger.log("OrderContext", s"Creating order for customer $customerId")

      val order = Order(
        id = java.util.UUID.randomUUID().toString,
        customerId = customerId,
        items = items,
        totalAmount = amount,
        status = "PENDING"
      )

      OrderValidator.validateOrder(order) match
        case Right(validOrder) =>
          if OrderValidator.checkInventory(items) then
            OrderRepository.save(validOrder)
            EventBus.publish("orders", s"OrderCreated:${validOrder.id}")
            Logger.log("OrderContext", s"Order ${validOrder.id} created successfully")
            Right(validOrder)
          else
            Logger.log("OrderContext", s"Inventory check failed for order")
            Left("Insufficient inventory")
        case Left(error) =>
          Logger.log("OrderContext", s"Order validation failed: $error")
          Left(error)

    def confirmOrder(orderId: String): OrderContextCapabilities ?=> Either[String, Unit] =
      val ctx = summon[OrderContextCapabilities]
      given OrderRepository = ctx.repository
      given EventBus = ctx.eventBus
      given Logger = ctx.logger

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

    def cancelOrder(orderId: String, reason: String): OrderContextCapabilities ?=> Either[String, Unit] =
      val ctx = summon[OrderContextCapabilities]
      given OrderRepository = ctx.repository
      given EventBus = ctx.eventBus
      given Logger = ctx.logger

      Logger.log("OrderContext", s"Cancelling order $orderId")

      OrderRepository.findById(orderId) match
        case Some(order) =>
          OrderRepository.updateStatus(orderId, "CANCELLED")
          EventBus.publish("orders", s"OrderCancelled:$orderId:$reason")
          Logger.log("OrderContext", s"Order $orderId cancelled: $reason")
          Right(())
        case None =>
          Left(s"Order $orderId not found")


// ============================================================================
// PART 3: Bounded Context 2 - Payment Processing
// ============================================================================

object PaymentContext:
  // Domain Models
  case class Payment(id: String, orderId: String, amount: Double, status: String, method: String)
  case class PaymentProcessed(paymentId: String, orderId: String, amount: Double)
  case class PaymentFailed(paymentId: String, orderId: String, reason: String)

  // Capabilities within Payment Context
  trait PaymentGateway:
    def processPayment(amount: Double, method: String): Either[String, String] // Returns transaction ID
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

  // Aggregate all Payment Context capabilities
  case class PaymentContextCapabilities(
    gateway: PaymentGateway,
    repository: PaymentRepository,
    eventBus: EventBus,
    logger: Logger
  )

  // Use Cases within Payment Context
  object PaymentUseCases:
    def processPayment(orderId: String, amount: Double, method: String): PaymentContextCapabilities ?=> Either[String, Payment] =
      val ctx = summon[PaymentContextCapabilities]
      given PaymentGateway = ctx.gateway
      given PaymentRepository = ctx.repository
      given EventBus = ctx.eventBus
      given Logger = ctx.logger

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


// ============================================================================
// PART 4: Bounded Context 3 - Shipping & Fulfillment
// ============================================================================

object ShippingContext:
  // Domain Models
  case class Shipment(id: String, orderId: String, address: String, status: String)
  case class ShipmentCreated(shipmentId: String, orderId: String)

  // Capabilities within Shipping Context
  trait ShippingService:
    def createShipment(orderId: String, address: String): Either[String, String] // Returns tracking number
    def trackShipment(trackingNumber: String): Option[String]

  object ShippingService:
    def createShipment(orderId: String, address: String): ShippingService ?=> Either[String, String] =
      summon[ShippingService].createShipment(orderId, address)

  trait ShipmentRepository:
    def save(shipment: Shipment): Unit
    def findByOrderId(orderId: String): Option[Shipment]

  object ShipmentRepository:
    def save(shipment: Shipment): ShipmentRepository ?=> Unit =
      summon[ShipmentRepository].save(shipment)

  // Aggregate all Shipping Context capabilities
  case class ShippingContextCapabilities(
    service: ShippingService,
    repository: ShipmentRepository,
    eventBus: EventBus,
    logger: Logger
  )

  // Use Cases within Shipping Context
  object ShippingUseCases:
    def createShipment(orderId: String, address: String): ShippingContextCapabilities ?=> Either[String, Shipment] =
      val ctx = summon[ShippingContextCapabilities]
      given ShippingService = ctx.service
      given ShipmentRepository = ctx.repository
      given EventBus = ctx.eventBus
      given Logger = ctx.logger

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


// ============================================================================
// PART 5: Cross-Context Orchestration (Saga Pattern)
// ============================================================================

object OrderFulfillmentSaga:
  case class FulfillmentResult(
    order: OrderContext.Order,
    payment: Option[PaymentContext.Payment],
    shipment: Option[ShippingContext.Shipment],
    success: Boolean,
    errors: List[String]
  )

  // This saga coordinates across all three bounded contexts
  def fulfillOrder(
    customerId: String,
    items: List[String],
    amount: Double,
    paymentMethod: String,
    shippingAddress: String
  ): (
    OrderContext.OrderContextCapabilities,
    PaymentContext.PaymentContextCapabilities,
    ShippingContext.ShippingContextCapabilities
  ) ?=> FulfillmentResult =

    given OrderContext.OrderContextCapabilities = summon[OrderContext.OrderContextCapabilities]
    given PaymentContext.PaymentContextCapabilities = summon[PaymentContext.PaymentContextCapabilities]
    given ShippingContext.ShippingContextCapabilities = summon[ShippingContext.ShippingContextCapabilities]

    var errors = List.empty[String]
    var paymentOpt: Option[PaymentContext.Payment] = None
    var shipmentOpt: Option[ShippingContext.Shipment] = None

    // Using for-comprehension with Either for cleaner saga flow
    val result = for
      // Step 1: Create Order
      order <- OrderContext.OrderUseCases.createOrder(customerId, items, amount)

      // Step 2: Process Payment
      payment <- PaymentContext.PaymentUseCases.processPayment(order.id, amount, paymentMethod)
        .left.map { error =>
          // Compensating transaction: Cancel order on payment failure
          OrderContext.OrderUseCases.cancelOrder(order.id, "Payment failed")
          error
        }

      // Step 3: Confirm Order
      _ <- OrderContext.OrderUseCases.confirmOrder(order.id)

      // Step 4: Create Shipment
      shipment <- ShippingContext.ShippingUseCases.createShipment(order.id, shippingAddress)
        .left.map { error =>
          // Compensating transaction: Cancel order on shipment failure
          OrderContext.OrderUseCases.cancelOrder(order.id, "Shipment creation failed")
          error
        }
    yield (order, Some(payment), Some(shipment))

    // Map result to FulfillmentResult
    result match
      case Right((order, payment, shipment)) =>
        FulfillmentResult(order, payment, shipment, success = true, errors)

      case Left(error) =>
        errors = error :: errors
        // No order created, nothing to compensate
        FulfillmentResult(
          OrderContext.Order("", customerId, items, amount, "FAILED"),
          None,
          None,
          success = false,
          errors
        )


// ============================================================================
// PART 6: Mock Implementations
// ============================================================================

object BoundedContextMocks:
  // Shared infrastructure mocks
  val mockEventBus: EventBus = new EventBus:
    override def publish(topic: String, event: String): Unit =
      println(s"[EventBus] Published to '$topic': $event")
    override def subscribe(topic: String, handler: String => Unit): Unit =
      println(s"[EventBus] Subscribed to '$topic'")

  val mockMessageQueue: MessageQueue = new MessageQueue:
    override def send(queue: String, message: String): Unit =
      println(s"[MessageQueue] Sent to '$queue': $message")
    override def receive(queue: String): Option[String] =
      println(s"[MessageQueue] Receiving from '$queue'")
      None

  val mockLogger: Logger = new Logger:
    override def log(context: String, message: String): Unit =
      println(s"[LOG][$context] $message")
    override def error(context: String, message: String, error: Throwable): Unit =
      println(s"[ERROR][$context] $message: ${error.getMessage}")

  // Order Context mocks
  val mockOrderRepository: OrderContext.OrderRepository = new OrderContext.OrderRepository:
    private var orders = Map.empty[String, OrderContext.Order]
    override def save(order: OrderContext.Order): Unit =
      println(s"[OrderRepository] Saving order ${order.id}")
      orders = orders + (order.id -> order)
    override def findById(id: String): Option[OrderContext.Order] =
      orders.get(id)
    override def updateStatus(id: String, status: String): Unit =
      println(s"[OrderRepository] Updating order $id status to $status")
      orders.get(id).foreach { order =>
        orders = orders + (id -> order.copy(status = status))
      }

  val mockOrderValidator: OrderContext.OrderValidator = new OrderContext.OrderValidator:
    override def validateOrder(order: OrderContext.Order): Either[String, OrderContext.Order] =
      if order.items.isEmpty then Left("Order must have items")
      else if order.totalAmount <= 0 then Left("Order amount must be positive")
      else Right(order)
    override def checkInventory(items: List[String]): Boolean =
      println(s"[OrderValidator] Checking inventory for ${items.size} items")
      true // Mock always returns true

  // Payment Context mocks
  val mockPaymentGateway: PaymentContext.PaymentGateway = new PaymentContext.PaymentGateway:
    override def processPayment(amount: Double, method: String): Either[String, String] =
      println(s"[PaymentGateway] Processing payment of $$${amount} via $method")
      if amount > 0 then Right(s"TXN-${java.util.UUID.randomUUID().toString.take(8)}")
      else Left("Invalid amount")
    override def refund(transactionId: String, amount: Double): Either[String, Unit] =
      println(s"[PaymentGateway] Refunding $$${amount} for transaction $transactionId")
      Right(())

  val mockPaymentRepository: PaymentContext.PaymentRepository = new PaymentContext.PaymentRepository:
    private var payments = Map.empty[String, PaymentContext.Payment]
    override def save(payment: PaymentContext.Payment): Unit =
      println(s"[PaymentRepository] Saving payment ${payment.id}")
      payments = payments + (payment.id -> payment)
    override def findByOrderId(orderId: String): Option[PaymentContext.Payment] =
      payments.values.find(_.orderId == orderId)

  // Shipping Context mocks
  val mockShippingService: ShippingContext.ShippingService = new ShippingContext.ShippingService:
    override def createShipment(orderId: String, address: String): Either[String, String] =
      println(s"[ShippingService] Creating shipment for order $orderId to $address")
      Right(s"TRACK-${java.util.UUID.randomUUID().toString.take(8)}")
    override def trackShipment(trackingNumber: String): Option[String] =
      Some("In Transit")

  val mockShipmentRepository: ShippingContext.ShipmentRepository = new ShippingContext.ShipmentRepository:
    private var shipments = Map.empty[String, ShippingContext.Shipment]
    override def save(shipment: ShippingContext.Shipment): Unit =
      println(s"[ShipmentRepository] Saving shipment ${shipment.id}")
      shipments = shipments + (shipment.id -> shipment)
    override def findByOrderId(orderId: String): Option[ShippingContext.Shipment] =
      shipments.values.find(_.orderId == orderId)

  // Pre-built context capabilities
  given OrderContext.OrderContextCapabilities = OrderContext.OrderContextCapabilities(
    mockOrderRepository,
    mockOrderValidator,
    mockEventBus,
    mockLogger
  )

  given PaymentContext.PaymentContextCapabilities = PaymentContext.PaymentContextCapabilities(
    mockPaymentGateway,
    mockPaymentRepository,
    mockEventBus,
    mockLogger
  )

  given ShippingContext.ShippingContextCapabilities = ShippingContext.ShippingContextCapabilities(
    mockShippingService,
    mockShipmentRepository,
    mockEventBus,
    mockLogger
  )


// ============================================================================
// PART 7: Demonstration
// ============================================================================

@main def boundedContextsMain(): Unit =
  println("=" * 80)
  println("BOUNDED CONTEXTS & MICROSERVICES ARCHITECTURE")
  println("=" * 80)

  import BoundedContextMocks.given

  println("\n--- SCENARIO 1: Single Context Operations ---")
  println("\nCreating an order within Order Context:")
  {
    val result = OrderContext.OrderUseCases.createOrder(
      "customer-123",
      List("item-1", "item-2", "item-3"),
      299.99
    )
    result match
      case Right(order) => println(s"✅ Order created: ${order.id}")
      case Left(error) => println(s"❌ Failed: $error")
  }

  println("\n--- SCENARIO 2: Cross-Context Communication ---")
  println("\nProcessing payment (separate context):")
  {
    val result = PaymentContext.PaymentUseCases.processPayment(
      "order-456",
      299.99,
      "credit-card"
    )
    result match
      case Right(payment) => println(s"✅ Payment processed: ${payment.id}")
      case Left(error) => println(s"❌ Payment failed: $error")
  }

  println("\n--- SCENARIO 3: Full Order Fulfillment Saga ---")
  println("\nOrchestrating across Order, Payment, and Shipping contexts:")
  {
    val result = OrderFulfillmentSaga.fulfillOrder(
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
  println("KEY OBSERVATIONS:")
  println("=" * 80)
  println("1. BOUNDED CONTEXT ISOLATION:")
  println("   - Each context has its own capabilities and models")
  println("   - Order, Payment, and Shipping are independent")
  println("   - Changes in one context don't affect others")
  println()
  println("2. CONTEXT CAPABILITIES:")
  println("   - OrderContextCapabilities bundles all Order-related capabilities")
  println("   - PaymentContextCapabilities bundles all Payment-related capabilities")
  println("   - ShippingContextCapabilities bundles all Shipping-related capabilities")
  println()
  println("3. CROSS-CONTEXT COORDINATION:")
  println("   - Saga pattern coordinates multiple contexts")
  println("   - Each context can fail independently")
  println("   - Compensating transactions handle failures")
  println()
  println("4. EVENT-DRIVEN COMMUNICATION:")
  println("   - Contexts communicate via EventBus")
  println("   - Loose coupling between contexts")
  println("   - Async communication possible")
  println()
  println("5. TESTING BENEFITS:")
  println("   - Test each context independently")
  println("   - Mock only needed contexts for saga testing")
  println("   - Clear boundaries simplify testing")
  println("=" * 80)