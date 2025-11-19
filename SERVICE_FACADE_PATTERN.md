# Service Facade Pattern - Comprehensive Guide

**A Production-Ready Pattern for Scala 3 Microservices with DDD and Capabilities**

---

## Overview

The **Service Facade Pattern** combines the best aspects of the Service pattern and Use Case pattern to create a clean, testable, and maintainable architecture for microservices built with Domain-Driven Design (DDD) principles and capability-based design.

### Core Principle

**Services are facades that encapsulate all context capabilities, while use cases remain pure functions with minimal, fine-grained dependencies.**

---

## Architecture Layers

```
┌─────────────────────────────────────────────────────────┐
│ Saga/Orchestration Layer                                │
│ - Coordinates multiple service facades                  │
│ - Services passed via context functions (?)=>           │
│ - Only business parameters in signatures                │
└─────────────────────────────────────────────────────────┘
                        ↓ (context)
┌─────────────────────────────────────────────────────────┐
│ Service Facade Layer                                    │
│ - Encapsulates all bounded context capabilities         │
│ - Provides clean public API                             │
│ - Delegates to use cases with given capabilities        │
│ - One facade per bounded context                        │
└─────────────────────────────────────────────────────────┘
                        ↓ (delegates)
┌─────────────────────────────────────────────────────────┐
│ Use Case Layer                                          │
│ - Pure functions with minimal dependencies              │
│ - Declares only needed capabilities via context         │
│ - Contains business logic/orchestration                 │
│ - Testable in isolation                                 │
└─────────────────────────────────────────────────────────┘
                        ↓ (context)
┌─────────────────────────────────────────────────────────┐
│ Capability Layer                                        │
│ - Infrastructure contracts (traits)                     │
│ - Repositories, Validators, Gateways, etc.              │
│ - EventBus, Logger (cross-cutting)                      │
└─────────────────────────────────────────────────────────┘
```

---

## Key Components

### 1. Bounded Context Structure

Each bounded context contains:

```scala
object OrderContext:
  // Domain Models
  case class Order(...)

  // Fine-grained Capabilities
  trait OrderRepository
  trait OrderValidator

  // Capability Accessor Objects
  object OrderRepository:
    def save(order: Order): OrderRepository ?=> Unit = ...
    def findById(id: String): OrderRepository ?=> Option[Order] = ...

  // Pure Use Cases
  object OrderUseCases:
    def createOrder(...): (OrderRepository, OrderValidator, EventBus, Logger) ?=> Either[String, Order]
    def confirmOrder(...): (OrderRepository, EventBus, Logger) ?=> Either[String, Unit]

  // Service Facade
  class OrderServiceFacade(
    repository: OrderRepository,
    validator: OrderValidator,
    eventBus: EventBus,
    logger: Logger
  ):
    // Provide capabilities in scope
    given OrderRepository = repository
    given OrderValidator = validator
    given EventBus = eventBus
    given Logger = logger

    // Public API delegates to use cases
    def createOrder(...): Either[String, Order] =
      OrderUseCases.createOrder(...)
```

### 2. Use Cases - Pure Business Logic

**Characteristics:**
- Pure functions with no side effects in signature
- Declare only needed capabilities via context functions
- Use accessor objects for clean capability access
- Contain orchestration and business rules
- Return `Either[Error, Result]` for error handling

**Example:**
```scala
object OrderUseCases:
  // Minimal dependencies - only what's needed
  def createOrder(customerId: String, items: List[String], amount: Double): (
    OrderRepository,      // Only needs these 4 capabilities
    OrderValidator,
    EventBus,
    Logger
  ) ?=> Either[String, Order] =
    Logger.log("OrderContext", s"Creating order for customer $customerId")

    val order = Order(UUID.randomUUID().toString, customerId, items, amount, "PENDING")

    for
      validOrder <- OrderValidator.validateOrder(order)
      _ <- if OrderValidator.checkInventory(items) then Right(())
           else Left("Insufficient inventory")
      _ = OrderRepository.save(validOrder)
      _ = EventBus.publish("orders", s"OrderCreated:${validOrder.id}")
    yield validOrder
```

**Benefits:**
- ✅ Testable with minimal mocks
- ✅ Clear dependency declaration
- ✅ Reusable across different service facades
- ✅ Easy to compose into complex workflows

### 3. Service Facades - Public API

**Characteristics:**
- One facade per bounded context
- Encapsulates ALL context capabilities
- Provides capabilities to use cases via `given`
- Exposes clean public API
- Named with `Facade` suffix for clarity

**Example:**
```scala
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

  // Public API - delegates to use cases
  def createOrder(customerId: String, items: List[String], amount: Double): Either[String, Order] =
    OrderUseCases.createOrder(customerId, items, amount)

  def confirmOrder(orderId: String): Either[String, Unit] =
    OrderUseCases.confirmOrder(orderId)

  def findOrder(orderId: String): Option[Order] =
    repository.findById(orderId)  // Can also access capabilities directly
```

**Benefits:**
- ✅ Clean API for external consumers
- ✅ All dependency wiring hidden
- ✅ Easy to swap implementations
- ✅ Can add methods without touching use cases

### 4. Sagas - Cross-Context Orchestration

**Characteristics:**
- Coordinates multiple service facades
- Receives services via context functions
- Only business parameters in signature
- Handles compensating transactions
- Uses for-comprehension with Either

**Example:**
```scala
object OrderFulfillmentSaga:
  def fulfillOrder(
    customerId: String,
    items: List[String],
    amount: Double,
    paymentMethod: String,
    shippingAddress: String
  ): (
    OrderServiceFacade,
    PaymentServiceFacade,
    ShippingServiceFacade
  ) ?=> FulfillmentResult =

    val orderService = summon[OrderServiceFacade]
    val paymentService = summon[PaymentServiceFacade]
    val shippingService = summon[ShippingServiceFacade]

    val result = for
      // Step 1: Create Order
      order <- orderService.createOrder(customerId, items, amount)

      // Step 2: Process Payment with compensation
      payment <- paymentService.processPayment(order.id, amount, paymentMethod)
        .left.map { error =>
          orderService.cancelOrder(order.id, "Payment failed")
          error
        }

      // Step 3: Confirm Order
      _ <- orderService.confirmOrder(order.id)

      // Step 4: Create Shipment with compensation
      shipment <- shippingService.createShipment(order.id, shippingAddress)
        .left.map { error =>
          orderService.cancelOrder(order.id, "Shipment creation failed")
          error
        }
    yield (order, payment, shipment)

    // Map to result
    result.fold(
      error => FulfillmentResult(..., success = false, errors = List(error)),
      (order, payment, shipment) => FulfillmentResult(..., success = true, errors = Nil)
    )
```

**Benefits:**
- ✅ Ultra-clean orchestration code
- ✅ Services provided via context (no parameter pollution)
- ✅ Easy to test with mock facades
- ✅ Clear compensation logic

---

## Naming Conventions

### Consistent Naming is Critical

```scala
// ✅ GOOD: Clear, consistent naming
class OrderServiceFacade(...)
class PaymentServiceFacade(...)
class ShippingServiceFacade(...)

// ❌ BAD: Inconsistent naming
class OrderService(...)        // Unclear if facade or something else
class PaymentFacade(...)       // Missing "Service"
class ShippingServiceImpl(...) // Sounds like implementation
```

**Rule:** All service facades MUST be named `{Context}ServiceFacade`

---

## Capability Grouping Strategies

### Fine-Grained Capabilities (Infrastructure)

Individual capabilities for infrastructure concerns:

```scala
trait OrderRepository:
  def save(order: Order): Unit
  def findById(id: String): Option[Order]

trait OrderValidator:
  def validateOrder(order: Order): Either[String, Order]
  def checkInventory(items: List[String]): Boolean

trait EventBus:
  def publish(topic: String, event: String): Unit

trait Logger:
  def log(context: String, message: String): Unit
```

### Accessor Objects (Ergonomic Pattern)

Companion objects provide clean API via context functions:

```scala
object OrderRepository:
  def save(order: Order): OrderRepository ?=> Unit =
    summon[OrderRepository].save(order)

  def findById(id: String): OrderRepository ?=> Option[Order] =
    summon[OrderRepository].findById(id)
```

**Why?** Enables clean usage: `OrderRepository.save(order)` instead of `summon[OrderRepository].save(order)`

---

## Dependency Injection Patterns

### Service Construction

```scala
object OrderServiceFacade:
  def apply(
    repository: OrderRepository,
    validator: OrderValidator,
    eventBus: EventBus,
    logger: Logger
  ): OrderServiceFacade =
    new OrderServiceFacade(repository, validator, eventBus, logger)
```

### Providing Services to Sagas

```scala
@main def app(): Unit =
  // Build service facades
  given orderService: OrderServiceFacade = OrderServiceFacade(
    mockOrderRepository,
    mockOrderValidator,
    mockEventBus,
    mockLogger
  )

  given paymentService: PaymentServiceFacade = PaymentServiceFacade(
    mockPaymentGateway,
    mockPaymentRepository,
    mockEventBus,
    mockLogger
  )

  // Services automatically available in saga via context
  val result = OrderFulfillmentSaga.fulfillOrder(
    customerId = "user-123",
    items = List("laptop"),
    amount = 1299.99,
    paymentMethod = "credit-card",
    shippingAddress = "123 Main St"
  )
```

---

## Testing Strategies

### Testing Use Cases in Isolation

```scala
class OrderUseCasesTest extends munit.FunSuite:
  test("createOrder should validate and save order"):
    var savedOrder: Option[Order] = None
    var eventPublished = false

    // Mock only needed capabilities
    given OrderRepository = new OrderRepository:
      override def save(order: Order): Unit = savedOrder = Some(order)

    given OrderValidator = new OrderValidator:
      override def validateOrder(order: Order) = Right(order)
      override def checkInventory(items: List[String]) = true

    given EventBus = new EventBus:
      override def publish(topic: String, event: String): Unit = eventPublished = true

    given Logger = new Logger:
      override def log(context: String, message: String): Unit = ()

    // Execute use case
    val result = OrderUseCases.createOrder("customer-1", List("item-1"), 99.99)

    // Assertions
    assert(result.isRight)
    assert(savedOrder.isDefined)
    assert(eventPublished)
```

### Testing Service Facades

```scala
class OrderServiceFacadeTest extends munit.FunSuite:
  test("facade should delegate to use case"):
    // Build facade with mocks
    val facade = OrderServiceFacade(
      mockOrderRepository,
      mockOrderValidator,
      mockEventBus,
      mockLogger
    )

    // Use facade
    val result = facade.createOrder("customer-1", List("item-1"), 99.99)

    assert(result.isRight)
```

### Testing Sagas

```scala
class OrderFulfillmentSagaTest extends munit.FunSuite:
  test("saga should coordinate all services"):
    // Provide mock facades via context
    given OrderServiceFacade = mockOrderFacade
    given PaymentServiceFacade = mockPaymentFacade
    given ShippingServiceFacade = mockShippingFacade

    // Execute saga
    val result = OrderFulfillmentSaga.fulfillOrder(
      "customer-1",
      List("item-1"),
      99.99,
      "credit-card",
      "123 Main St"
    )

    assert(result.success)
```

---

## Comparison: Before vs After

### Before: Capability Bundle Pattern

```scala
// Entire context bundled
case class OrderContextCapabilities(
  repository: OrderRepository,
  validator: OrderValidator,
  eventBus: EventBus,
  logger: Logger
)

// Use cases require entire bundle
def createOrder(...): OrderContextCapabilities ?=> Either[String, Order]

// Saga receives capabilities
def fulfillOrder(...): (
  OrderContextCapabilities,
  PaymentContextCapabilities,
  ShippingContextCapabilities
) ?=> Result
```

**Issues:**
- ❌ Use cases get ALL capabilities even if only need some
- ❌ Testing requires mocking entire context
- ❌ No clear public API for external consumers
- ❌ Capability bundle = implementation detail exposed

### After: Service Facade Pattern

```scala
// Use cases declare minimal dependencies
def createOrder(...): (OrderRepository, OrderValidator, EventBus, Logger) ?=> Either[String, Order]

// Service facade encapsulates capabilities
class OrderServiceFacade(repository, validator, eventBus, logger):
  given OrderRepository = repository
  // ...
  def createOrder(...): Either[String, Order] =
    OrderUseCases.createOrder(...)

// Saga receives services
def fulfillOrder(...): (
  OrderServiceFacade,
  PaymentServiceFacade,
  ShippingServiceFacade
) ?=> Result
```

**Benefits:**
- ✅ Use cases have minimal, explicit dependencies
- ✅ Testing uses only needed mocks
- ✅ Clean public API via service facades
- ✅ Services = stable interface, use cases = internal implementation

---

## When to Use Service Facade Pattern

### ✅ Use When:
- Building microservices with DDD bounded contexts
- Need clear separation between public API and internal logic
- Want testable business logic with minimal dependencies
- Building production systems with multiple teams
- Need flexibility to swap implementations
- Using Scala 3 with context functions

### ❌ Don't Use When:
- Building simple CRUD applications
- Single monolithic service with no clear contexts
- Prototyping or proof-of-concept
- Team unfamiliar with functional patterns

---

## Best Practices

### 1. One Facade Per Bounded Context
Each bounded context gets exactly one service facade.

### 2. Use Cases Stay Pure
Never put infrastructure concerns in use cases. Always use capabilities.

### 3. Consistent Naming
Always use `{Context}ServiceFacade` naming pattern.

### 4. Context Functions Everywhere
Use `?=>` for all capability passing - from low-level to high-level.

### 5. Accessor Objects for Ergonomics
Provide companion objects for all capabilities.

### 6. Either for Error Handling
Use `Either[Error, Result]` for all fallible operations.

### 7. Immutable Data Structures
All domain models should be immutable (`case class`).

### 8. Event-Driven Communication
Use EventBus for cross-context communication.

---

## Migration Path

### From Capability Bundle Pattern

**Step 1:** Extract use cases from bundles
```scala
// Before
def createOrder(...): OrderContextCapabilities ?=> Result

// After
def createOrder(...): (OrderRepository, OrderValidator, EventBus, Logger) ?=> Result
```

**Step 2:** Create service facades
```scala
class OrderServiceFacade(repository, validator, eventBus, logger):
  given OrderRepository = repository
  given OrderValidator = validator
  given EventBus = eventBus
  given Logger = logger

  def createOrder(...) = OrderUseCases.createOrder(...)
```

**Step 3:** Update sagas to use facades
```scala
// Before
def saga(...): (OrderContextCapabilities, ...) ?=> Result

// After
def saga(...): (OrderServiceFacade, ...) ?=> Result
```

---

## Complete Example

See `src/main/scala/scaling/ServiceFacadePattern.scala` for a complete working example with:
- 3 bounded contexts (Order, Payment, Shipping)
- Service facades for each context
- Pure use cases with minimal dependencies
- Cross-context saga with compensation
- Mock implementations
- Full demonstration

Run with: `sbt "runMain scaling.servicefacade.serviceFacadeMain"`

---

## Key Takeaways

1. **Services are facades** - They encapsulate capabilities and provide clean APIs
2. **Use cases are pure** - They declare minimal dependencies and contain business logic
3. **Context functions everywhere** - From capabilities to use cases to facades to sagas
4. **Separation of concerns** - Services = API, Use Cases = Logic, Capabilities = Infrastructure
5. **Testability** - Each layer testable in isolation with minimal mocks
6. **Flexibility** - Swap implementations at service level without touching use cases
7. **Consistency** - Naming, structure, and patterns consistent across all contexts

---

**Author:** David Lebl
**Date:** 2025-11-19
**Version:** 1.0
**License:** MIT